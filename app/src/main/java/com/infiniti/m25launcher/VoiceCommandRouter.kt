package com.infiniti.m25launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/**
 * Parses natural Traditional Chinese / English mixed voice transcripts and routes them
 * to Y51 DriveOS automation, split-screen navigation, Spotify playback, or head-unit controls.
 */
object VoiceCommandRouter {

    private const val TAG = "Y51VoiceRouter"

    enum class MediaCommand {
        PLAY, PAUSE, NEXT, PREV
    }

    enum class VolumeDirection {
        UP, DOWN, MUTE, UNMUTE
    }

    sealed class VoiceAction {
        data class NavigateSplit(val destination: String) : VoiceAction()
        object NavigateHome : VoiceAction()
        object NavigateWork : VoiceAction()
        data class PlaySpotifySearch(val query: String) : VoiceAction()
        data class MediaControl(val command: MediaCommand) : VoiceAction()
        data class VolumeControl(val direction: VolumeDirection) : VoiceAction()
        object StartSplitNavigation : VoiceAction()
        object StartAutoDriveRoutine : VoiceAction()
        object LaunchSpeedcam : VoiceAction()
        object LaunchVehicleInfo : VoiceAction()
        object GoHome : VoiceAction()
        data class FallbackAssistant(val rawQuery: String) : VoiceAction()
    }

    private val NAVIGATION_PREFIXES = listOf(
        "導航到", "導航去", "帶我去", "帶我到", "我想去", "開車去", "前往",
        "navigate to ", "go to "
    )

    private val SPOTIFY_SEARCH_PREFIXES = listOf(
        "用spotify播放", "用 spotify 播放", "spotify播放", "spotify 播",
        "我想聽", "幫我播", "幫我放", "播一首", "放一首", "點播", "聽一下", "播放",
        "play "
    )

    private val TRIM_CHARS = charArrayOf(' ', '\t', '\n', '。', '，', '！', '？', '.', ',', '!', '?', '"', '\'', '「', '」')

    /**
     * Evaluates N-best speech recognition hypotheses and picks the first recognized local car command,
     * falling back to [VoiceAction.FallbackAssistant] with the top hypothesis if none match.
     */
    fun parseCandidates(candidates: List<String>): VoiceAction {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return VoiceAction.FallbackAssistant("")
        for (candidate in cleaned) {
            val action = parse(candidate)
            if (action !is VoiceAction.FallbackAssistant) {
                return action
            }
        }
        return VoiceAction.FallbackAssistant(cleaned.first())
    }

