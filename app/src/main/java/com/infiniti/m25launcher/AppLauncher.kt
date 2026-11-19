package com.infiniti.m25launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shared launch, media key dispatch, telemetry conversion, and accessibility helpers
 * for the Y51 DriveOS launcher and automation service.
 */
object AppLauncher {

    private const val TAG = "Y51AppLauncher"

    const val PKG_SPEEDCAM = "tw.com.ainvest.outpack"
    const val PKG_SPEEDCAM_LEGACY = "tw.com.aegis.speedcam"
    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_SPOTIFY = "com.spotify.music"
    const val PKG_CAR_INFO = "com.syu.carinfo"
    const val PKG_CANBUS = "com.syu.canbus"

    val SPEEDCAM_CANDIDATES = listOf(PKG_SPEEDCAM, PKG_SPEEDCAM_LEGACY)

    data class DrawerAppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    /**
     * Queries all launchable apps on the head unit (excluding this launcher) for the hidden drawer.
     */
    @Suppress("DEPRECATION")
    fun queryDrawerApps(context: Context): List<DrawerAppItem> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(intent, 0)
        }

        val seen = HashSet<String>()
        val items = ArrayList<DrawerAppItem>()
        for (info in activities) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            val icon = try {
                info.loadIcon(pm)
            } catch (_: Exception) {
                continue
            }
            items.add(DrawerAppItem(label = label, packageName = pkg, icon = icon))
        }
        return items.sortedBy { it.label.lowercase() }
    }

    /**
     * Converts meters-per-second GPS speed to an integer km/h reading.
     */
    fun mpsToKmhInt(speedMps: Float): Int {
        if (speedMps <= 0f || speedMps.isNaN()) return 0
        return (speedMps * 3.6f).toInt()
    }

    /**
     * Selects the first launchable intent from [candidates], preserving priority order.
     */
    fun resolveFirstLaunchPackage(
        candidates: List<String>,
        intentResolver: (String) -> Intent?
    ): Pair<String, Intent>? {
        for (pkg in candidates) {
            val intent = intentResolver(pkg)
            if (intent != null) {
                return pkg to intent
            }
        }
        return null
    }

    /**
     * Launches Aegis Speedcam by known package IDs first, then falls back to matching
     * installed launchable app labels containing "神盾" or "speedcam".
     */
    fun launchSpeedcam(context: Context, showMissingToast: Boolean = true): Boolean {
        if (launchPackage(
                context = context,
                primaryPackage = PKG_SPEEDCAM,
                fallbackPackage = PKG_SPEEDCAM_LEGACY,
                showMissingToast = false
            )
        ) {
            return true
        }

        val labelMatch = queryDrawerApps(context).firstOrNull { app ->
            val lowerLabel = app.label.lowercase()
            app.label.contains("神盾") || lowerLabel.contains("speedcam")
        }
        if (labelMatch != null) {
            return launchPackage(
                context = context,
                primaryPackage = labelMatch.packageName,
                showMissingToast = showMissingToast
            )
        }

        if (showMissingToast) {
            Toast.makeText(
                context,
                context.getString(R.string.msg_app_not_installed, SPEEDCAM_CANDIDATES.joinToString(" / ")),
                Toast.LENGTH_SHORT
            ).show()
        }
        return false
    }

    /**
     * Launches [primaryPackage], falling back to [fallbackPackage] if unavailable.
     * Applies [extraFlags] (such as [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT]) and optional [optionsBundle],
     * automatically adding [Intent.FLAG_ACTIVITY_NEW_TASK] when [newTask] is true.
     */
    fun launchPackage(
        context: Context,
        primaryPackage: String,
        fallbackPackage: String? = null,
        extraFlags: Int = 0,
        newTask: Boolean = true,
        showMissingToast: Boolean = true,
        optionsBundle: android.os.Bundle? = null
    ): Boolean {
        val candidates = listOfNotNull(primaryPackage, fallbackPackage)
        val pm = context.packageManager
        val resolved = resolveFirstLaunchPackage(candidates) { pkg ->
            pm.getLaunchIntentForPackage(pkg)
        }

        if (resolved == null) {
            Log.w(TAG, "No launchable package found for candidates: $candidates")
            if (showMissingToast) {
                val missingDisplay = candidates.joinToString(" / ")
                Toast.makeText(
                    context,
                    context.getString(R.string.msg_app_not_installed, missingDisplay),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        val (pkgName, intent) = resolved
        if (newTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (extraFlags != 0) {
            intent.addFlags(extraFlags)
        }

        return try {
            if (optionsBundle != null) {
                context.startActivity(intent, optionsBundle)
            } else {
                context.startActivity(intent)
            }
            Log.d(TAG, "Launched package: $pkgName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package: $pkgName", e)
            if (showMissingToast) {
                Toast.makeText(
                    context,
                    context.getString(R.string.msg_app_not_installed, pkgName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            false
        }
    }

    /**
     * Dispatches a DOWN + UP hardware media key event through [AudioManager].
     */
    fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    /**
     * Checks both [AccessibilityManager] and [Settings.Secure] to determine whether
     * [serviceClass] is currently enabled in system accessibility settings.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager != null) {
            val enabledList = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val targetName = serviceClass.name
            val found = enabledList.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo != null &&
                    serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == targetName
            }
            if (found) return true
        }

        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
        } catch (_: Exception) {
            0
        }
        if (accessibilityEnabled != 1) return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val component = ComponentName(context, serviceClass)
        val fullName = component.flattenToString()
        val shortName = component.flattenToShortString()
        return enabledServices.split(':').any { entry ->
            entry.equals(fullName, ignoreCase = true) || entry.equals(shortName, ignoreCase = true)
        }
    }

    /**
     * Applies sticky immersive fullscreen mode hiding both system status and navigation bars.
     */
    @Suppress("DEPRECATION")
    fun applyImmersiveFullscreen(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }
}
