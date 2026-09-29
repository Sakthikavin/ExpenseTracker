package com.example.expensetracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.expensetracker.ExpenseTrackerApp
import com.example.expensetracker.data.remoterules.RuleSyncResult

const val REMOTE_RULE_SYNC_WORK_NAME = "remote_rule_sync"

/**
 * Daily background pull of the published rule set (REQUIREMENTS.md §8.1). Unmetered network is
 * not required — the `rules/current` document is tiny.
 */
class RemoteRuleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val coordinator = (applicationContext as ExpenseTrackerApp).container.ruleSyncCoordinator
        // Retry (WorkManager backoff) only on an actual network/parse failure — a fetch that
        // succeeded but found nothing new is still a successful run.
        return when (coordinator.sync().result) {
            RuleSyncResult.Failed -> Result.retry()
            is RuleSyncResult.UpToDate, is RuleSyncResult.Updated -> Result.success()
        }
    }
}
