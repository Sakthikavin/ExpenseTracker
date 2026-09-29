package com.example.expensetracker.data.remoterules

import android.util.Log
import com.example.expensetracker.data.local.entity.Direction
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "RemoteRulesApi"

/**
 * Reads the published rule set straight from Firestore's REST API — no Firebase SDK, no
 * `google-services.json` to provision. `/rules/current` is public-read (see the console repo's
 * `firestore.rules`), so a plain unauthenticated GET is all this needs.
 */
class RemoteRulesApi(
    private val projectId: String = "expense-tracker-rules-console",
    /** Override for local dev — point at the Firestore emulator's REST endpoint (see the console
     *  repo's README, "Local development against emulators"). Defaults to production Firestore. */
    private val baseUrl: String = "https://firestore.googleapis.com",
) {
    /** Null on any failure (offline, no doc published yet, malformed response) — never throws. */
    suspend fun fetchCurrent(): RemoteRuleSet? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "$baseUrl/v1/projects/$projectId/databases/(default)/documents/rules/current",
            )
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "fetchCurrent: HTTP ${connection.responseCode} from $url")
                    return@withContext null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseDocument(JSONObject(body).getJSONObject("fields"))
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "fetchCurrent failed: ${it.javaClass.name}: ${it.message}") }.getOrNull()
    }

    private fun parseDocument(fields: JSONObject): RemoteRuleSet = RemoteRuleSet(
        version = fields.fsInt("version") ?: 0,
        updatedAt = fields.fsString("updatedAt").orEmpty(),
        discardSenders = fields.fsStringArray("discardSenders"),
        rules = fields.fsArray("rules").orEmpty()
            .mapNotNull { it.optJSONObject("mapValue")?.optJSONObject("fields")?.let(::parseRule) },
    )

    private fun parseRule(fields: JSONObject): RemoteRule? {
        val pattern = fields.fsString("pattern") ?: return null
        val direction = if (fields.fsString("direction") == "credit") Direction.CREDIT else Direction.DEBIT
        return RemoteRule(
            id = fields.fsString("id").orEmpty(),
            senders = fields.fsStringArray("senders"),
            direction = direction,
            pattern = pattern,
            fieldMap = fields.fsMap("fieldMap")?.let { map ->
                map.keys().asSequence().mapNotNull { key -> map.optJSONObject(key)?.asFsInt()?.let { key to it } }.toMap()
            }.orEmpty(),
            priority = fields.fsInt("priority") ?: 0,
        )
    }
}

// Firestore REST documents wrap every value as `{ "<type>Value": ... }`; these unwrap that shape.

private fun JSONObject.asFsInt(): Int? = if (has("integerValue")) getString("integerValue").toIntOrNull() else null

private fun JSONObject.fsString(key: String): String? {
    val value = optJSONObject(key) ?: return null
    return if (value.has("stringValue")) value.getString("stringValue") else null
}

private fun JSONObject.fsInt(key: String): Int? = optJSONObject(key)?.asFsInt()

private fun JSONObject.fsArray(key: String): List<JSONObject>? {
    val value = optJSONObject(key) ?: return null
    val values = value.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptyList()
    return (0 until values.length()).map { values.getJSONObject(it) }
}

private fun JSONObject.fsStringArray(key: String): List<String> =
    fsArray(key)?.mapNotNull { entry -> entry.optString("stringValue").takeIf { it.isNotEmpty() } }.orEmpty()

private fun JSONObject.fsMap(key: String): JSONObject? {
    val value = optJSONObject(key) ?: return null
    return value.optJSONObject("mapValue")?.optJSONObject("fields")
}
