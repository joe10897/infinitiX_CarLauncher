/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses incoming SSE payloads, resolves shortened Google Maps links (`maps.app.goo.gl`),
 * and extracts pinpoint navigation targets (`lat,lng` or place query) plus a display label.
 */
object RemoteNavParser {

    data class SseIncomingMessage(
        val id: String,
        val timeEpochSec: Long,
        val rawPayload: String
    )

    data class ParsedDestination(
        val navigationQuery: String,
        val displayLabel: String
    )

    private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val PIN_3D4D_REGEX = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val AT_COORD_REGEX = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val GEO_COORD_REGEX = Regex("""^geo:(-?\d+\.\d+),(-?\d+\.\d+)""", RegexOption.IGNORE_CASE)
    private val COORD_PAIR_REGEX = Regex("""^\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*$""")
    private val PLACE_PATH_REGEX = Regex("""/maps/place/([^/@?]+)""")

    private const val RESOLVE_TIMEOUT_MS = 4000
    private const val MAX_REDIRECTS = 4

    /**
     * Parses either an SSE `data: {...}` line or a raw NDJSON `{...}` line from ntfy.sh
     * into an [SseIncomingMessage]. Returns null for keepalive/open frames or malformed JSON.
     */
    fun parseSseEventLine(line: String): SseIncomingMessage? {
        val jsonStr = extractEventJson(line) ?: return null

        return try {
            val obj = JSONObject(jsonStr)
            if (obj.optString("event") != "message") return null
            val id = obj.optString("id").trim()
            val timeSec = obj.optLong("time", 0L)
            val messageBody = obj.optString("message").trim()
            if (id.isEmpty() || messageBody.isEmpty()) return null

            val rawPayload = unwrapAppPayload(messageBody)
            if (rawPayload.isBlank()) return null
            SseIncomingMessage(id = id, timeEpochSec = timeSec, rawPayload = rawPayload)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the ntfy.sh server UTC timestamp from an `open` or `keepalive` SSE frame so
     * message freshness never depends on the car head unit's local clock accuracy.
     */
    fun parseServerTimeFromControlFrame(line: String): Long? {
        val jsonStr = extractEventJson(line) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            val event = obj.optString("event")
            if (event == "open" || event == "keepalive") {
                obj.optLong("time", 0L).takeIf { it > 0L }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEventJson(line: String): String? {
        val trimmed = line.trim()
        val jsonStr = if (trimmed.startsWith("data:")) {
            trimmed.removePrefix("data:").trim()
        } else {
            trimmed
        }
        return jsonStr.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    /**
     * Supports both structured Y51 JSON (`{"v":1,"raw":"..."}`) and raw plain-text/URL messages.
     */
    fun unwrapAppPayload(messageBody: String): String {
        val trimmed = messageBody.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val inner = JSONObject(trimmed)
                val raw = inner.optString("raw").trim()
                if (raw.isNotEmpty()) return raw
            } catch (_: Exception) {
                // Fall back to plain text body.
            }
        }
        return trimmed
    }

    fun isFreshMessage(eventTimeEpochSec: Long, nowEpochSec: Long, maxAgeSec: Long = 180L): Boolean {
        if (eventTimeEpochSec <= 0L) return true
        val age = nowEpochSec - eventTimeEpochSec
        return age <= maxAgeSec
    }

    fun extractFirstUrl(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', ';', ')', ']', '}')

    fun isShortMapUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://maps.app.goo.gl/") ||
            lower.startsWith("http://maps.app.goo.gl/") ||
            lower.startsWith("https://goo.gl/maps/") ||
            lower.startsWith("http://goo.gl/maps/") ||
            lower.startsWith("https://g.co/")
    }

