/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Inspects and launches one-tap authorization screens for all APK-only permissions and
 * system bindings required for reliable head-unit operation without ADB.
 */
object SystemHealthHelper {

    data class HealthSnapshot(
        val isDefaultHome: Boolean,
        val isAutoDriveEnabled: Boolean,
        val isMediaSyncEnabled: Boolean,
        val hasLocationAndMic: Boolean,
        val isBatteryUnrestricted: Boolean
    ) {
        val totalChecks: Int get() = 5

        val readyCount: Int
            get() = listOf(
                isDefaultHome,
                isAutoDriveEnabled,
                isMediaSyncEnabled,
                hasLocationAndMic,
                isBatteryUnrestricted
            ).count { it }

        val isAllReady: Boolean get() = readyCount == totalChecks
    }

    fun collect(context: Context): HealthSnapshot {
        return HealthSnapshot(
            isDefaultHome = isDefaultHomeLauncher(context),
            isAutoDriveEnabled = AppLauncher.isAccessibilityServiceEnabled(
                context,
                AutoDriveService::class.java
            ),
            isMediaSyncEnabled = MediaSessionMonitor.isNotificationAccessEnabled(context),
            hasLocationAndMic = hasLocationPermission(context) && hasRecordAudioPermission(context),
            isBatteryUnrestricted = isIgnoringBatteryOptimizations(context)
        )
    }

    fun isDefaultHomeLauncher(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName == context.packageName
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun openDefaultHomeSettings(context: Context) {
        launchSettingsIntent(
            context,
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val directIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        launchSettingsIntent(
            context,
            directIntent,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    fun openAppDetailsSettings(context: Context) {
        val detailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        launchSettingsIntent(context, detailsIntent, Intent(Settings.ACTION_SETTINGS))
    }

    fun formatVersionLabel(): String =
        "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_STAMP}"

    private fun launchSettingsIntent(context: Context, vararg candidates: Intent) {
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Fall through to next compatible settings screen on vendor ROMs.
            }
        }
    }
}
