package com.example.expensetracker.ui.settings

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Telephony
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.repository.IngestResult
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class SettingsViewModel(private val smsRepository: SmsRepository) : ViewModel() {

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _lastImportAt = MutableStateFlow<Instant?>(null)
    val lastImportAt: StateFlow<Instant?> = _lastImportAt.asStateFlow()

    private val _ignoreBelowMinor = MutableStateFlow(SmsParser.DEFAULT_IGNORE_BELOW_MINOR)
    val ignoreBelowMinor: StateFlow<Long> = _ignoreBelowMinor.asStateFlow()

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
     */
    fun startImport(contentResolver: ContentResolver, prefs: SharedPreferences) {
        if (_importProgress.value.isRunning) return
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isRunning = true)
            val messages = withContext(Dispatchers.IO) { readInbox(contentResolver) }

            var imported = 0
            var needsReview = 0
            var ignored = 0
            messages.forEachIndexed { index, message ->
                when (smsRepository.ingest(message.sender, message.body, message.receivedAt)) {
                    IngestResult.IMPORTED -> imported++
                    IngestResult.NEEDS_REVIEW -> needsReview++
                    IngestResult.IGNORED -> ignored++
                }
                _importProgress.value = ImportProgress(
                    isRunning = true,
                    scanned = index + 1,
                    total = messages.size,
                    imported = imported,
                    needsReview = needsReview,
                    ignored = ignored,
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
