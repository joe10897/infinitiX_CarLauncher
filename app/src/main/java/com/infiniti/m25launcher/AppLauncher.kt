package com.infiniti.m25launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast

/**
 * Shared launch, media key dispatch, telemetry conversion, and accessibility helpers
 * for the Y51 DriveOS launcher and automation service.
 */
object AppLauncher {

    private const val TAG = "Y51AppLauncher"

    const val PKG_SPEEDCAM = "tw.com.aegis.speedcam"
    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_SPOTIFY = "com.spotify.music"
    const val PKG_CAR_INFO = "com.syu.carinfo"
    const val PKG_CANBUS = "com.syu.canbus"
    const val PKG_AVM = "com.syu.avm"
    const val PKG_FRONTVIEW = "com.syu.frontview"
    const val PKG_DVR = "com.syu.dvr"
    const val PKG_FCAM = "com.syu.fcam"
    const val PKG_AUX = "com.syu.aux"
    const val PKG_VIDEO = "com.syu.video"

    val REAR_CAMERA_CANDIDATES = listOf(
        PKG_AVM,
        PKG_FRONTVIEW,
        PKG_DVR,
        PKG_FCAM,
        PKG_AUX,
        PKG_VIDEO
    )

    private val CAMERA_KEYWORDS = listOf(
        "syu",
        "avm",
        "dvr",
        "fcam",
        "aux",
        "video",
        "frontview",
        "camera",
        "rear",
        "backcar"
    )

    data class LaunchableApp(
        val label: String,
        val packageName: String
    )

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
     * Prioritizes camera / FYT system packages first, then sorts alphabetically by label.
     */
    fun sortLaunchableAppsForCameraPicker(apps: List<LaunchableApp>): List<LaunchableApp> {
        return apps.sortedWith(
            compareByDescending<LaunchableApp> { app ->
                val lowerPkg = app.packageName.lowercase()
                val lowerLabel = app.label.lowercase()
                CAMERA_KEYWORDS.any { kw -> lowerPkg.contains(kw) || lowerLabel.contains(kw) }
            }.thenBy { it.label.lowercase() }
        )
    }

    /**
     * Queries all launchable apps on the device (excluding this launcher) for picker binding.
     */
    @Suppress("DEPRECATION")
    fun queryLaunchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                mainIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(mainIntent, 0)
        }

        val seenPackages = HashSet<String>()
        val result = ArrayList<LaunchableApp>()
        for (info in activities) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seenPackages.add(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            result.add(LaunchableApp(label = label, packageName = pkg))
        }
        return sortLaunchableAppsForCameraPicker(result)
    }

    /**
     * Resolves a human-readable application label for [packageName], or null if unavailable.
     */
    @Suppress("DEPRECATION")
    fun getAppLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launches the first available package from [candidates].
     */
    fun launchPackages(
        context: Context,
        candidates: List<String>,
        extraFlags: Int = 0,
        newTask: Boolean = true,
        showMissingToast: Boolean = true
    ): Boolean {
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
            context.startActivity(intent)
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
     * Launches [primaryPackage], falling back to [fallbackPackage] if unavailable.
     */
    fun launchPackage(
        context: Context,
        primaryPackage: String,
        fallbackPackage: String? = null,
        extraFlags: Int = 0,
        newTask: Boolean = true,
        showMissingToast: Boolean = true
    ): Boolean {
        return launchPackages(
            context = context,
            candidates = listOfNotNull(primaryPackage, fallbackPackage),
            extraFlags = extraFlags,
            newTask = newTask,
            showMissingToast = showMissingToast
        )
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
}
