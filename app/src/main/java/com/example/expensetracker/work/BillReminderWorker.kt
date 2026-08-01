package com.example.expensetracker.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.expensetracker.ExpenseTrackerApp
import com.example.expensetracker.R
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val BILL_REMINDER_CHANNEL_ID = "bill_reminders"
const val BILL_REMINDER_WORK_NAME = "bill_reminder_check"

/**
 * Runs daily (subject to Doze-mode delay, which is fine for a once-a-day check) and notifies
 * for any bill due today that hasn't already been notified today.
 */
class BillReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val billRepository = (applicationContext as ExpenseTrackerApp).container.billRepository
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

        billRepository.getAll()
            .filter { it.dueDay == today.dayOfMonth }
            .filter { bill ->
                val lastNotifiedDate = bill.lastNotifiedAt
                    ?.toLocalDateTime(TimeZone.currentSystemDefault())?.date
                lastNotifiedDate != today
            }
            .forEach { bill ->
                notify(bill.id.toInt(), bill.name)
                billRepository.update(bill.copy(lastNotifiedAt = now))
            }

        return Result.success()
    }

    private fun notify(notificationId: Int, billName: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BILL_REMINDER_CHANNEL_ID,
                    "Bill reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(applicationContext, BILL_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bill due today")
            .setContentText("$billName is due today")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
    }
}