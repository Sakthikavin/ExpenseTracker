package com.example.expensetracker.data.remoterules

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.sms.ParsedSms
import com.example.expensetracker.data.sms.PatternLearner
import com.example.expensetracker.data.sms.SmsDateParser
import com.example.expensetracker.data.sms.parseAmountToMinorUnits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

sealed interface RuleSyncResult {
    data class UpToDate(val version: Int) : RuleSyncResult
    data class Updated(val fromVersion: Int?, val toVersion: Int, val newRuleCount: Int) : RuleSyncResult
    data object Failed : RuleSyncResult
}

private data class CompiledRule(val rule: RemoteRule, val regex: Regex)

/**
 * Caches the published remote rule set (REQUIREMENTS.md §9/§10) and applies it as tier 2 of
 * `SmsParser.parse` (§5). One instance lives for the app's lifetime — see
 * [com.example.expensetracker.di.AppContainer].
 */
class RemoteRulesRepository(
    private val api: RemoteRulesApi,
    private val prefs: SharedPreferences,
) {
    /** Guards concurrent syncs — the daily worker and a manual "Check now" tap can race. */
    private val syncMutex = Mutex()

    @Volatile
    private var cached: RemoteRuleSet? = null

    @Volatile
    private var compiled: List<CompiledRule> = emptyList()

    init {
        loadFromDisk()?.let { setCache(it) }
    }

    val cachedVersion: Int? get() = cached?.version
    val lastCheckedAtMillis: Long? get() = prefs.getLong(PREF_LAST_CHECKED_AT, -1L).takeIf { it >= 0 }

    /** On app launch: only actually hits the network once per 24h (§8.1). */
    suspend fun syncIfDue(now: Long = System.currentTimeMillis()): RuleSyncResult? {
        val last = lastCheckedAtMillis
        if (last != null && now - last < ONE_DAY_MILLIS) return null
        return sync()
    }

    /** The daily background worker and the manual "Check now" button (§8.1/§8.2) both call this. */
    suspend fun sync(): RuleSyncResult = syncMutex.withLock {
        val previous = cached
        val fetched = api.fetchCurrent() ?: return@withLock RuleSyncResult.Failed
        prefs.edit { putLong(PREF_LAST_CHECKED_AT, System.currentTimeMillis()) }

        if (previous != null && fetched.version == previous.version) {
            return@withLock RuleSyncResult.UpToDate(fetched.version)
        }

        val previousIds = previous?.rules.orEmpty().map { it.id }.toSet()
        val newRuleCount = fetched.rules.count { it.id !in previousIds }
        setCache(fetched)
        saveToDisk(fetched)
        RuleSyncResult.Updated(fromVersion = previous?.version, toVersion = fetched.version, newRuleCount = newRuleCount)
    }

    /** §5.2 step 1: a normalized sender in `discardSenders` is noise, not just unmatched. */
    fun isDiscardedSender(sender: String): Boolean =
        PatternLearner.normaliseSender(sender) in cached?.discardSenders.orEmpty()

    /**
     * Tier 2 of `SmsParser.parse` (§5). Follows the console's exact prediction order (§5.2): rules
     * for this sender by `priority` descending (ties keep publish order), first rule that matches
     * *and* fills every `fieldMap` group wins, non-compiling rules are already excluded from
     * [compiled].
     */
    fun tryMatch(sender: String, body: String): ParsedSms? {
        if (isDiscardedSender(sender)) return null
        val normalized = PatternLearner.normaliseSender(sender)
        return compiled
            .filter { normalized in it.rule.senders }
            .sortedByDescending { it.rule.priority }
            .firstNotNullOfOrNull { applyRule(it, body) }
    }

    private fun applyRule(candidate: CompiledRule, body: String): ParsedSms? {
        val match = candidate.regex.find(body) ?: return null
        // Every mapped group must yield a non-empty value, or this rule falls through (§5.2 step 3).
        val values = candidate.rule.fieldMap.mapValues { (_, group) ->
            match.groupValues.getOrNull(group)?.takeIf { it.isNotEmpty() } ?: return null
        }
        val amountMinor = values["amount"]?.let(::parseAmountToMinorUnits) ?: return null
        val merchant = values["merchant"]?.trim().orEmpty()
        return ParsedSms(
            amountMinor = amountMinor,
            direction = candidate.rule.direction,
            merchant = merchant,
            occurredAt = SmsDateParser.parse(body),
        )
    }

    private fun setCache(set: RemoteRuleSet) {
        cached = set
        // A rule whose pattern fails to compile is skipped and logged, never crashes the parser
        // (§10) — same defensive posture as PatternLearner.applyToBody.
        compiled = set.rules.mapNotNull { rule ->
            runCatching { Regex(rule.pattern) }.getOrNull()?.let { CompiledRule(rule, it) }
        }
    }

    private fun saveToDisk(set: RemoteRuleSet) {
        val json = JSONObject().apply {
            put("version", set.version)
            put("updatedAt", set.updatedAt)
            put("discardSenders", JSONArray(set.discardSenders))
            put(
                "rules",
                JSONArray(
                    set.rules.map { rule ->
                        JSONObject().apply {
                            put("id", rule.id)
                            put("senders", JSONArray(rule.senders))
                            put("direction", if (rule.direction == Direction.CREDIT) "credit" else "debit")
                            put("pattern", rule.pattern)
                            put("fieldMap", JSONObject(rule.fieldMap))
                            put("priority", rule.priority)
                        }
                    },
                ),
            )
        }
        prefs.edit { putString(PREF_CACHED_RULE_SET, json.toString()) }
    }

    private fun loadFromDisk(): RemoteRuleSet? {
        val raw = prefs.getString(PREF_CACHED_RULE_SET, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val rulesArray = json.getJSONArray("rules")
            RemoteRuleSet(
                version = json.getInt("version"),
                updatedAt = json.optString("updatedAt"),
                discardSenders = json.getJSONArray("discardSenders").toStringList(),
                rules = (0 until rulesArray.length()).map { i ->
                    val r = rulesArray.getJSONObject(i)
                    val fieldMapJson = r.getJSONObject("fieldMap")
                    RemoteRule(
                        id = r.getString("id"),
                        senders = r.getJSONArray("senders").toStringList(),
                        direction = if (r.optString("direction") == "credit") Direction.CREDIT else Direction.DEBIT,
                        pattern = r.getString("pattern"),
                        fieldMap = fieldMapJson.keys().asSequence().associateWith { fieldMapJson.getInt(it) },
                        priority = r.optInt("priority", 0),
                    )
                },
            )
        }.getOrNull()
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        const val PREF_CACHED_RULE_SET = "remote_rules_cached_set"
        const val PREF_LAST_CHECKED_AT = "remote_rules_last_checked_at"
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
