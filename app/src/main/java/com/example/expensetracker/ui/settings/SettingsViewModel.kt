package com.example.expensetracker.ui.settings

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Telephony
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.remoterules.RuleSyncCoordinator
import com.example.expensetracker.data.remoterules.RuleSyncResult
import com.example.expensetracker.data.repository.IngestResult
import com.example.expensetracker.data.repository.ReparseOutcome
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Live state of the one-time SMS history backfill; see [SettingsViewModel.startImport]. */
data class ImportProgress(
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val imported: Int = 0,
    val needsReview: Int = 0,
    val ignored: Int = 0,
    /** Mentioned money but matched nothing — the rows "Messages I skipped" lists. */
    val skipped: Int = 0,
    /**
     * Set when the import ran with no rules available at all, which means nothing could be parsed
     * and every financial-looking message went to review. The next successful sync re-reads them.
     */
    val ranWithoutRules: Boolean = false,
)

/** Live state of the "Rule updates" section (§8.2). */
data class RuleUpdateState(
    val version: Int?,
    val lastCheckedAtMillis: Long?,
    val isChecking: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val smsRepository: SmsRepository,
    private val ruleSyncCoordinator: RuleSyncCoordinator,
) : ViewModel() {

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _lastImportAt = MutableStateFlow<Instant?>(null)
    val lastImportAt: StateFlow<Instant?> = _lastImportAt.asStateFlow()

    private val _ignoreBelowMinor = MutableStateFlow(SmsParser.DEFAULT_IGNORE_BELOW_MINOR)
    val ignoreBelowMinor: StateFlow<Long> = _ignoreBelowMinor.asStateFlow()

    private val _ruleUpdateState = MutableStateFlow(RuleUpdateState(version = null, lastCheckedAtMillis = null))
    val ruleUpdateState: StateFlow<RuleUpdateState> = _ruleUpdateState.asStateFlow()

    /** Drives the "Messages I skipped" row — zero hides it, since an empty diagnostic list is noise. */
    val skippedCount: StateFlow<Int> = smsRepository.observeDiscardedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Re-reads the repository's in-memory cache. Needed because the app-launch `syncIfDue()` and
     * the daily worker both run outside this ViewModel and can finish after it was constructed —
     * a plain constructor-time snapshot would go stale the moment either of those completes.
     */
    fun loadRuleUpdateState() {
        _ruleUpdateState.value = _ruleUpdateState.value.copy(
            version = ruleSyncCoordinator.cachedVersion,
            lastCheckedAtMillis = ruleSyncCoordinator.lastCheckedAtMillis,
        )
    }

    fun loadLastImportAt(prefs: SharedPreferences) {
        val millis = prefs.getLong(PREF_LAST_IMPORT_AT, -1L)
        _lastImportAt.value = millis.takeIf { it >= 0 }?.let(Instant::fromEpochMilliseconds)
    }

    fun loadIgnoreBelowMinor(prefs: SharedPreferences) {
        _ignoreBelowMinor.value =
            prefs.getLong(SmsParser.PREF_IGNORE_BELOW_MINOR, SmsParser.DEFAULT_IGNORE_BELOW_MINOR)
    }

    /** [minor] is already paise; the screen converts what the user types in rupees. */
    fun setIgnoreBelowMinor(prefs: SharedPreferences, minor: Long) {
        prefs.edit { putLong(SmsParser.PREF_IGNORE_BELOW_MINOR, minor) }
        _ignoreBelowMinor.value = minor
    }

    /**
     * Reads every message in the SMS inbox and feeds each one through the same
     * [SmsRepository.ingest] the live receiver uses per message — no separate parsing or dedup
     * logic, so re-running this (or running it after the live receiver already caught some of the
     * same messages) is exactly as safe as re-delivering a single SMS broadcast.
     *
     * Syncs the rules first when the phone has none (`PARSING_ARCHITECTURE.md` §4): the app ships
     * no rules, so an import on a fresh install would otherwise push the whole inbox into the
     * review queue. If the sync fails the import still runs — the queue is recoverable, a refused
     * import leaves the user with no way to backfill at all — but it says so in the summary.
     */
    fun startImport(contentResolver: ContentResolver, prefs: SharedPreferences) {
        if (_importProgress.value.isRunning) return
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isRunning = true)
            if (ruleSyncCoordinator.cachedVersion == null) ruleSyncCoordinator.sync()
            val ranWithoutRules = ruleSyncCoordinator.cachedVersion == null
            val messages = withContext(Dispatchers.IO) { readInbox(contentResolver) }

            var imported = 0
            var needsReview = 0
            var ignored = 0
            var skipped = 0
            messages.forEachIndexed { index, message ->
                when (smsRepository.ingest(message.sender, message.body, message.receivedAt)) {
                    IngestResult.IMPORTED -> imported++
                    IngestResult.NEEDS_REVIEW -> needsReview++
                    IngestResult.IGNORED -> ignored++
                    IngestResult.DISCARDED -> skipped++
                }
                _importProgress.value = ImportProgress(
                    isRunning = true,
                    scanned = index + 1,
                    total = messages.size,
                    imported = imported,
                    needsReview = needsReview,
                    ignored = ignored,
                    skipped = skipped,
                    ranWithoutRules = ranWithoutRules,
                )
            }

            _importProgress.value = _importProgress.value.copy(isRunning = false, isComplete = true)
            val now = Clock.System.now()
            prefs.edit { putLong(PREF_LAST_IMPORT_AT, now.toEpochMilliseconds()) }
            _lastImportAt.value = now
        }
    }

    /** Closes the completion sheet; a no-op while the import is still running. */
    fun dismissSummary() {
        if (_importProgress.value.isRunning) return
        _importProgress.value = ImportProgress()
    }

    /** "Check now" (§8.2) — same sync path the daily worker uses, run synchronously for the tap. */
    fun checkForRuleUpdates() {
        if (_ruleUpdateState.value.isChecking) return
        viewModelScope.launch {
            _ruleUpdateState.value = _ruleUpdateState.value.copy(isChecking = true, message = null)
            val outcome = ruleSyncCoordinator.sync()
            val message = when (val result = outcome.result) {
                is RuleSyncResult.UpToDate -> "Already up to date (v${result.version})"
                is RuleSyncResult.Updated -> {
                    val ruleWord = if (result.newRuleCount == 1) "rule" else "rules"
                    "Updated to v${result.toVersion} — ${result.newRuleCount} new $ruleWord." +
                        reparseSummary(outcome.reparse)
                }
                RuleSyncResult.Failed -> "Couldn't reach the rules server. Try again later."
            }
            _ruleUpdateState.value = RuleUpdateState(
                version = ruleSyncCoordinator.cachedVersion,
                lastCheckedAtMillis = ruleSyncCoordinator.lastCheckedAtMillis,
                isChecking = false,
                message = message,
            )
        }
    }

    /** The half of §8.2's message that reports what the new rules did to the existing queue. */
    private fun reparseSummary(reparse: ReparseOutcome?): String = when {
        reparse == null || reparse.checked == 0 -> ""
        // "Unmatched" rather than "pending": the walk now covers skipped messages too, not just
        // the ones waiting in the review queue.
        else -> " Re-checked ${reparse.checked} unmatched ${"message".plural(reparse.checked)}, cleared ${reparse.cleared}."
    }

    private fun String.plural(count: Int) = if (count == 1) this else this + "s"

    private fun readInbox(contentResolver: ContentResolver): List<InboxMessage> {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val messages = mutableListOf<InboxMessage>()
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                messages += InboxMessage(
                    sender = cursor.getString(addressColumn).orEmpty(),
                    body = cursor.getString(bodyColumn).orEmpty(),
                    receivedAt = Instant.fromEpochMilliseconds(cursor.getLong(dateColumn)),
                )
            }
        }
        return messages
    }

    private data class InboxMessage(val sender: String, val body: String, val receivedAt: Instant)

    private companion object {
        const val PREF_LAST_IMPORT_AT = "sms_import_last_run_at"
    }
}
