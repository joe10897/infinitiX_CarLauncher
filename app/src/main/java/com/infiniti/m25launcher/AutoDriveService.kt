package com.infiniti.m25launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Zero-click automotive automation service.
 * Listens for vehicle wake events (ACTION_SCREEN_ON / ACTION_USER_PRESENT) with a 5-minute
 * monotonic cooldown, intercepts steering-wheel voice assist keys, and orchestrates
 * Speedcam, Maps + Spotify split-screen, and media playback.
 */
class AutoDriveService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerElapsedMs: Long? = null
    private var isReceiverRegistered = false

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (!shouldTriggerWithCooldown(now, lastTriggerElapsedMs, COOLDOWN_MS)) {
                Log.d(TAG, "Ignored wake event ($action): within 5-minute cooldown")
                return
            }

            lastTriggerElapsedMs = now
            Log.i(TAG, "Wake trigger accepted ($action); scheduling Auto-Drive routine")
            scheduleFullAutoDriveRoutine()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerWakeReceiverIfNeeded()
        Log.i(TAG, "AutoDriveService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Global split-screen actions and wake broadcasts do not require reading window contents.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOICE_ASSIST && keyCode != KeyEvent.KEYCODE_ASSIST) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Steering wheel voice key intercepted (keyCode=$keyCode)")
            VoiceTriggerActivity.launch(this)
        }
        return true
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    /**
     * Executes the complete Auto-Drive automation sequence immediately (bypasses wake cooldown).
     */
    fun triggerFullRoutineNow() {
        Log.i(TAG, "Manual full Auto-Drive routine triggered")
        scheduleFullAutoDriveRoutine()
    }

    /**
     * Launches Google Maps and Spotify side-by-side in split-screen mode.
     */
    fun triggerSplitNavigationNow() {
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Manual Split Navigation sequence triggered")
        scheduleMapsSpotifySplit(baseDelayMs = 0L, showMissingToast = true)
    }

    /**
     * Launches turn-by-turn Google Maps navigation to [destination] and places Spotify
     * side-by-side if the head unit is not already in split-screen mode.
     */
    fun triggerNavigationWithSplit(destination: String) {
        mainHandler.removeCallbacksAndMessages(null)
        val alreadySplit = isCurrentlyInSplitScreen()
        Log.i(TAG, "Voice navigation to '$destination' (alreadySplit=$alreadySplit)")

        VoiceCommandRouter.launchGoogleMapsNavigation(this, destination)
        if (!alreadySplit) {
            mainHandler.postDelayed({
                val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN result=$toggled")
            }, DELAY_SPLIT_TOGGLE_AFTER_VOICE_NAV_MS)

            mainHandler.postDelayed({
                AppLauncher.launchPackage(
                    context = this,
                    primaryPackage = AppLauncher.PKG_SPOTIFY,
                    extraFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
                    newTask = true,
                    showMissingToast = false
                )
            }, DELAY_SPLIT_TOGGLE_AFTER_VOICE_NAV_MS + DELAY_SPOTIFY_AFTER_SPLIT_MS)
        }
    }

    private fun isCurrentlyInSplitScreen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleFullAutoDriveRoutine() {
        mainHandler.removeCallbacksAndMessages(null)

        // 0.5s: Launch Aegis Speedcam
        mainHandler.postDelayed({
            AppLauncher.launchPackage(
                context = this,
                primaryPackage = AppLauncher.PKG_SPEEDCAM,
                newTask = true,
                showMissingToast = false
            )
        }, DELAY_SPEEDCAM_MS)

        // 2.5s: Launch Google Maps -> +1.2s toggle split screen -> +0.7s launch Spotify adjacent
        scheduleMapsSpotifySplit(baseDelayMs = DELAY_MAPS_MS, showMissingToast = false)

        // 5.5s: Resume media playback via KEYCODE_MEDIA_PLAY
        mainHandler.postDelayed({
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY)
            Log.d(TAG, "Dispatched KEYCODE_MEDIA_PLAY to resume audio")
        }, DELAY_MEDIA_PLAY_MS)
    }

    private fun scheduleMapsSpotifySplit(baseDelayMs: Long, showMissingToast: Boolean) {
        mainHandler.postDelayed({
            AppLauncher.launchPackage(
                context = this,
                primaryPackage = AppLauncher.PKG_MAPS,
                newTask = true,
                showMissingToast = showMissingToast
            )
        }, baseDelayMs)

        val splitToggleAt = baseDelayMs + DELAY_SPLIT_TOGGLE_AFTER_MAPS_MS
        mainHandler.postDelayed({
            if (!isCurrentlyInSplitScreen()) {
                val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN result=$toggled")
            }
        }, splitToggleAt)

        val spotifyLaunchAt = splitToggleAt + DELAY_SPOTIFY_AFTER_SPLIT_MS
        mainHandler.postDelayed({
            AppLauncher.launchPackage(
                context = this,
                primaryPackage = AppLauncher.PKG_SPOTIFY,
                extraFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
                newTask = true,
                showMissingToast = showMissingToast
            )
        }, spotifyLaunchAt)
    }

    private fun registerWakeReceiverIfNeeded() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenWakeReceiver, filter)
        isReceiverRegistered = true
    }

    private fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(screenWakeReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was already unregistered.
            }
            isReceiverRegistered = false
        }
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "AutoDriveService"

        const val COOLDOWN_MS = 5 * 60 * 1000L
        const val DELAY_SPEEDCAM_MS = 500L
        const val DELAY_MAPS_MS = 2500L
        const val DELAY_SPLIT_TOGGLE_AFTER_MAPS_MS = 1200L
        const val DELAY_SPLIT_TOGGLE_AFTER_VOICE_NAV_MS = 1400L
        const val DELAY_SPOTIFY_AFTER_SPLIT_MS = 700L
        const val DELAY_MEDIA_PLAY_MS = 5500L

        @Volatile
        var instance: AutoDriveService? = null
            private set

        /**
         * Determines whether a wake event at [nowElapsedMs] should trigger automation
         * given the previous trigger timestamp [lastTriggerElapsedMs] and [cooldownMs].
         */
        fun shouldTriggerWithCooldown(
            nowElapsedMs: Long,
            lastTriggerElapsedMs: Long?,
            cooldownMs: Long = COOLDOWN_MS
        ): Boolean {
            if (lastTriggerElapsedMs == null) return true
            return (nowElapsedMs - lastTriggerElapsedMs) >= cooldownMs
        }
    }
}
