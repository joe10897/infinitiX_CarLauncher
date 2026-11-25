package com.infiniti.m25launcher

import android.content.Intent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDriveLogicTest {

    @Test
    fun mpsToKmhInt_convertsAndFloorsCorrectly() {
        assertEquals(0, AppLauncher.mpsToKmhInt(0f))
        assertEquals(0, AppLauncher.mpsToKmhInt(-2.5f))
        assertEquals(0, AppLauncher.mpsToKmhInt(Float.NaN))
        assertEquals(36, AppLauncher.mpsToKmhInt(10.0f))
        assertEquals(99, AppLauncher.mpsToKmhInt(27.7f))
        assertEquals(100, AppLauncher.mpsToKmhInt(27.8f))
    }

    @Test
    fun cooldown_allowsInitialTriggerAndEnforcesFiveMinuteWindow() {
        val fiveMinutes = 300_000L
        assertTrue(AutoDriveService.shouldTriggerWithCooldown(10_000L, null, fiveMinutes))
        assertFalse(AutoDriveService.shouldTriggerWithCooldown(10_500L, 10_000L, fiveMinutes))
        assertFalse(AutoDriveService.shouldTriggerWithCooldown(309_999L, 10_000L, fiveMinutes))
        assertTrue(AutoDriveService.shouldTriggerWithCooldown(310_000L, 10_000L, fiveMinutes))
    }

    @Test
    fun resolveFirstLaunchPackage_usesPrimaryOrFallback() {
        val fakeIntent = Intent()
        val primaryHit = AppLauncher.resolveFirstLaunchPackage(
            listOf(AppLauncher.PKG_CAR_INFO, AppLauncher.PKG_CANBUS)
        ) { pkg ->
            if (pkg == AppLauncher.PKG_CAR_INFO) fakeIntent else null
        }
        assertEquals(AppLauncher.PKG_CAR_INFO, primaryHit?.first)

        val fallbackHit = AppLauncher.resolveFirstLaunchPackage(
            listOf(AppLauncher.PKG_CAR_INFO, AppLauncher.PKG_CANBUS)
        ) { pkg ->
            if (pkg == AppLauncher.PKG_CANBUS) fakeIntent else null
        }
        assertEquals(AppLauncher.PKG_CANBUS, fallbackHit?.first)

        val noneHit = AppLauncher.resolveFirstLaunchPackage(
            listOf(AppLauncher.PKG_CAR_INFO, AppLauncher.PKG_CANBUS)
        ) { null }
        assertNull(noneHit)

        val speedcamHit = AppLauncher.resolveFirstLaunchPackage(AppLauncher.SPEEDCAM_CANDIDATES) { pkg ->
            if (pkg == "tw.com.ainvest.outpack") fakeIntent else null
        }
        assertEquals("tw.com.ainvest.outpack", speedcamHit?.first)
    }

    @Test
    fun liquidGlassDrawable_detectsPressedStateFlag() {
        assertFalse(LiquidGlassDrawable.hasPressedState(intArrayOf()))
        assertFalse(LiquidGlassDrawable.hasPressedState(intArrayOf(android.R.attr.state_enabled)))
        assertTrue(LiquidGlassDrawable.hasPressedState(intArrayOf(android.R.attr.state_pressed)))
        assertTrue(
            LiquidGlassDrawable.hasPressedState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
            )
        )
    }

    @Test
    fun tripTelemetryTracker_filtersDeadbandAndTracksPeakAvgDuration() {
        assertEquals(0, TripTelemetryTracker.filterSpeedKmh(0.4f, hasSpeed = true))
        assertEquals(0, TripTelemetryTracker.filterSpeedKmh(10.0f, hasSpeed = false))
        assertEquals(72, TripTelemetryTracker.filterSpeedKmh(20.0f, hasSpeed = true))

        val tracker = TripTelemetryTracker()
        val s0 = tracker.recordSample(0, 1_000L)
        assertEquals(0, s0.maxSpeedKmh)
        assertEquals(0L, s0.elapsedMs)

        val s1 = tracker.recordSample(60, 10_000L)
        assertEquals(60, s1.maxSpeedKmh)
        assertEquals(60, s1.avgSpeedKmh)
        assertEquals(0L, s1.elapsedMs)

        val s2 = tracker.recordSample(100, 75_000L)
        assertEquals(100, s2.maxSpeedKmh)
        assertEquals(80, s2.avgSpeedKmh)
        assertEquals(65_000L, s2.elapsedMs)
        assertEquals("01:05", TripTelemetryTracker.formatDuration(s2.elapsedMs))
        assertEquals("1:01:05", TripTelemetryTracker.formatDuration(3_665_000L))

        tracker.reset()
        val resetSnap = tracker.currentSnapshot(0, 80_000L)
        assertEquals(0, resetSnap.maxSpeedKmh)
        assertEquals(0, resetSnap.avgSpeedKmh)
        assertEquals(0L, resetSnap.elapsedMs)
    }

    @Test
    fun mediaSessionMonitor_interpolatesPositionAndComputesProgressRatio() {
        assertEquals(
            15_000L,
            MediaSessionMonitor.interpolatePositionMs(
                basePositionMs = 10_000L,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                playbackSpeed = 1.0f,
                isPlaying = true
            )
        )
        assertEquals(
            10_000L,
            MediaSessionMonitor.interpolatePositionMs(
                basePositionMs = 10_000L,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                playbackSpeed = 1.0f,
                isPlaying = false
            )
        )
        assertEquals(0f, MediaSessionMonitor.computeProgressRatio(10_000L, 0L), 0.001f)
        assertEquals(0.5f, MediaSessionMonitor.computeProgressRatio(90_000L, 180_000L), 0.001f)
        assertEquals(1f, MediaSessionMonitor.computeProgressRatio(200_000L, 180_000L), 0.001f)
    }

    @Test
    fun swcKeyStore_matchesDefaultAndLearnedKeysWhileProtectingSystemKeys() {
        assertTrue(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_VOICE_ASSIST, null))
        assertTrue(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_ASSIST, null))
        assertTrue(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_SEARCH, null))

        assertFalse(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_F1, null))
        assertTrue(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F1))

        assertFalse(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK))
        assertFalse(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_HOME))
        assertFalse(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(SwcKeyStore.isVoiceTriggerKey(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_UNKNOWN))
        assertFalse(SwcKeyStore.isVoiceTriggerKey(0, 0))
    }

    @Test
    fun appLauncher_buildSplitPolicyControlValue_targetsMapsAndSpotify() {
        assertEquals(
            "immersive.status=com.google.android.apps.maps,com.spotify.music",
            AppLauncher.buildSplitPolicyControlValue(true)
        )
        assertEquals("", AppLauncher.buildSplitPolicyControlValue(false))
    }

    @Test
    fun appLauncher_shouldKillOnScreenOff_protectsFytCoreAndSelf() {
        val self = "com.infiniti.m25launcher"
        assertTrue(AppLauncher.shouldKillOnScreenOff("com.google.android.apps.maps", self))
        assertTrue(AppLauncher.shouldKillOnScreenOff("com.spotify.music", self))
        assertTrue(AppLauncher.shouldKillOnScreenOff("tw.com.ainvest.outpack", self))

        assertFalse(AppLauncher.shouldKillOnScreenOff(self, self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("android", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.android.systemui", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.syu.ms", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.syu.canbus", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.fyt.boot", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.google.android.gms", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.google.android.googlequicksearchbox", self))
        assertFalse(AppLauncher.shouldKillOnScreenOff("com.google.android.inputmethod.latin", self))
    }

    @Test
    fun voiceTrigger_identifiesActivityFallbackErrorsAndFormatsCodes() {
        assertTrue(VoiceTriggerActivity.shouldTryActivityFallback(SpeechRecognizer.ERROR_CLIENT))
        assertTrue(VoiceTriggerActivity.shouldTryActivityFallback(SpeechRecognizer.ERROR_AUDIO))
        assertTrue(VoiceTriggerActivity.shouldTryActivityFallback(SpeechRecognizer.ERROR_SERVER))
        assertFalse(VoiceTriggerActivity.shouldTryActivityFallback(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals("AUDIO #3", VoiceTriggerActivity.formatSpeechErrorLabel(SpeechRecognizer.ERROR_AUDIO))
        assertEquals("CLIENT #5", VoiceTriggerActivity.formatSpeechErrorLabel(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun tripTelemetryTracker_formatsCompassHeadingAndDetectsOverspeed() {
        assertEquals("N 000°", TripTelemetryTracker.formatCompassHeading(0f))
        assertEquals("NE 042°", TripTelemetryTracker.formatCompassHeading(42f))
        assertEquals("E 090°", TripTelemetryTracker.formatCompassHeading(90f))
        assertEquals("SW 225°", TripTelemetryTracker.formatCompassHeading(225f))
        assertEquals("NW 315°", TripTelemetryTracker.formatCompassHeading(-45f))
        assertEquals("N 000°", TripTelemetryTracker.formatCompassHeading(359.8f))
        assertEquals("N 000°", TripTelemetryTracker.formatCompassHeading(Float.NaN))

        assertFalse(TripTelemetryTracker.isOverspeed(114))
        assertTrue(TripTelemetryTracker.isOverspeed(115))
        assertTrue(TripTelemetryTracker.isOverspeed(132))
    }

    @Test
    fun pinnedAppsStore_parsesSerializesAndSortsPinnedFirst() {
        val parsed = PinnedAppsStore.parsePinnedList(" com.zlink , com.youtube , com.zlink , ")
        assertEquals(listOf("com.zlink", "com.youtube"), parsed)
        assertEquals("com.zlink,com.youtube", PinnedAppsStore.serializePinnedList(parsed))

        val items = listOf("com.alpha", "com.youtube", "com.beta", "com.zlink")
        val sorted = PinnedAppsStore.sortWithPinnedFirst(items, parsed) { it }
        assertEquals(listOf("com.zlink", "com.youtube", "com.alpha", "com.beta"), sorted)
    }

    @Test
    fun cockpitThemeMode_distinguishesDayAndNightHours() {
        assertFalse(CockpitThemeMode.isDaytimeHour(5))
        assertTrue(CockpitThemeMode.isDaytimeHour(6))
        assertTrue(CockpitThemeMode.isDaytimeHour(12))
        assertTrue(CockpitThemeMode.isDaytimeHour(17))
        assertFalse(CockpitThemeMode.isDaytimeHour(18))
        assertFalse(CockpitThemeMode.isDaytimeHour(23))
    }

    @Test
    fun systemHealthSnapshot_countsReadyChecksAccurately() {
        val partial = SystemHealthHelper.HealthSnapshot(
            isDefaultHome = true,
            isAutoDriveEnabled = true,
            isMediaSyncEnabled = false,
            hasLocationAndMic = true,
            isBatteryUnrestricted = false
        )
        assertEquals(3, partial.readyCount)
        assertFalse(partial.isAllReady)

        val full = partial.copy(isMediaSyncEnabled = true, isBatteryUnrestricted = true)
        assertEquals(5, full.readyCount)
        assertTrue(full.isAllReady)
    }

    @Test
    fun mediaSessionMonitor_formatsTimecodeAndExtractsAmbientAccent() {
        assertEquals("--:-- / --:--", MediaSessionMonitor.formatTrackTimecode(0L, 0L))
        assertEquals("01:24 / 03:58", MediaSessionMonitor.formatTrackTimecode(84_000L, 238_000L))
        assertEquals("03:58 / 03:58", MediaSessionMonitor.formatTrackTimecode(300_000L, 238_000L))
        assertEquals("1:01:05 / 1:30:00", MediaSessionMonitor.formatTrackTimecode(3_665_000L, 5_400_000L))

        assertNull(MediaSessionMonitor.extractAmbientAccentRgb(intArrayOf()))
        assertNull(MediaSessionMonitor.extractAmbientAccentRgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())))

        val deepIndigo = 0xFF3A44B8.toInt()
        val accent = MediaSessionMonitor.extractAmbientAccentRgb(intArrayOf(deepIndigo, deepIndigo, 0xFF050505.toInt()))
        assertEquals(0x3A44B8, accent)
    }

    @Test
    fun remoteNavStore_generatesAndFormatsPairingCodesAndTopics() {
        val code = RemoteNavStore.generatePairingCode(java.util.Random(42L))
        assertTrue(code.matches(Regex("""Y51-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}""")))
        assertEquals("Y51-7K4M-9P2X", RemoteNavStore.formatPairingCode("y51-7k4m-9p2x"))
        assertEquals("infinitiy51-nav-y517k4m9p2x", RemoteNavStore.topicForPairingCode("Y51-7K4M-9P2X"))
        assertEquals(
            "https://ntfy.sh/infinitiy51-nav-y517k4m9p2x/sse?since=2m",
            RemoteNavStore.buildSseUrl("Y51-7K4M-9P2X")
        )
    }

    @Test
    fun remoteNavParser_parsesSseFramesAndExtractsPinpointDestinations() {
        val keepalive = RemoteNavParser.parseSseEventLine("""data: {"id":"abc","time":1700000000,"event":"keepalive"}""")
        assertNull(keepalive)

        val sseLine = """data: {"id":"msg1","time":1700000100,"event":"message","message":"{\"v\":1,\"raw\":\"台北101 https://maps.app.goo.gl/xyz\"}"}"""
        val parsedMsg = RemoteNavParser.parseSseEventLine(sseLine)
        assertEquals("msg1", parsedMsg?.id)
        assertEquals("台北101 https://maps.app.goo.gl/xyz", parsedMsg?.rawPayload)
        assertTrue(RemoteNavParser.isFreshMessage(1700000100L, 1700000200L))
        assertFalse(RemoteNavParser.isFreshMessage(1700000000L, 1700000500L))

        assertTrue(RemoteNavParser.isShortMapUrl("https://maps.app.goo.gl/AbCd123"))
        assertFalse(RemoteNavParser.isShortMapUrl("https://www.google.com/maps/place/Taipei+101"))

        // Pinpoint !3d!4d takes precedence over camera center @lat,lng
        val expanded = "https://www.google.com/maps/place/Taipei+101/@25.0300,121.5600,17z/data=!3m1!4b1!4m6!3m5!1s0x0!8m2!3d25.033964!4d121.564472"
        val fromMaps = RemoteNavParser.extractNavigationTarget(
            rawText = "台北101 https://maps.app.goo.gl/xyz",
            resolvedUrl = expanded
        )
        assertEquals("25.033964,121.564472", fromMaps?.navigationQuery)
        assertEquals("台北101", fromMaps?.displayLabel)

        // Apple Maps link
        val fromApple = RemoteNavParser.extractNavigationTarget("https://maps.apple.com/?ll=25.0478,121.5170&q=Taipei+Main+Station")
        assertEquals("25.0478,121.5170", fromApple?.navigationQuery)
        assertEquals("Taipei Main Station", fromApple?.displayLabel)

        // Direct geo: URI
        val fromGeo = RemoteNavParser.extractNavigationTarget("geo:25.0339,121.5644?q=Taipei+101")
        assertEquals("25.0339,121.5644", fromGeo?.navigationQuery)
        assertEquals("Taipei 101", fromGeo?.displayLabel)

        // Plain LINE address text
        val fromPlain = RemoteNavParser.extractNavigationTarget("台北市信義區市府路1號")
        assertEquals("台北市信義區市府路1號", fromPlain?.navigationQuery)
        assertEquals("台北市信義區市府路1號", fromPlain?.displayLabel)
    }
}
