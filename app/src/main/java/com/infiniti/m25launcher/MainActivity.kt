package com.infiniti.m25launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Infiniti Y51 Fine Vision Home Launcher optimized for 1280x480 ultra-wide landscape head units.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var layoutAutoDriveStatus: View
    private lateinit var viewStatusIndicator: View
    private lateinit var tvAutoDriveStatus: TextView

    private lateinit var layoutGpsHeader: View
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvSpeedValue: AppCompatTextView

    private var locationManager: LocationManager? = null
    private var accessibilityManager: AccessibilityManager? = null
    private var isGpsListening = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startGpsUpdates()
        } else {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
        }
    }

    private val accessibilityStateListener =
        AccessibilityManager.AccessibilityStateChangeListener {
            refreshAutoDriveStatus()
        }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedKmh = AppLauncher.mpsToKmhInt(location.speed)
            tvSpeedValue.text = speedKmh.toString()
            updateGpsStatus(R.string.gps_state_lock, isLocked = true)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                updateGpsStatus(R.string.gps_state_searching, isLocked = false)
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                tvSpeedValue.text = "0"
                updateGpsStatus(R.string.gps_state_disabled, isLocked = false)
            }
        }

        @Deprecated("Required on API 26-28 devices to prevent AbstractMethodError")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // No-op; state updates arrive via onLocationChanged/onProviderEnabled/onProviderDisabled.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupBackNavigationBlock()
        setupListeners()
        applyImmersiveFullscreen()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        ensureLocationPermissionAndStart()
    }

    override fun onStart() {
        super.onStart()
        accessibilityManager?.addAccessibilityStateChangeListener(accessibilityStateListener)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        refreshAutoDriveStatus()
        startGpsUpdates()
    }

    override fun onPause() {
        stopGpsUpdates()
        super.onPause()
    }

    override fun onStop() {
        accessibilityManager?.removeAccessibilityStateChangeListener(accessibilityStateListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun bindViews() {
        layoutAutoDriveStatus = findViewById(R.id.layoutAutoDriveStatus)
        viewStatusIndicator = findViewById(R.id.viewStatusIndicator)
        tvAutoDriveStatus = findViewById(R.id.tvAutoDriveStatus)

        layoutGpsHeader = findViewById(R.id.layoutGpsHeader)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
    }

    private fun setupBackNavigationBlock() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Home Launcher intentionally absorbs Back button presses.
                }
            }
        )
    }

    private fun setupListeners() {
        layoutAutoDriveStatus.setOnClickListener {
            openAccessibilitySettings()
        }

        layoutGpsHeader.setOnClickListener {
            if (!hasLocationPermission()) {
                requestLocationPermissions()
            }
        }

        findViewById<View>(R.id.btnRunSplitRoutine).setOnClickListener {
            val service = AutoDriveService.instance
            if (service != null) {
                service.triggerFullRoutineNow()
                Toast.makeText(this, R.string.msg_routine_triggered, Toast.LENGTH_SHORT).show()
            } else {
                promptEnableAccessibility()
            }
        }

        findViewById<View>(R.id.btnSplitNav).setOnClickListener {
            val service = AutoDriveService.instance
            if (service != null) {
                service.triggerSplitNavigationNow()
                Toast.makeText(this, R.string.msg_split_triggered, Toast.LENGTH_SHORT).show()
            } else {
                promptEnableAccessibility()
            }
        }

        findViewById<View>(R.id.btnSpeedcam).setOnClickListener {
            AppLauncher.launchPackage(this, AppLauncher.PKG_SPEEDCAM)
        }

        findViewById<View>(R.id.btnVehicleInfo).setOnClickListener {
            AppLauncher.launchPackage(
                this,
                primaryPackage = AppLauncher.PKG_CAR_INFO,
                fallbackPackage = AppLauncher.PKG_CANBUS
            )
        }

        findViewById<View>(R.id.btnSurround360).setOnClickListener {
            AppLauncher.launchPackage(
                this,
                primaryPackage = AppLauncher.PKG_AVM,
                fallbackPackage = AppLauncher.PKG_FRONTVIEW
            )
        }

        findViewById<View>(R.id.btnSpotifyHeader).setOnClickListener {
            AppLauncher.launchPackage(this, AppLauncher.PKG_SPOTIFY)
        }

        findViewById<View>(R.id.btnMediaPrev).setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        findViewById<View>(R.id.btnMediaPlayPause).setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        findViewById<View>(R.id.btnMediaNext).setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    private fun refreshAutoDriveStatus() {
        val active = AppLauncher.isAccessibilityServiceEnabled(this, AutoDriveService::class.java)
        if (active) {
            viewStatusIndicator.setBackgroundResource(R.drawable.indicator_active)
            tvAutoDriveStatus.setText(R.string.status_active)
            tvAutoDriveStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            viewStatusIndicator.setBackgroundResource(R.drawable.indicator_inactive)
            tvAutoDriveStatus.setText(R.string.status_inactive)
            tvAutoDriveStatus.setTextColor(
                ContextCompat.getColor(this, R.color.accent_fine_vision)
            )
        }
    }

    private fun promptEnableAccessibility() {
        Toast.makeText(this, R.string.msg_enable_accessibility, Toast.LENGTH_LONG).show()
        openAccessibilitySettings()
    }

    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun ensureLocationPermissionAndStart() {
        if (hasLocationPermission()) {
            startGpsUpdates()
        } else {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
            requestLocationPermissions()
        }
    }

    private fun startGpsUpdates() {
        if (!hasLocationPermission()) {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
            return
        }

        val lm = locationManager ?: return
        val hasGpsProvider = try {
            lm.allProviders.contains(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        if (!hasGpsProvider) {
            updateGpsStatus(R.string.gps_state_disabled, isLocked = false)
            return
        }

        val gpsEnabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        if (!gpsEnabled) {
            tvSpeedValue.text = "0"
            updateGpsStatus(R.string.gps_state_disabled, isLocked = false)
            return
        }

        if (isGpsListening) return

        try {
            @Suppress("MissingPermission")
            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown != null && lastKnown.hasSpeed()) {
                tvSpeedValue.text = AppLauncher.mpsToKmhInt(lastKnown.speed).toString()
            }
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_MIN_TIME_MS,
                GPS_MIN_DISTANCE_M,
                gpsListener
            )
            isGpsListening = true
            updateGpsStatus(R.string.gps_state_searching, isLocked = false)
        } catch (_: SecurityException) {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
        } catch (_: IllegalArgumentException) {
            updateGpsStatus(R.string.gps_state_disabled, isLocked = false)
        }
    }

    private fun stopGpsUpdates() {
        if (!isGpsListening) return
        try {
            locationManager?.removeUpdates(gpsListener)
        } catch (_: SecurityException) {
            // Permission revoked while backgrounded.
        } finally {
            isGpsListening = false
        }
    }

    private fun updateGpsStatus(statusResId: Int, isLocked: Boolean) {
        tvGpsStatus.setText(statusResId)
        val colorRes = if (isLocked) R.color.accent_fine_vision else R.color.text_secondary
        tvGpsStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFullscreen() {
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

    companion object {
        private const val GPS_MIN_TIME_MS = 250L
        private const val GPS_MIN_DISTANCE_M = 0f
    }
}