    /**
     * Parses a single speech transcript into a structured [VoiceAction].
     */
    fun parse(rawTranscript: String): VoiceAction {
        val text = rawTranscript.trim()
        if (text.isEmpty()) return VoiceAction.FallbackAssistant("")
        val lower = text.lowercase()

        // 1. Fixed high-priority automation & app launch commands
        if (containsAny(lower, "一鍵出發", "行車模式", "自動駕駛", "啟動行車", "出發模式", "run split routine")) {
            return VoiceAction.StartAutoDriveRoutine
        }
        if (containsAny(lower, "分屏導航", "分割畫面", "雙畫面", "左邊地圖右邊音樂", "split navigation", "split screen")) {
            return VoiceAction.StartSplitNavigation
        }
        if (containsAny(lower, "神盾", "測速", "speedcam")) {
            return VoiceAction.LaunchSpeedcam
        }
        if (containsAny(lower, "原車資訊", "車輛資訊", "車況", "行車電腦", "vehicle info", "canbus")) {
            return VoiceAction.LaunchVehicleInfo
        }
        if (containsAny(lower, "回桌面", "回主畫面", "回首頁") || lower == "home") {
            return VoiceAction.GoHome
        }
        if (containsAny(lower, "導航回家", "導航到家", "帶我回家", "載我回家", "我要回家", "開回家", "回住處", "navigate home", "go home") ||
            lower == "回家"
        ) {
            return VoiceAction.NavigateHome
        }
        if (containsAny(lower, "去公司", "導航到公司", "導航去公司", "導航回公司", "帶我去公司", "我要去公司", "navigate to work", "go to work") ||
            lower == "上班"
        ) {
            return VoiceAction.NavigateWork
        }

        // 2. Fixed media transport commands (checked before generic "播放" search prefix)
        if (containsAny(lower, "下一首", "換下一首", "切歌", "跳過", "next song", "next track") ||
            lower == "next" || lower == "skip"
        ) {
            return VoiceAction.MediaControl(MediaCommand.NEXT)
        }
        if (containsAny(lower, "上一首", "前一首", "回到上一首", "previous song", "previous track") ||
            lower == "previous" || lower == "prev"
        ) {
            return VoiceAction.MediaControl(MediaCommand.PREV)
        }
        if (containsAny(lower, "暫停", "停止播放", "不要播", "stop music") || lower == "pause") {
            return VoiceAction.MediaControl(MediaCommand.PAUSE)
        }
        if (containsAny(lower, "繼續播放", "恢復播放", "開始播放", "播放音樂", "播音樂", "play music", "resume music") ||
            lower == "play" || lower == "resume"
        ) {
            return VoiceAction.MediaControl(MediaCommand.PLAY)
        }

        // 3. Volume controls (check unmute before mute)
        if (containsAny(lower, "取消靜音", "恢復聲音", "unmute")) {
            return VoiceAction.VolumeControl(VolumeDirection.UNMUTE)
        }
        if (containsAny(lower, "靜音", "閉嘴", "mute")) {
            return VoiceAction.VolumeControl(VolumeDirection.MUTE)
        }
        if (containsAny(lower, "大聲", "大一點", "調大", "調高音量", "音量加", "volume up", "louder")) {
            return VoiceAction.VolumeControl(VolumeDirection.UP)
        }
        if (containsAny(lower, "小聲", "小一點", "調小", "調低音量", "音量減", "volume down", "quieter")) {
            return VoiceAction.VolumeControl(VolumeDirection.DOWN)
        }

        // 4. Parameterized navigation -> Google Maps + Spotify split screen
        extractAfterPrefix(text, lower, NAVIGATION_PREFIXES)?.let { destination ->
            return VoiceAction.NavigateSplit(destination)
        }

        // 5. Parameterized Spotify search playback
        extractAfterPrefix(text, lower, SPOTIFY_SEARCH_PREFIXES)?.let { query ->
            return VoiceAction.PlaySpotifySearch(query)
        }

        return VoiceAction.FallbackAssistant(text)
    }

