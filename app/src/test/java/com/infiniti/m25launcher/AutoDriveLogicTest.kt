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

        val rearFallbackHit = AppLauncher.resolveFirstLaunchPackage(
            AppLauncher.REAR_CAMERA_CANDIDATES
        ) { pkg ->
            if (pkg == AppLauncher.PKG_AUX) fakeIntent else null
        }
        assertEquals(AppLauncher.PKG_AUX, rearFallbackHit?.first)

        val noneHit = AppLauncher.resolveFirstLaunchPackage(
            AppLauncher.REAR_CAMERA_CANDIDATES
        ) { null }
        assertNull(noneHit)
    }

    @Test
    fun sortLaunchableAppsForCameraPicker_prioritizesCameraAndSyuPackages() {
        val apps = listOf(
            AppLauncher.LaunchableApp("Spotify", "com.spotify.music"),
            AppLauncher.LaunchableApp("Maps", "com.google.android.apps.maps"),
            AppLauncher.LaunchableApp("Aux Video", "com.syu.aux"),
            AppLauncher.LaunchableApp("Aegis Speedcam", "tw.com.aegis.speedcam"),
            AppLauncher.LaunchableApp("Front Cam", "com.example.fcam")
        )
        val sorted = AppLauncher.sortLaunchableAppsForCameraPicker(apps)
        assertEquals("com.syu.aux", sorted[0].packageName)
        assertEquals("com.example.fcam", sorted[1].packageName)
        assertEquals("tw.com.aegis.speedcam", sorted[2].packageName)
        assertEquals("com.google.android.apps.maps", sorted[3].packageName)
        assertEquals("com.spotify.music", sorted[4].packageName)
    }
}
