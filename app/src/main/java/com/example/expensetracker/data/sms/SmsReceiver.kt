package com.example.expensetracker.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.expensetracker.ExpenseTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val smsRepository = (context.applicationContext as ExpenseTrackerApp).container.smsRepository
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                messages
                    .groupBy { it.originatingAddress.orEmpty() }
                    .forEach { (sender, parts) ->
                        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
                        // The network's own timestamp, not Clock.System.now(): it is identical
                        // across redeliveries of the same message, which is what lets the
                        // (sender, body, receivedAt) index recognise a duplicate broadcast.
                        val receivedAt = parts.first().timestampMillis
                            .takeIf { it > 0 }
                            ?.let(Instant::fromEpochMilliseconds)
                            ?: Clock.System.now()
                        smsRepository.ingest(sender, body, receivedAt)
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }
}