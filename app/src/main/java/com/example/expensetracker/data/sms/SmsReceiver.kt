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

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val smsRepository = (context.applicationContext as ExpenseTrackerApp).container.smsRepository
        val receivedAt = Clock.System.now()
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                messages
                    .groupBy { it.originatingAddress.orEmpty() }
                    .forEach { (sender, parts) ->
                        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
                        smsRepository.ingest(sender, body, receivedAt)
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }
}