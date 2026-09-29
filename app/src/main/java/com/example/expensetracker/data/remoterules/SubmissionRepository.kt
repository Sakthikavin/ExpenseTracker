package com.example.expensetracker.data.remoterules

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.json.JSONObject

private const val TAG = "SubmissionRepository"

enum class SubmissionAction(val wire: String) { REVIEW("review"), DISCARD("discard") }

sealed interface SubmissionResult {
    data object Sent : SubmissionResult

    /** Redaction left something identifying behind, so nothing was uploaded (REQUIREMENTS §6.1.1). */
    data class Blocked(val hints: List<String>) : SubmissionResult

    data object Failed : SubmissionResult
}

/**
 * Uploads a redacted message template to `/submissions` so the console can author a rule for it
 * (REQUIREMENTS §6.3). `firestore.rules` allows an unauthenticated create as long as the payload
 * matches that exact shape, so this is a plain REST POST — no Firebase SDK, no auth.
 *
 * Submissions carry no device or user identifier, by design.
 */
class SubmissionRepository(
    private val projectId: String,
    private val baseUrl: String,
    private val appVersion: String,
    private val rulesVersion: () -> Int?,
) {
    suspend fun submit(
        sender: String,
        body: String,
        action: SubmissionAction,
        transactionType: String? = null,
        note: String? = null,
    ): SubmissionResult {
        val template = Redactor.redact(body)
        val hints = Redactor.unredactedHints(template)
        if (hints.isNotEmpty()) return SubmissionResult.Blocked(hints)

        val fields = JSONObject().apply {
            put("template", stringValue(template.take(2000)))
            put("sender", stringValue(sender.take(40)))
            put("action", stringValue(action.wire))
            put("submittedAt", stringValue(Clock.System.now().toString()))
            put("appVersion", stringValue(appVersion.take(30)))
            transactionType?.takeIf { it.isNotBlank() }?.let { put("transactionType", stringValue(it.take(30))) }
            note?.takeIf { it.isNotBlank() }?.let { put("note", stringValue(it.take(200))) }
            rulesVersion()?.let { put("rulesVersion", JSONObject().put("integerValue", it.toString())) }
        }
        return post(JSONObject().put("fields", fields))
    }

    private suspend fun post(document: JSONObject): SubmissionResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$baseUrl/v1/projects/$projectId/databases/(default)/documents/submissions")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(document.toString().toByteArray()) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "submit: HTTP ${connection.responseCode} ${error.take(300)}")
                    return@withContext SubmissionResult.Failed
                }
                SubmissionResult.Sent
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            Log.w(TAG, "submit failed: ${it.javaClass.name}: ${it.message}")
        }.getOrDefault(SubmissionResult.Failed)
    }
}

private fun stringValue(value: String) = JSONObject().put("stringValue", value)
