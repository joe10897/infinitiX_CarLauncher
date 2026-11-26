/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Monitors active system MediaSessions (prioritizing Spotify) to drive live track title,
 * artist, album artwork, ambient color tinting, playback timecode, and optical progress bar.
 */
class MediaSessionMonitor(
    private val context: Context,
    private val onStateChanged: (NowPlayingState) -> Unit
) {

    data class NowPlayingState(
        val hasNotificationAccess: Boolean,
        val hasActiveSession: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val progressRatio: Float = 0f,
        val albumArt: Bitmap? = null,
        val albumAccentColor: Int? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentName = ComponentName(context, MediaNotificationService::class.java)
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var isStarted = false
    private var cachedArtKey: String? = null
    private var cachedArtBitmap: Bitmap? = null
    private var cachedAccentColor: Int? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            emitCurrentControllerState()
            if (isStarted && activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                mainHandler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            emitCurrentControllerState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            emitCurrentControllerState()
            rescheduleTickerIfPlaying(state)
        }

        override fun onSessionDestroyed() {
            detachController()
            refreshActiveSessions()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindPreferredController(controllers.orEmpty())
        }

    fun start() {
        if (isStarted) return
        isStarted = true

        if (!isNotificationAccessEnabled(context)) {
            onStateChanged(NowPlayingState(hasNotificationAccess = false))
            return
        }

        val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager = sm
        if (sm == null) {
            onStateChanged(NowPlayingState(hasNotificationAccess = false))
            return
        }

        try {
            sm.addOnActiveSessionsChangedListener(activeSessionsListener, componentName)
            refreshActiveSessions()
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted for MediaSessionManager", e)
            onStateChanged(NowPlayingState(hasNotificationAccess = false))
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        mainHandler.removeCallbacks(progressTicker)
        detachController()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {
            // Listener was not registered or permission changed.
        }
        sessionManager = null
    }

    private fun refreshActiveSessions() {
        val sm = sessionManager ?: return
        try {
            val controllers = sm.getActiveSessions(componentName)
            bindPreferredController(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to query active media sessions", e)
            onStateChanged(NowPlayingState(hasNotificationAccess = false))
        }
    }

    private fun bindPreferredController(controllers: List<MediaController>) {
        detachController()
        val chosen = controllers.firstOrNull {
            it.packageName == AppLauncher.PKG_SPOTIFY &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.packageName == AppLauncher.PKG_SPOTIFY
        } ?: controllers.firstOrNull()

        activeController = chosen
        if (chosen != null) {
            chosen.registerCallback(controllerCallback, mainHandler)
            emitCurrentControllerState()
            rescheduleTickerIfPlaying(chosen.playbackState)
        } else {
            onStateChanged(NowPlayingState(hasNotificationAccess = true, hasActiveSession = false))
        }
    }

    private fun detachController() {
        mainHandler.removeCallbacks(progressTicker)
        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
            // Ignore unregister race during session teardown.
        }
        activeController = null
        clearArtCache()
    }

    private fun clearArtCache() {
        cachedArtKey = null
        cachedArtBitmap = null
        cachedAccentColor = null
    }

    private fun refreshArtCacheIfNeeded(
        title: String?,
        artist: String?,
        metadata: MediaMetadata?
    ) {
        val rawArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artKey = "${title.orEmpty()}|${artist.orEmpty()}|${rawArt?.generationId ?: 0}"
        if (artKey == cachedArtKey) return

        cachedArtKey = artKey
        if (rawArt == null || rawArt.isRecycled || rawArt.width <= 0 || rawArt.height <= 0) {
            cachedArtBitmap = null
            cachedAccentColor = null
            return
        }

        try {
            val scaled = Bitmap.createScaledBitmap(rawArt, ART_PREVIEW_PX, ART_PREVIEW_PX, true)
            cachedArtBitmap = scaled
            val sample = Bitmap.createScaledBitmap(scaled, ART_SAMPLE_PX, ART_SAMPLE_PX, true)
            val pixels = IntArray(ART_SAMPLE_PX * ART_SAMPLE_PX)
            sample.getPixels(pixels, 0, ART_SAMPLE_PX, 0, 0, ART_SAMPLE_PX, ART_SAMPLE_PX)
            if (sample !== scaled) {
                sample.recycle()
            }
            cachedAccentColor = extractAmbientAccentRgb(pixels)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to process album artwork: ${e.message}")
            cachedArtBitmap = null
            cachedAccentColor = null
        }
    }

    private fun emitCurrentControllerState() {
        val controller = activeController
        if (controller == null) {
            clearArtCache()
            onStateChanged(NowPlayingState(hasNotificationAccess = true, hasActiveSession = false))
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
            ?.takeIf { it.isNotEmpty() }
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?.takeIf { it.isNotEmpty() }
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

        val positionMs = if (playbackState != null) {
            interpolatePositionMs(
                basePositionMs = playbackState.position,
                lastUpdateElapsedMs = playbackState.lastPositionUpdateTime,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                playbackSpeed = playbackState.playbackSpeed,
                isPlaying = isPlaying
            )
        } else {
            0L
        }

        refreshArtCacheIfNeeded(title, artist, metadata)

        onStateChanged(
            NowPlayingState(
                hasNotificationAccess = true,
                hasActiveSession = true,
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                progressRatio = computeProgressRatio(positionMs, durationMs),
                albumArt = cachedArtBitmap,
                albumAccentColor = cachedAccentColor
            )
        )
    }

    private fun rescheduleTickerIfPlaying(state: PlaybackState?) {
        mainHandler.removeCallbacks(progressTicker)
        if (isStarted && state?.state == PlaybackState.STATE_PLAYING) {
            mainHandler.postDelayed(progressTicker, PROGRESS_TICK_MS)
        }
    }

    companion object {
        private const val TAG = "Y51MediaMonitor"
        private const val PROGRESS_TICK_MS = 1000L
        private const val ART_PREVIEW_PX = 160
        private const val ART_SAMPLE_PX = 16

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, MediaNotificationService::class.java)
            val full = target.flattenToString()
            val short = target.flattenToShortString()
            return enabledListeners.split(':').any {
                it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
            }
        }

        fun interpolatePositionMs(
            basePositionMs: Long,
            lastUpdateElapsedMs: Long,
            nowElapsedMs: Long,
            playbackSpeed: Float,
            isPlaying: Boolean
        ): Long {
            val safeBase = basePositionMs.coerceAtLeast(0L)
            if (!isPlaying || lastUpdateElapsedMs <= 0L || playbackSpeed <= 0f) {
                return safeBase
            }
            val deltaMs = (nowElapsedMs - lastUpdateElapsedMs).coerceAtLeast(0L)
            return safeBase + (deltaMs * playbackSpeed).toLong()
        }

        fun computeProgressRatio(positionMs: Long, durationMs: Long): Float {
            if (durationMs <= 0L || positionMs <= 0L) return 0f
            return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

        fun formatTrackTimecode(positionMs: Long, durationMs: Long): String {
            if (durationMs <= 0L) return "--:-- / --:--"
            val clampedPos = positionMs.coerceIn(0L, durationMs)
            val posText = TripTelemetryTracker.formatDuration(clampedPos)
            val durText = TripTelemetryTracker.formatDuration(durationMs)
            return "$posText / $durText"
        }

        /**
         * Extracts a tasteful chroma-weighted ambient RGB tint (`0x00RRGGBB`) from sampled
         * ARGB album pixels so glass refractions subtly echo the current track without glaring.
         */
        fun extractAmbientAccentRgb(pixels: IntArray): Int? {
            if (pixels.isEmpty()) return null
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var totalWeight = 0.0

            for (pixel in pixels) {
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val maxCh = maxOf(r, g, b)
                val minCh = minOf(r, g, b)
                if (maxCh < 28 || minCh > 238) continue

                val chroma = maxCh - minCh
                val weight = 1.0 + (chroma / 255.0) * 4.0
                sumR += r * weight
                sumG += g * weight
                sumB += b * weight
                totalWeight += weight
            }

            if (totalWeight <= 0.0) return null
            var r = Math.round(sumR / totalWeight).toInt().coerceIn(0, 255)
            var g = Math.round(sumG / totalWeight).toInt().coerceIn(0, 255)
            var b = Math.round(sumB / totalWeight).toInt().coerceIn(0, 255)

            // Lift overly dark covers slightly so ambient glass caustic remains perceptible.
            val peak = maxOf(r, g, b)
            if (peak in 1..119) {
                val scale = 140f / peak.toFloat()
                r = Math.round(r * scale).coerceAtMost(255)
                g = Math.round(g * scale).coerceAtMost(255)
                b = Math.round(b * scale).coerceAtMost(255)
            }
            return (r shl 16) or (g shl 8) or b
        }
    }
}
