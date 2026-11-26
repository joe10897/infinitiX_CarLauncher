package com.infiniti.m25launcher

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Persists pairing code, enabled state, and handled message IDs for Remote Phone-to-Car Navigation.
 */
object RemoteNavStore {

    private const val PREFS_NAME = "y51_remote_nav_prefs"
    private const val KEY_ENABLED = "remote_nav_enabled"
    private const val KEY_PAIRING_CODE = "remote_nav_pairing_code"
    private const val KEY_HANDLED_IDS = "remote_nav_handled_ids"
    private const val MAX_HANDLED_HISTORY = 24

    // Excludes ambiguous characters (0/O, 1/I/L) for effortless one-time entry on the phone.
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val TOPIC_PREFIX = "infinitiy51-nav-"
    private const val NTFY_BASE_URL = "https://ntfy.sh"

    private val secureRandom = SecureRandom()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getOrCreatePairingCode(context: Context): String {
        val existing = prefs(context).getString(KEY_PAIRING_CODE, null)?.trim()
        if (!existing.isNullOrEmpty() && normalizeCode(existing).length >= 6) {
            return formatPairingCode(existing)
        }
        return regeneratePairingCode(context)
    }

    fun regeneratePairingCode(context: Context): String {
        val fresh = generatePairingCode()
        prefs(context).edit()
            .putString(KEY_PAIRING_CODE, fresh)
            .remove(KEY_HANDLED_IDS)
            .apply()
        return fresh
    }

    fun buildSseUrl(pairingCode: String): String =
        "$NTFY_BASE_URL/${topicForPairingCode(pairingCode)}/sse?since=3m"

    fun buildPollJsonUrl(pairingCode: String): String =
        "$NTFY_BASE_URL/${topicForPairingCode(pairingCode)}/json?poll=1&since=5m"

    fun topicForPairingCode(pairingCode: String): String =
        "$TOPIC_PREFIX${normalizeCode(pairingCode).lowercase()}"

    fun normalizeCode(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun formatPairingCode(raw: String): String {
        val clean = normalizeCode(raw)
        if (clean.startsWith("Y51") && clean.length == 11) {
            return "Y51-${clean.substring(3, 7)}-${clean.substring(7, 11)}"
        }
        return raw.trim().uppercase()
    }

    fun generatePairingCode(random: java.util.Random = secureRandom): String {
        val part1 = CharArray(4) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        val part2 = CharArray(4) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        return "Y51-${String(part1)}-${String(part2)}"
    }

    fun isMessageHandled(context: Context, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val saved = prefs(context).getString(KEY_HANDLED_IDS, null).orEmpty()
        return messageId in saved.split(',')
    }

    fun markMessageHandled(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        val current = prefs(context).getString(KEY_HANDLED_IDS, null).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != messageId }
            .toMutableList()
        current.add(0, messageId)
        val trimmed = current.take(MAX_HANDLED_HISTORY).joinToString(",")
        prefs(context).edit().putString(KEY_HANDLED_IDS, trimmed).apply()
    }
}