    /**
     * Follows HTTP 3xx redirects on shortened Google Maps URLs to uncover pinpoint coordinates.
     * Always returns a usable URL (falling back to [shortUrl] on timeout or network error).
     */
    fun resolveShortUrl(shortUrl: String): String {
        if (!isShortMapUrl(shortUrl)) return shortUrl
        var current = shortUrl
        for (hop in 0 until MAX_REDIRECTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = RESOLVE_TIMEOUT_MS
                    readTimeout = RESOLVE_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (location.isNullOrEmpty()) break
                    current = URL(URL(current), location).toString()
                    if (!isShortMapUrl(current) &&
                        (PIN_3D4D_REGEX.containsMatchIn(current) || AT_COORD_REGEX.containsMatchIn(current))
                    ) {
                        break
                    }
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            } finally {
                conn?.disconnect()
            }
        }
        return current
    }

    /**
     * Extracts the most accurate Google Maps navigation query and readable label from shared text
     * plus an optional redirect-resolved URL.
     */
    fun extractNavigationTarget(
        rawText: String,
        resolvedUrl: String? = null
    ): ParsedDestination? {
        val cleanedText = rawText.trim()
        if (cleanedText.isEmpty()) return null

        val originalUrl = extractFirstUrl(cleanedText)
        val effectiveUrl = resolvedUrl?.takeIf { it.isNotBlank() } ?: originalUrl
        val noteText = stripUrlAndNoise(cleanedText, originalUrl)

        // 1. Direct geo: URI (e.g. geo:25.0339,121.5644?q=Taipei+101)
        val geoMatch = GEO_COORD_REGEX.find(cleanedText)
        if (geoMatch != null) {
            val lat = geoMatch.groupValues[1]
            val lng = geoMatch.groupValues[2]
            val queryParam = extractQueryParam(cleanedText, "q")
            val hasNonZero = lat.toDoubleOrNull() != 0.0 || lng.toDoubleOrNull() != 0.0
            val navQuery = if (hasNonZero) "$lat,$lng" else (queryParam ?: cleanedText)
            val label = queryParam?.takeIf { !COORD_PAIR_REGEX.matches(it) } ?: navQuery
            return ParsedDestination(navigationQuery = navQuery, displayLabel = label)
        }

        // 2. URL-based maps link (Google Maps / Apple Maps)
        if (effectiveUrl != null) {
            val placeName = extractPlaceSegment(effectiveUrl)
            val qParam = extractQueryParam(effectiveUrl, "q")
                ?: extractQueryParam(effectiveUrl, "query")
                ?: extractQueryParam(effectiveUrl, "daddr")
            val llParam = extractQueryParam(effectiveUrl, "ll")
            val addressParam = extractQueryParam(effectiveUrl, "address")

            val pin3d4d = PIN_3D4D_REGEX.find(effectiveUrl)
            val atCoord = AT_COORD_REGEX.find(effectiveUrl)

            val coordQuery = when {
                pin3d4d != null -> "${pin3d4d.groupValues[1]},${pin3d4d.groupValues[2]}"
                llParam != null && COORD_PAIR_REGEX.matches(llParam) ->
                    normalizeCoordPair(llParam)
                qParam != null && COORD_PAIR_REGEX.matches(qParam) ->
                    normalizeCoordPair(qParam)
                atCoord != null -> "${atCoord.groupValues[1]},${atCoord.groupValues[2]}"
                else -> null
            }

            val label = noteText.takeIf { it.isNotEmpty() }
                ?: placeName?.takeIf { it.isNotEmpty() }
                ?: qParam?.takeIf { it.isNotEmpty() && !COORD_PAIR_REGEX.matches(it) }
                ?: addressParam?.takeIf { it.isNotEmpty() }
                ?: coordQuery
                ?: effectiveUrl

            val navQuery = coordQuery
                ?: qParam?.takeIf { it.isNotEmpty() }
                ?: addressParam?.takeIf { it.isNotEmpty() }
                ?: placeName?.takeIf { it.isNotEmpty() }
                ?: noteText.takeIf { it.isNotEmpty() }
                ?: return null

            return ParsedDestination(navigationQuery = navQuery, displayLabel = label)
        }

        // 3. Pure text address / place name (e.g. shared from LINE or typed manually)
        val coordMatch = COORD_PAIR_REGEX.matchEntire(cleanedText)
        if (coordMatch != null) {
            val normalized = "${coordMatch.groupValues[1]},${coordMatch.groupValues[2]}"
            return ParsedDestination(navigationQuery = normalized, displayLabel = normalized)
        }

        return ParsedDestination(navigationQuery = cleanedText, displayLabel = cleanedText)
    }

    private fun stripUrlAndNoise(rawText: String, url: String?): String {
        var text = if (url != null) rawText.replace(url, " ") else rawText
        text = text.replace(Regex("""\s+"""), " ").trim()
        // Strip common trailing punctuation left after removing a shared link
        return text.trimEnd('-', ':', '：', '|', '·', ' ')
    }

    private fun extractPlaceSegment(url: String): String? {
        val match = PLACE_PATH_REGEX.find(url) ?: return null
        val decoded = safeUrlDecode(match.groupValues[1]).replace('+', ' ').trim()
        return decoded.takeIf { it.isNotEmpty() }
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val qIndex = url.indexOf('?')
        if (qIndex < 0 || qIndex >= url.length - 1) return null
        val queryPart = url.substring(qIndex + 1).substringBefore('#')
        for (pair in queryPart.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val k = pair.substring(0, eqIdx)
            if (k.equals(key, ignoreCase = true)) {
                val v = safeUrlDecode(pair.substring(eqIdx + 1)).trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    private fun normalizeCoordPair(raw: String): String {
        val m = COORD_PAIR_REGEX.matchEntire(raw) ?: return raw.trim()
        return "${m.groupValues[1]},${m.groupValues[2]}"
    }

    private fun safeUrlDecode(encoded: String): String = try {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        encoded
    }
}
