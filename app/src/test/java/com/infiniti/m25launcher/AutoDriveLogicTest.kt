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
}
