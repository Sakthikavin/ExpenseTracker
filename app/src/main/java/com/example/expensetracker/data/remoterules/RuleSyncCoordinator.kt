package com.example.expensetracker.data.remoterules

import com.example.expensetracker.data.repository.ReparseOutcome
import com.example.expensetracker.data.repository.SmsRepository

/** A rule pull and, when it brought something new, what re-reading the review queue achieved. */
data class RuleSyncOutcome(val result: RuleSyncResult, val reparse: ReparseOutcome?)

/**
 * Pairs a rule pull with the backlog re-parse it implies (REQUIREMENTS §8.1): a rule written for a
 * message already sitting in review should clear it on the next sync, not wait for the bank to send
 * a matching one.
 *
 * It lives here rather than inside [RemoteRulesRepository] because the parser already depends on
 * that repository — having it call back into [SmsRepository] would close the loop into a cycle.
 */
class RuleSyncCoordinator(
    private val remoteRulesRepository: RemoteRulesRepository,
    private val smsRepository: SmsRepository,
) {
    val cachedVersion: Int? get() = remoteRulesRepository.cachedVersion
    val lastCheckedAtMillis: Long? get() = remoteRulesRepository.lastCheckedAtMillis

    suspend fun sync(): RuleSyncOutcome = withReparse(remoteRulesRepository.sync())

    suspend fun syncIfDue(): RuleSyncOutcome? = remoteRulesRepository.syncIfDue()?.let { withReparse(it) }

    // Only a version bump can change how anything parses, so an up-to-date check stays cheap.
    private suspend fun withReparse(result: RuleSyncResult) = RuleSyncOutcome(
        result = result,
        reparse = if (result is RuleSyncResult.Updated) smsRepository.reparseNeedsReview() else null,
    )
}
