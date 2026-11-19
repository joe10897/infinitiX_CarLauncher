package com.infiniti.m25launcher

import android.content.ComponentName
import android.content.Context
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
 * artist, play/pause state, and an optical playback progress bar on the dashboard.
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
        val progressRatio: Float = 0f
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentName = ComponentName(context, MediaNotificationService::class.java)
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var isStarted = false

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
    }

    private fun emitCurrentControllerState() {
        val controller = activeController
        if (controller == null) {
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

        onStateChanged(
            NowPlayingState(
                hasNotificationAccess = true,
                hasActiveSession = true,
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                progressRatio = computeProgressRatio(positionMs, durationMs)
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
    }
}
