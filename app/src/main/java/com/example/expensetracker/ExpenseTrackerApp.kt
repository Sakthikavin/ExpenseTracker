package com.example.expensetracker

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.expensetracker.di.AppContainer
import com.example.expensetracker.work.BILL_REMINDER_WORK_NAME
import com.example.expensetracker.work.BillReminderWorker
import com.example.expensetracker.work.REMOTE_RULE_SYNC_WORK_NAME
import com.example.expensetracker.work.RemoteRuleSyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExpenseTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleBillReminders()
        scheduleRemoteRuleSync()
        // On-launch check, capped to once per 24h inside the repository itself (§8.1).
        applicationScope.launch { container.ruleSyncCoordinator.syncIfDue() }
        repairSmsDatesOnce()
    }

    /**
     * Transactions ingested before [com.example.expensetracker.data.sms.SmsDateParser.plausibleOccurredAt]
     * existed can carry a date read out of the wrong part of the message — a December message filed
     * a year ahead, say. Runs once per install; the flag is what stops it re-scanning every launch.
     */
    private fun repairSmsDatesOnce() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SMS_DATE_REPAIR_DONE, false)) return
        applicationScope.launch {
            container.smsRepository.repairDatesAheadOfTheirSms()
            prefs.edit().putBoolean(PREF_SMS_DATE_REPAIR_DONE, true).apply()
        }
    }

    private fun scheduleBillReminders() {
        val request = PeriodicWorkRequestBuilder<BillReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BILL_REMINDER_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleRemoteRuleSync() {
        val request = PeriodicWorkRequestBuilder<RemoteRuleSyncWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            REMOTE_RULE_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val PREF_SMS_DATE_REPAIR_DONE = "sms_date_repair_done"
    }
}