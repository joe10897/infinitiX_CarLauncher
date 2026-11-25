package com.infiniti.m25launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Zero-click automotive automation service.
 * Listens for vehicle wake events (ACTION_SCREEN_ON / ACTION_USER_PRESENT) with a 5-minute
 * monotonic cooldown, intercepts steering-wheel voice assist keys, and orchestrates
 * Aegis Speedcam, Google Maps (left) + Spotify (right) split-screen, and media playback.
 */
class AutoDriveService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerElapsedMs: Long? = null
    private var isReceiverRegistered = false
    private var swcLearnCallback: ((Int) -> Unit)? = null
    private var swcLearnTimeoutRunnable: Runnable? = null
    private var screenOffCleanupRunnable: Runnable? = null
    private var remoteNavClient: RemoteNavClient? = null

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_OFF) {
                handleAccScreenOff()
                return
            }
            if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) {
                return
            }

            screenOffCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
            screenOffCleanupRunnable = null
            syncRemoteNavClient(forceRestart = false)

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
        syncRemoteNavClient(forceRestart = true)
        Log.i(TAG, "AutoDriveService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window trees are inspected on-demand during split-screen docking.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val learnCallback = swcLearnCallback
        if (learnCallback != null) {
            if (SwcKeyStore.isProtectedSystemKey(keyCode)) {
                return false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cancelSwcLearnMode()
                SwcKeyStore.saveLearnedKeyCode(this, keyCode)
                Log.i(TAG, "Captured SWC voice keyCode=$keyCode")
                learnCallback.invoke(keyCode)
            }
            return true
        }

        val learnedKeyCode = SwcKeyStore.getLearnedKeyCode(this)
        if (!SwcKeyStore.isVoiceTriggerKey(keyCode, learnedKeyCode)) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Steering wheel voice key intercepted (keyCode=$keyCode)")
            VoiceTriggerActivity.launch(this)
        }
        return true
    }

    /**
     * Enters one-shot key capture mode so the next non-system steering-wheel button press is
     * stored as the voice shortcut key.
     */
    fun startSwcLearnMode(
        timeoutMs: Long = SWC_LEARN_TIMEOUT_MS,
        onCaptured: (Int) -> Unit,
        onTimeout: () -> Unit
    ) {
        cancelSwcLearnMode()
        swcLearnCallback = onCaptured
        val timeoutRunnable = Runnable {
            if (swcLearnCallback != null) {
                cancelSwcLearnMode()
                onTimeout()
            }
        }
        swcLearnTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    fun cancelSwcLearnMode() {
        swcLearnTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        swcLearnTimeoutRunnable = null
        swcLearnCallback = null
    }

    private fun handleAccScreenOff() {
        Log.i(TAG, "ACC/screen-off detected; pausing media and releasing background apps")
        cancelSwcLearnMode()
        remoteNavClient?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        AppLauncher.setSplitImmersiveMode(this, false)
        AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PAUSE)

        // Return to the lightweight launcher so active map/media UIs move to background first.
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to return to MainActivity on screen off: ${e.message}")
        }

        val cleanupTask = Runnable {
            screenOffCleanupRunnable = null
            AppLauncher.cleanupBackgroundAppsOnScreenOff(this)
        }
        screenOffCleanupRunnable = cleanupTask
        mainHandler.postDelayed(cleanupTask, DELAY_SCREEN_OFF_CLEANUP_MS)
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
     * Launches Google Maps (left) and Spotify (right) side-by-side in split-screen mode.
     */
    fun triggerSplitNavigationNow() {
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Manual Split Navigation sequence triggered")
        scheduleMapsSpotifySplit(baseDelayMs = 0L, showMissingToast = true)
    }

    /**
     * Launches turn-by-turn Google Maps navigation to [destination] and ensures Spotify
     * is docked in the right pane without exiting an already active split-screen session.
     */
    fun triggerNavigationWithSplit(destination: String) {
        mainHandler.removeCallbacksAndMessages(null)
        val alreadySplit = isCurrentlyInSplitScreen()
        Log.i(TAG, "Voice navigation to '$destination' (alreadySplit=$alreadySplit)")

        if (alreadySplit) {
            VoiceCommandRouter.launchGoogleMapsNavigation(this, destination)
            return
        }

        // Pre-warm Spotify so it becomes the #1 Recents card on FYT SystemUI
        AppLauncher.launchPackage(
            context = this,
            primaryPackage = AppLauncher.PKG_SPOTIFY,
            newTask = true,
            showMissingToast = false
        )

        mainHandler.postDelayed({
            VoiceCommandRouter.launchGoogleMapsNavigation(this, destination)
        }, DELAY_MAPS_AFTER_PREWARM_MS)

        val splitToggleAt = DELAY_MAPS_AFTER_PREWARM_MS + DELAY_SPLIT_TOGGLE_AFTER_VOICE_NAV_MS
        mainHandler.postDelayed({
            AppLauncher.setSplitImmersiveMode(this, true)
            AppLauncher.collapseSystemDialogs(this)
            if (!isCurrentlyInSplitScreen()) {
                val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN result=$toggled")
            }
        }, splitToggleAt)

        scheduleRightPaneSpotifyDock(
            startAtMs = splitToggleAt + DELAY_SPOTIFY_AFTER_SPLIT_MS,
            showMissingToast = false
        )
    }

    private fun scheduleFullAutoDriveRoutine() {
        mainHandler.removeCallbacksAndMessages(null)

        // 0.5s: Launch Aegis Speedcam (tw.com.ainvest.outpack + fallback)
        mainHandler.postDelayed({
            AppLauncher.launchSpeedcam(this, showMissingToast = false)
        }, DELAY_SPEEDCAM_MS)

        // 2.2s: Pre-warm Spotify -> +0.9s Google Maps -> +1.2s split toggle -> dock Spotify right
        val splitEndAt = scheduleMapsSpotifySplit(
            baseDelayMs = DELAY_ROUTINE_SPLIT_START_MS,
            showMissingToast = false
        )

        // Resume media playback after Spotify is docked on the right pane
        mainHandler.postDelayed({
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY)
            Log.d(TAG, "Dispatched KEYCODE_MEDIA_PLAY to resume audio")
        }, splitEndAt + DELAY_MEDIA_PLAY_AFTER_DOCK_MS)
    }

    /**
     * Pre-warms Spotify so FYT's right-half Recents puts Spotify first, then opens Maps,
     * toggles split-screen, and docks Spotify cleanly into the right pane.
     * Returns the scheduled timestamp (relative to now) when right-pane docking finishes.
     */
    private fun scheduleMapsSpotifySplit(baseDelayMs: Long, showMissingToast: Boolean): Long {
        // Step 1: Pre-warm Spotify into top of Recents
        mainHandler.postDelayed({
            AppLauncher.launchPackage(
                context = this,
                primaryPackage = AppLauncher.PKG_SPOTIFY,
                newTask = true,
                showMissingToast = false
            )
        }, baseDelayMs)

        // Step 2: Open Google Maps as primary foreground task
        val mapsAt = baseDelayMs + DELAY_MAPS_AFTER_PREWARM_MS
        mainHandler.postDelayed({
            AppLauncher.launchPackage(
                context = this,
                primaryPackage = AppLauncher.PKG_MAPS,
                newTask = true,
                showMissingToast = showMissingToast
            )
        }, mapsAt)

        // Step 3: Enter split-screen (Maps docks left; FYT Recents with Spotify #1 opens right)
        val splitToggleAt = mapsAt + DELAY_SPLIT_TOGGLE_AFTER_MAPS_MS
        mainHandler.postDelayed({
            AppLauncher.setSplitImmersiveMode(this, true)
            AppLauncher.collapseSystemDialogs(this)
            if (!isCurrentlyInSplitScreen()) {
                val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN result=$toggled")
            }
        }, splitToggleAt)

        // Step 4: Dock Spotify into right pane via Recents click or adjacent+multiple_task fallback
        val dockAt = splitToggleAt + DELAY_SPOTIFY_AFTER_SPLIT_MS
        return scheduleRightPaneSpotifyDock(dockAt, showMissingToast)
    }

    private fun scheduleRightPaneSpotifyDock(startAtMs: Long, showMissingToast: Boolean): Long {
        mainHandler.postDelayed({
            if (isPackageDockedOnRight(AppLauncher.PKG_SPOTIFY)) {
                Log.d(TAG, "Spotify is already docked on the right pane")
                return@postDelayed
            }
            val clickedRecents = tryClickSpotifyInRightRecents()
            Log.d(TAG, "Right-pane Recents click attempt result=$clickedRecents")
        }, startAtMs)

        val fallbackAt = startAtMs + DELAY_RIGHT_PANE_VERIFY_MS
        mainHandler.postDelayed({
            if (!isPackageDockedOnRight(AppLauncher.PKG_SPOTIFY)) {
                Log.i(TAG, "Spotify not yet on right pane; launching with ADJACENT + MULTIPLE_TASK + right bounds")
                launchSpotifyToRightBounds(showMissingToast)
            }
        }, fallbackAt)

        return fallbackAt
    }

    private fun launchSpotifyToRightBounds(showMissingToast: Boolean) {
        val dm = resources.displayMetrics
        val rightHalf = Rect(dm.widthPixels / 2, 0, dm.widthPixels, dm.heightPixels)
        val options = try {
            ActivityOptions.makeBasic().setLaunchBounds(rightHalf).toBundle()
        } catch (_: Exception) {
            null
        }
        AppLauncher.launchPackage(
            context = this,
            primaryPackage = AppLauncher.PKG_SPOTIFY,
            extraFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            newTask = true,
            showMissingToast = showMissingToast,
            optionsBundle = options
        )
    }

    private fun tryClickSpotifyInRightRecents(): Boolean {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        if (screenW <= 0 || screenH <= 0) return false

        val rightWindows = try {
            windows.filter { win ->
                if (win.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                    win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                ) {
                    return@filter false
                }
                val r = Rect()
                win.getBoundsInScreen(r)
                r.right > screenW / 2 && r.left >= screenW / 3
            }
        } catch (_: Exception) {
            emptyList()
        }

        // Pass 1: Explicit "Spotify" node match in right-half Recents
        for (win in rightWindows) {
            val root = win.root ?: continue
            val match = findNodeMatchingLabel(root, "spotify", screenW)
            if (match != null && clickSelfOrAncestor(match)) {
                return true
            }
        }

        // Pass 2: Leftmost large task-preview card in right-half Recents (above trash-can bar)
        val candidateCards = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        for (win in rightWindows) {
            val root = win.root ?: continue
            collectRightPaneRecentsCards(root, screenW, screenH, candidateCards)
        }
        val firstCard = candidateCards.minByOrNull { it.first }?.second
        return firstCard != null && clickSelfOrAncestor(firstCard)
    }

    private fun findNodeMatchingLabel(
        node: AccessibilityNodeInfo,
        needleLower: String,
        screenW: Int
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val textMatch = node.text?.toString()?.lowercase()?.contains(needleLower) == true
        val descMatch = node.contentDescription?.toString()?.lowercase()?.contains(needleLower) == true
        if ((textMatch || descMatch) && bounds.centerX() >= screenW / 2) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatchingLabel(child, needleLower, screenW)
            if (found != null) return found
        }
        return null
    }

    private fun collectRightPaneRecentsCards(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val isLargeCardInRightUpperArea = node.isClickable &&
            bounds.left >= screenW / 2 &&
            bounds.width() >= screenW / 8 &&
            bounds.height() >= screenH / 4 &&
            bounds.top >= screenH / 10 &&
            bounds.bottom <= (screenH * 0.72f).toInt()

        if (isLargeCardInRightUpperArea) {
            out.add(bounds.left to node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRightPaneRecentsCards(child, screenW, screenH, out)
        }
    }

    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isPackageDockedOnRight(targetPackage: String): Boolean {
        val screenW = resources.displayMetrics.widthPixels
        if (screenW <= 0) return false
        return try {
            windows.any { win ->
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
                val pkg = win.root?.packageName?.toString()
                if (pkg != targetPackage) return@any false
                val rect = Rect()
                win.getBoundsInScreen(rect)
                rect.left >= screenW / 3
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isCurrentlyInSplitScreen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
        } catch (_: Exception) {
            false
        }
    }

    private fun registerWakeReceiverIfNeeded() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenWakeReceiver, filter)
        isReceiverRegistered = true
    }

    /**
     * Starts, restarts, or stops the background phone-to-car SSE receiver according to
     * [RemoteNavStore.isEnabled]. Safe to call from UI pairing controls.
     */
    fun syncRemoteNavClient(forceRestart: Boolean = true) {
        if (!RemoteNavStore.isEnabled(this)) {
            remoteNavClient?.stop()
            return
        }
        val client = remoteNavClient ?: RemoteNavClient(this) { destination ->
            mainHandler.post {
                RemoteNavLaunchActivity.launch(
                    context = this,
                    displayLabel = destination.displayLabel,
                    navigationQuery = destination.navigationQuery
                )
            }
        }.also { remoteNavClient = it }

        if (forceRestart) {
            client.restart()
        } else {
            client.start()
        }
    }

    private fun cleanup() {
        remoteNavClient?.stop()
        remoteNavClient = null
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
        const val DELAY_ROUTINE_SPLIT_START_MS = 2200L
        const val DELAY_MAPS_AFTER_PREWARM_MS = 900L
        const val DELAY_SPLIT_TOGGLE_AFTER_MAPS_MS = 1200L
        const val DELAY_SPLIT_TOGGLE_AFTER_VOICE_NAV_MS = 1400L
        const val DELAY_SPOTIFY_AFTER_SPLIT_MS = 800L
        const val DELAY_RIGHT_PANE_VERIFY_MS = 700L
        const val DELAY_MEDIA_PLAY_AFTER_DOCK_MS = 1000L
        const val DELAY_SCREEN_OFF_CLEANUP_MS = 750L
        const val SWC_LEARN_TIMEOUT_MS = 12_000L

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
