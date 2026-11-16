package com.infiniti.m25launcher

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.widget.BaseAdapter
import android.widget.ListView
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

    private lateinit var btnRearCamera: View
    private lateinit var tvRearCameraSub: TextView

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
        refreshRearCameraSubtitle()
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
        refreshRearCameraSubtitle()
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

        btnRearCamera = findViewById(R.id.btnRearCamera)
        tvRearCameraSub = findViewById(R.id.tvRearCameraSub)
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

        btnRearCamera.setOnClickListener {
            val boundPackage = getBoundRearCameraPackage()
            if (boundPackage != null) {
                AppLauncher.launchPackages(this, listOf(boundPackage))
            } else {
                AppLauncher.launchPackages(this, AppLauncher.REAR_CAMERA_CANDIDATES)
            }
        }

        btnRearCamera.setOnLongClickListener {
            showRearCameraPickerDialog()
            true
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

    private fun getBoundRearCameraPackage(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_REAR_CAM_PACKAGE, null)?.takeIf { it.isNotBlank() }
    }

    private fun setBoundRearCameraPackage(packageName: String?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (packageName.isNullOrBlank()) {
                remove(KEY_REAR_CAM_PACKAGE)
            } else {
                putString(KEY_REAR_CAM_PACKAGE, packageName)
            }
        }.apply()
        refreshRearCameraSubtitle()
    }

    private fun refreshRearCameraSubtitle() {
        val boundPkg = getBoundRearCameraPackage()
        if (boundPkg == null) {
            tvRearCameraSub.setText(R.string.tile_rear_camera_sub)
        } else {
            val label = AppLauncher.getAppLabel(this, boundPkg) ?: boundPkg
            tvRearCameraSub.text = getString(R.string.tile_rear_camera_bound_sub, label)
        }
    }

    private fun showRearCameraPickerDialog() {
        val installedApps = AppLauncher.queryLaunchableApps(this)
        val currentBoundPkg = getBoundRearCameraPackage()

        val pickerItems = ArrayList<AppPickerOption>(installedApps.size + 1)
        pickerItems.add(
            AppPickerOption(
                label = getString(R.string.picker_auto_fallback_title),
                packageName = getString(R.string.picker_auto_fallback_pkg),
                targetPackage = null
            )
        )
        installedApps.forEach { app ->
            pickerItems.add(
                AppPickerOption(
                    label = app.label,
                    packageName = app.packageName,
                    targetPackage = app.packageName
                )
            )
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app_picker)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val widthPx = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialog.findViewById<View>(R.id.btnPickerCancel).setOnClickListener {
            dialog.dismiss()
        }

        val listView = dialog.findViewById<ListView>(R.id.listAppPicker)
        listView.adapter = AppPickerAdapter(this, pickerItems, currentBoundPkg)
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = pickerItems[position]
            setBoundRearCameraPackage(selected.targetPackage)
            if (selected.targetPackage == null) {
                Toast.makeText(this, R.string.msg_rear_cam_reset, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.msg_rear_cam_bound, selected.label),
                    Toast.LENGTH_SHORT
                ).show()
            }
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            applyImmersiveFullscreen()
        }
        dialog.show()
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

    private data class AppPickerOption(
        val label: String,
        val packageName: String,
        val targetPackage: String?
    )

    private class AppPickerAdapter(
        context: Context,
        private val items: List<AppPickerOption>,
        private val selectedPackage: String?
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): AppPickerOption = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_picker, parent, false)
            val option = items[position]
            val isSelected = option.targetPackage == selectedPackage

            val dot = view.findViewById<View>(R.id.viewPickerSelectedDot)
            val labelTv = view.findViewById<TextView>(R.id.tvPickerAppLabel)
            val pkgTv = view.findViewById<TextView>(R.id.tvPickerPackageName)

            dot.setBackgroundResource(
                if (isSelected) R.drawable.indicator_active else R.drawable.indicator_inactive
            )
            labelTv.text = option.label
            labelTv.setTextColor(
                ContextCompat.getColor(
                    view.context,
                    if (isSelected) R.color.accent_fine_vision else R.color.text_primary
                )
            )
            pkgTv.text = option.packageName
            return view
        }
    }

    companion object {
        private const val GPS_MIN_TIME_MS = 250L
        private const val GPS_MIN_DISTANCE_M = 0f
        private const val PREFS_NAME = "y51_launcher_prefs"
        private const val KEY_REAR_CAM_PACKAGE = "rear_cam_package"
    }
}
