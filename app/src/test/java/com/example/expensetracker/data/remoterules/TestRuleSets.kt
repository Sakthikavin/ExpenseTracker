package com.example.expensetracker.data.remoterules

import org.json.JSONArray
import org.json.JSONObject

/**
 * The published rules, as test fixtures.
 *
 * Both files are copies of what the console publishes, and they're copies on purpose: the app
 * ships no rules (`PARSING_ARCHITECTURE.md` §4), so a corpus expectation is only evidence if the
 * pattern behind it is the real one rather than something written here to pass. Refresh them from
 * the console repo (`data/generic-rules.json`, and `/rules/current` for the bank rules) when those
 * change.
 */
object TestRuleSets {

    /** The seven any-sender rules that used to be `BankTemplates` and `BlockFormat`. */
    const val GENERIC = "/generic-rules.json"

    /** The published TMBANK rules — the corpus's TMB pair is unreadable without them. */
    const val BANK = "/bank-rules.json"

    fun ruleSetJson(vararg resources: String, version: Int = 16, extraRules: String = "[]"): String =
        JSONObject()
            .put("version", version)
            .put("updatedAt", "2026-10-01T00:00:00Z")
            .put("discardSenders", JSONArray())
            .put("rules", JSONArray(rulesArray(*resources, extraRules = extraRules)))
            .put("ignoreRules", JSONArray())
            .toString()

    /** Just the `rules` array, for a test that assembles the rest of the document itself. */
    fun rulesArray(vararg resources: String, extraRules: String = "[]"): String {
        val rules = JSONArray()
        for (resource in resources) {
            val published = JSONObject(read(resource)).getJSONArray("rules")
            for (i in 0 until published.length()) rules.put(published.getJSONObject(i))
        }
        val extra = JSONArray(extraRules)
        for (i in 0 until extra.length()) rules.put(extra.getJSONObject(i))
        return rules.toString()
    }

    private fun read(resource: String): String =
        requireNotNull(TestRuleSets::class.java.getResource(resource)) {
            "src/test/resources$resource is missing — copy it from the console repo"
        }.readText()
}

/**
 * Builds a [RemoteRulesRepository] already holding a rule set, by seeding the disk cache it loads
 * and compiles on construction — the same state a phone is in after any successful sync.
 */
fun repositoryWith(ruleSetJson: String): RemoteRulesRepository =
    RemoteRulesRepository(
        RemoteRulesApi(),
        FakeSharedPreferences(mapOf("remote_rules_cached_set" to ruleSetJson)),
    )

/** A phone that has never synced: no cached set, so nothing matches. */
fun repositoryWithNoRules(): RemoteRulesRepository =
    RemoteRulesRepository(RemoteRulesApi(), FakeSharedPreferences())
