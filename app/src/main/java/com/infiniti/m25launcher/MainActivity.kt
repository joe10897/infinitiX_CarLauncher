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
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * Infiniti Y51 Fine Vision Home Launcher optimized for 1280x480 ultra-wide landscape head units.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var layoutAutoDriveStatus: View
    private lateinit var viewStatusIndicator: View
    private lateinit var tvAutoDriveStatus: TextView
    private lateinit var tvClusterDrawerTrigger: View

    private lateinit var layoutGpsHeader: View
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvSpeedValue: AppCompatTextView
    private lateinit var speedBarContainer: View
    private lateinit var viewSpeedBarFill: View
    private lateinit var tvTripTelemetry: TextView

    private lateinit var tvMediaTitle: TextView
    private lateinit var tvMediaSubtitle: TextView
    private lateinit var mediaProgressContainer: View
    private lateinit var viewMediaProgressFill: View
    private lateinit var ivMediaPlayPause: ImageView
    private lateinit var tvMediaPlayPause: TextView

    private val tripTracker = TripTelemetryTracker()
    private var mediaMonitor: MediaSessionMonitor? = null

    private var locationManager: LocationManager? = null
    private var accessibilityManager: AccessibilityManager? = null
    private var isGpsListening = false

    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
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
            val filteredKmh = TripTelemetryTracker.filterSpeedKmh(
                speedMps = location.speed,
                hasSpeed = location.hasSpeed()
            )
            val snapshot = tripTracker.recordSample(filteredKmh, SystemClock.elapsedRealtime())
            renderSpeedTelemetry(snapshot)

            val strongFix = !location.hasAccuracy() || location.accuracy <= GPS_LOCK_ACCURACY_M
            if (strongFix) {
                updateGpsStatus(R.string.gps_state_lock, isLocked = true)
            } else {
                updateGpsStatus(R.string.gps_state_weak, isLocked = false)
            }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                updateGpsStatus(R.string.gps_state_searching, isLocked = false)
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                renderSpeedTelemetry(tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()))
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
        LiquidGlassStyler.applyToDashboard(findViewById(R.id.rootDashboard))
        setupBackNavigationBlock()
        setupListeners()
        applyImmersiveFullscreen()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        mediaMonitor = MediaSessionMonitor(this) { state ->
            renderMediaState(state)
        }

        ensureLocationPermissionAndStart()
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SAVED_PLACES, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SAVED_PLACES)
            window.decorView.post { showSavedPlacesDialog() }
        }
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
        mediaMonitor?.start()
    }

    override fun onPause() {
        mediaMonitor?.stop()
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
        tvClusterDrawerTrigger = findViewById(R.id.tvClusterDrawerTrigger)

        layoutGpsHeader = findViewById(R.id.layoutGpsHeader)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        speedBarContainer = findViewById(R.id.speedBarContainer)
        viewSpeedBarFill = findViewById(R.id.viewSpeedBarFill)
        tvTripTelemetry = findViewById(R.id.tvTripTelemetry)

        tvMediaTitle = findViewById(R.id.tvMediaTitle)
        tvMediaSubtitle = findViewById(R.id.tvMediaSubtitle)
        mediaProgressContainer = findViewById(R.id.mediaProgressContainer)
        viewMediaProgressFill = findViewById(R.id.viewMediaProgressFill)
        ivMediaPlayPause = findViewById(R.id.ivMediaPlayPause)
        tvMediaPlayPause = findViewById(R.id.tvMediaPlayPause)
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

        // Hidden gesture: long-press Y51 // FINE VISION tag to open All-Apps Drawer
        tvClusterDrawerTrigger.setOnLongClickListener {
            showAllAppsDrawerDialog()
            true
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            openSystemSettings()
        }

        layoutGpsHeader.setOnClickListener {
            if (!hasLocationPermission()) {
                requestStartupPermissions()
            }
        }

        layoutGpsHeader.setOnLongClickListener {
            tripTracker.reset()
            renderSpeedTelemetry(tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()))
            Toast.makeText(this, R.string.msg_trip_reset, Toast.LENGTH_SHORT).show()
            true
        }

        val voiceButton = findViewById<View>(R.id.btnVoiceCommand)
        voiceButton.setOnClickListener {
            VoiceTriggerActivity.launch(this)
        }
        voiceButton.setOnLongClickListener {
            showSavedPlacesDialog()
            true
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
            AppLauncher.launchSpeedcam(this)
        }

        findViewById<View>(R.id.btnVehicleInfo).setOnClickListener {
            AppLauncher.launchPackage(
                this,
                primaryPackage = AppLauncher.PKG_CAR_INFO,
                fallbackPackage = AppLauncher.PKG_CANBUS
            )
        }

        val spotifyHeader = findViewById<View>(R.id.btnSpotifyHeader)
        spotifyHeader.setOnClickListener {
            AppLauncher.launchPackage(this, AppLauncher.PKG_SPOTIFY)
        }
        spotifyHeader.setOnLongClickListener {
            openNotificationListenerSettings()
            true
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

    private fun renderSpeedTelemetry(snapshot: TripTelemetryTracker.Snapshot) {
        tvSpeedValue.text = snapshot.currentSpeedKmh.toString()
        val duration = TripTelemetryTracker.formatDuration(snapshot.elapsedMs)
        tvTripTelemetry.text = getString(
            R.string.trip_telemetry_format,
            snapshot.maxSpeedKmh,
            snapshot.avgSpeedKmh,
            duration
        )
        val ratio = TripTelemetryTracker.speedBarRatio(snapshot.currentSpeedKmh)
        updateOpticalBar(speedBarContainer, viewSpeedBarFill, ratio)
    }

    private fun renderMediaState(state: MediaSessionMonitor.NowPlayingState) {
        if (!state.hasNotificationAccess) {
            tvMediaTitle.setText(R.string.media_title)
            tvMediaSubtitle.setText(R.string.media_subtitle_sync_hint)
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play_pause)
            tvMediaPlayPause.setText(R.string.media_play_pause)
            updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, 0f)
            return
        }

        if (!state.hasActiveSession || state.title.isNullOrEmpty()) {
            tvMediaTitle.setText(R.string.media_title)
            tvMediaSubtitle.setText(R.string.media_subtitle_idle)
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play_pause)
            tvMediaPlayPause.setText(R.string.media_play_pause)
            updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, 0f)
            return
        }

        tvMediaTitle.text = state.title
        tvMediaSubtitle.text = state.artist ?: getString(R.string.media_title)
        if (state.isPlaying) {
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_pause)
            tvMediaPlayPause.setText(R.string.media_pause)
        } else {
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play)
            tvMediaPlayPause.setText(R.string.media_play)
        }
        updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, state.progressRatio)
    }

    private fun updateOpticalBar(container: View, fillView: View, ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        val applyWidth = {
            val targetWidth = (container.width * clamped).toInt()
            val lp = fillView.layoutParams
            if (lp.width != targetWidth) {
                lp.width = targetWidth
                fillView.layoutParams = lp
            }
        }
        if (container.width > 0) {
            applyWidth()
        } else {
            container.post { applyWidth() }
        }
    }

    private fun showAllAppsDrawerDialog() {
        val apps = AppLauncher.queryDrawerApps(this)
        val dialog = createGlassDialog(R.layout.dialog_app_drawer, widthFraction = 0.72f)
        dialog.findViewById<View>(R.id.panelAppDrawer)?.let {
            LiquidGlassStyler.applyToAppDrawer(it)
        }
        dialog.findViewById<View>(R.id.btnDrawerClose)?.setOnClickListener {
            dialog.dismiss()
        }

        val grid = dialog.findViewById<GridView>(R.id.gridAppDrawer)
        grid.adapter = AppDrawerAdapter(this, apps)
        grid.setOnItemClickListener { _, _, position, _ ->
            val selected = apps[position]
            dialog.dismiss()
            AppLauncher.launchPackage(this, selected.packageName)
        }
        dialog.show()
    }

    private fun showSavedPlacesDialog() {
        val dialog = createGlassDialog(R.layout.dialog_saved_places, widthFraction = 0.56f)
        dialog.findViewById<View>(R.id.panelSavedPlaces)?.let {
            LiquidGlassStyler.applyToSavedPlacesDialog(it)
        }

        val etHome = dialog.findViewById<EditText>(R.id.etHomeAddress)
        val etWork = dialog.findViewById<EditText>(R.id.etWorkAddress)
        etHome.setText(SavedPlacesStore.getHomeAddress(this).orEmpty())
        etWork.setText(SavedPlacesStore.getWorkAddress(this).orEmpty())

        dialog.findViewById<View>(R.id.btnPlacesCancel)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.btnPlacesSave)?.setOnClickListener {
            SavedPlacesStore.savePlaces(this, etHome.text?.toString(), etWork.text?.toString())
            Toast.makeText(this, R.string.msg_places_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun createGlassDialog(layoutResId: Int, widthFraction: Float): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutResId)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val widthPx = (resources.displayMetrics.widthPixels * widthFraction).toInt()
            setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            applyImmersiveFullscreen()
        }
        return dialog
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

    private fun openNotificationListenerSettings() {
        Toast.makeText(this, R.string.msg_enable_media_sync, Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openSystemSettings()
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.btn_settings, Toast.LENGTH_SHORT).show()
        }
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

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStartupPermissions() {
        val missing = ArrayList<String>(3)
        if (!hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasRecordAudioPermission()) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) {
            startupPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureLocationPermissionAndStart() {
        if (hasLocationPermission()) {
            startGpsUpdates()
            if (!hasRecordAudioPermission()) {
                requestStartupPermissions()
            }
        } else {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
            requestStartupPermissions()
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
            renderSpeedTelemetry(tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()))
            updateGpsStatus(R.string.gps_state_disabled, isLocked = false)
            return
        }

        if (isGpsListening) return

        try {
            @Suppress("MissingPermission")
            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown != null) {
                val filtered = TripTelemetryTracker.filterSpeedKmh(
                    speedMps = lastKnown.speed,
                    hasSpeed = lastKnown.hasSpeed()
                )
                renderSpeedTelemetry(tripTracker.currentSnapshot(filtered, SystemClock.elapsedRealtime()))
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

    private fun applyImmersiveFullscreen() {
        AppLauncher.applyImmersiveFullscreen(window)
    }

    private class AppDrawerAdapter(
        context: Context,
        private val items: List<AppLauncher.DrawerAppItem>
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): AppLauncher.DrawerAppItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_drawer, parent, false).also {
                LiquidGlassStyler.applyToDrawerTile(it)
            }
            val item = items[position]
            view.findViewById<ImageView>(R.id.ivDrawerAppIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.tvDrawerAppLabel).text = item.label
            return view
        }
    }

    companion object {
        const val EXTRA_OPEN_SAVED_PLACES = "extra_open_saved_places"
        private const val GPS_MIN_TIME_MS = 250L
        private const val GPS_MIN_DISTANCE_M = 0f
        private const val GPS_LOCK_ACCURACY_M = 35f
    }
}
