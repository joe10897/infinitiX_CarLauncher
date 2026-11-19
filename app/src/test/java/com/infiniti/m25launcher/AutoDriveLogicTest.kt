package com.infiniti.m25launcher

import android.content.Intent
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
}
