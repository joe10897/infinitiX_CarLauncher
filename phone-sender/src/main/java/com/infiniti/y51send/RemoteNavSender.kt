/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.y51send

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Stateless HTTPS one-shot sender used by Y51 Send to push shared navigation destinations
 * to the paired Infiniti Y51 DriveOS head unit without any background services.
 */
object RemoteNavSender {

    private const val PREFS_NAME = "y51_send_prefs"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_LAST_SENT = "last_sent"
    private const val TOPIC_PREFIX = "infinitiy51-nav-"
    private const val NTFY_BASE_URL = "https://ntfy.sh"
    private const val TIMEOUT_MS = 8000

    sealed class SendResult {
        object Success : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPairingCode(context: Context): String? =
        prefs(context).getString(KEY_PAIRING_CODE, null)?.trim()?.takeIf { isValidPairingCode(it) }

    fun savePairingCode(context: Context, rawCode: String): String? {
        if (!isValidPairingCode(rawCode)) return null
        val formatted = formatPairingCode(rawCode)
        prefs(context).edit().putString(KEY_PAIRING_CODE, formatted).apply()
        return formatted
    }

    fun getLastSent(context: Context): String? =
        prefs(context).getString(KEY_LAST_SENT, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveLastSent(context: Context, destination: String) {
        prefs(context).edit().putString(KEY_LAST_SENT, destination.trim().take(120)).apply()
    }

    fun normalizeCode(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidPairingCode(raw: String): Boolean =
        normalizeCode(raw).length >= 6

    fun formatPairingCode(raw: String): String {
        val clean = normalizeCode(raw)
        if (clean.startsWith("Y51") && clean.length == 11) {
            return "Y51-${clean.substring(3, 7)}-${clean.substring(7, 11)}"
        }
        return raw.trim().uppercase()
    }

    fun topicForPairingCode(pairingCode: String): String =
        "$TOPIC_PREFIX${normalizeCode(pairingCode).lowercase()}"

    fun buildPostUrl(pairingCode: String): String =
        "$NTFY_BASE_URL/${topicForPairingCode(pairingCode)}"

    fun buildPayloadJson(rawDestination: String, nowMs: Long = System.currentTimeMillis()): String {
        return JSONObject()
            .put("v", 1)
            .put("raw", rawDestination.trim())
            .put("ts", nowMs)
            .toString()
    }

    /**
     * Extracts shared destination text or `geo:` URI from incoming Android Share / View intents.
     */
    fun extractSharedPayload(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                when {
                    text.isNotEmpty() && subject.isNotEmpty() && !text.contains(subject) ->
                        "$subject $text"
                    text.isNotEmpty() -> text
                    subject.isNotEmpty() -> subject
                    else -> null
                }
            }
            Intent.ACTION_VIEW -> intent.dataString?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * Performs a single blocking HTTPS POST to the vehicle's private topic. Call off the main thread.
     */
    fun sendBlocking(pairingCode: String, rawDestination: String): SendResult {
        val cleaned = rawDestination.trim()
        if (!isValidPairingCode(pairingCode)) {
            return SendResult.Failure("Invalid pairing code")
        }
        if (cleaned.isEmpty()) {
            return SendResult.Failure("Empty destination")
        }

        val url = buildPostUrl(pairingCode)
        val bodyBytes = buildPayloadJson(cleaned).toByteArray(StandardCharsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("Title", "Y51 Nav")
                setFixedLengthStreamingMode(bodyBytes.size)
            }
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                SendResult.Success
            } else {
                SendResult.Failure("HTTP $code")
            }
        } catch (e: Exception) {
            SendResult.Failure(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