    /**
     * Executes [action] and returns a short uppercase status summary for the voice HUD.
     */
    fun execute(context: Context, action: VoiceAction): String {
        Log.i(TAG, "Executing voice action: $action")
        return when (action) {
            is VoiceAction.NavigateSplit -> {
                navigateDestinationWithSplit(context, action.destination)
                "NAV SPLIT -> ${action.destination}"
            }

            VoiceAction.NavigateHome -> {
                val homeAddress = SavedPlacesStore.getHomeAddress(context)
                if (homeAddress != null) {
                    navigateDestinationWithSplit(context, homeAddress)
                    "NAV HOME -> $homeAddress"
                } else {
                    openSavedPlacesSetup(context, R.string.msg_home_not_set)
                    "SET HOME ADDRESS"
                }
            }

            VoiceAction.NavigateWork -> {
                val workAddress = SavedPlacesStore.getWorkAddress(context)
                if (workAddress != null) {
                    navigateDestinationWithSplit(context, workAddress)
                    "NAV WORK -> $workAddress"
                } else {
                    openSavedPlacesSetup(context, R.string.msg_work_not_set)
                    "SET WORK ADDRESS"
                }
            }

            is VoiceAction.PlaySpotifySearch -> {
                launchSpotifySearch(context, action.query)
                "SPOTIFY -> ${action.query}"
            }

            is VoiceAction.MediaControl -> {
                val keyCode = when (action.command) {
                    MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                    MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    MediaCommand.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                AppLauncher.dispatchMediaKey(context, keyCode)
                "MEDIA -> ${action.command.name}"
            }

            is VoiceAction.VolumeControl -> {
                adjustMusicVolume(context, action.direction)
                "VOLUME -> ${action.direction.name}"
            }

            VoiceAction.StartSplitNavigation -> {
                val service = AutoDriveService.instance
                if (service != null) {
                    service.triggerSplitNavigationNow()
                    "SPLIT NAVIGATION"
                } else {
                    openAccessibilityPrompt(context)
                    "ENABLE ACCESSIBILITY"
                }
            }

            VoiceAction.StartAutoDriveRoutine -> {
                val service = AutoDriveService.instance
                if (service != null) {
                    service.triggerFullRoutineNow()
                    "AUTO-DRIVE ROUTINE"
                } else {
                    openAccessibilityPrompt(context)
                    "ENABLE ACCESSIBILITY"
                }
            }

            VoiceAction.LaunchSpeedcam -> {
                AppLauncher.launchSpeedcam(context)
                "SPEEDCAM HUD"
            }

            VoiceAction.LaunchVehicleInfo -> {
                AppLauncher.launchPackage(context, AppLauncher.PKG_CAR_INFO, AppLauncher.PKG_CANBUS)
                "VEHICLE INFO"
            }

            VoiceAction.GoHome -> {
                val homeIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(homeIntent)
                "HOME LAUNCHER"
            }

            is VoiceAction.FallbackAssistant -> {
                "UNMATCHED"
            }
        }
    }

    private fun navigateDestinationWithSplit(context: Context, destination: String) {
        val service = AutoDriveService.instance
        if (service != null) {
            service.triggerNavigationWithSplit(destination)
        } else {
            launchGoogleMapsNavigation(context, destination)
        }
    }

    /**
     * Launches Google Maps directly into navigation (or geo search fallback) for [destination].
     */
    fun launchGoogleMapsNavigation(context: Context, destination: String) {
        val encoded = Uri.encode(destination)
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
            setPackage(AppLauncher.PKG_MAPS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val startedNav = try {
            if (navIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(navIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        if (!startedNav) {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                setPackage(AppLauncher.PKG_MAPS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(geoIntent)
            } catch (_: Exception) {
                AppLauncher.launchPackage(context, AppLauncher.PKG_MAPS)
            }
        }
    }

    /**
     * Launches Spotify directly with a media search query so playback begins automatically.
     */
    fun launchSpotifySearch(context: Context, query: String) {
        val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(AppLauncher.PKG_SPOTIFY)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = try {
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        if (!launched) {
            AppLauncher.launchPackage(context, AppLauncher.PKG_SPOTIFY)
        }
    }

    /**
     * Opens the system Google Assistant / default voice command handler as a fallback.
     */
    fun launchSystemAssistant(context: Context): Boolean {
        val intents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent("android.speech.action.WEB_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        for (intent in intents) {
            try {
                val resolved = intent.resolveActivity(context.packageManager)
                if (resolved != null && resolved.packageName != context.packageName) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // Try next fallback intent.
            }
        }
        return false
    }

    private fun adjustMusicVolume(context: Context, direction: VolumeDirection) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        when (direction) {
            VolumeDirection.UP -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
            VolumeDirection.DOWN -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
            VolumeDirection.MUTE -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            VolumeDirection.UNMUTE -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
    }

    private fun openSavedPlacesSetup(context: Context, messageResId: Int) {
        Toast.makeText(context, messageResId, Toast.LENGTH_LONG).show()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_SAVED_PLACES, true)
        }
        context.startActivity(intent)
    }

    private fun openAccessibilityPrompt(context: Context) {
        Toast.makeText(context, R.string.msg_enable_accessibility, Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun containsAny(lowerText: String, vararg keywords: String): Boolean =
        keywords.any { lowerText.contains(it) }

    private fun extractAfterPrefix(
        original: String,
        lower: String,
        prefixes: List<String>
    ): String? {
        for (prefix in prefixes) {
            val idx = lower.indexOf(prefix)
            if (idx >= 0) {
                val after = original.substring(idx + prefix.length).trim(*TRIM_CHARS)
                if (after.isNotEmpty()) {
                    return after
                }
            }
        }
        return null
    }
}
