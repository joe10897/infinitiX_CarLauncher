package com.infiniti.m25launcher

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
    private lateinit var btnSettings: View

    private lateinit var layoutGpsHeader: View
    private lateinit var tvGpsStatus: TextView
    private lateinit var speedGaugeView: FineVisionSpeedGaugeView
    private lateinit var tvSpeedValue: AppCompatTextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var tvTripTelemetry: TextView

    private lateinit var ivMediaBackdropArt: ImageView
    private lateinit var viewMediaArtScrim: View
    private lateinit var ivMediaAlbumThumb: ImageView
    private lateinit var ivMediaAlbumFallback: ImageView
    private lateinit var tvMediaTitle: TextView
    private lateinit var tvMediaSubtitle: TextView
    private lateinit var mediaProgressContainer: View
    private lateinit var viewMediaProgressFill: View
    private lateinit var tvMediaTimecode: TextView
    private lateinit var ivMediaPlayPause: ImageView
    private lateinit var tvMediaPlayPause: TextView

    private val tripTracker = TripTelemetryTracker()
    private var mediaMonitor: MediaSessionMonitor? = null
    private var mediaPanelGlass: LiquidGlassDrawable? = null
    private var appliedDaytimeTheme: Boolean? = null
    private var lastDisplayedAlbumArt: Bitmap? = null
    private var lastAmbientAccentColor: Int? = null
    private var activeHealthDialog: Dialog? = null

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
        refreshAutoDriveStatus()
        refreshOpenHealthDialogIfNeeded()
    }

    private val accessibilityStateListener =
        AccessibilityManager.AccessibilityStateChangeListener {
            refreshAutoDriveStatus()
            refreshOpenHealthDialogIfNeeded()
        }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val filteredKmh = TripTelemetryTracker.filterSpeedKmh(
                speedMps = location.speed,
                hasSpeed = location.hasSpeed()
            )
            val snapshot = tripTracker.recordSample(filteredKmh, SystemClock.elapsedRealtime())
            renderSpeedTelemetry(snapshot, animate = true)

            val strongFix = !location.hasAccuracy() || location.accuracy <= GPS_LOCK_ACCURACY_M
            if (filteredKmh >= TripTelemetryTracker.COMPASS_MIN_SPEED_KMH && location.hasBearing()) {
                val compass = TripTelemetryTracker.formatCompassHeading(location.bearing)
                val fixTag = if (strongFix) "LOCK" else "WEAK"
                updateGpsStatusText("$compass · $fixTag", isLocked = strongFix)
            } else if (strongFix) {
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
                renderSpeedTelemetry(
                    tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()),
                    animate = false
                )
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
        refreshCockpitThemeIfNeeded()
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
        AppLauncher.setSplitImmersiveMode(this, false)
        refreshCockpitThemeIfNeeded()
        applyImmersiveFullscreen()
        refreshAutoDriveStatus()
        refreshOpenHealthDialogIfNeeded()
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

    override fun onDestroy() {
        activeHealthDialog?.dismiss()
        activeHealthDialog = null
        super.onDestroy()
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
        btnSettings = findViewById(R.id.btnSettings)

        layoutGpsHeader = findViewById(R.id.layoutGpsHeader)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        speedGaugeView = findViewById(R.id.speedGaugeView)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvSpeedUnit = findViewById(R.id.tvSpeedUnit)
        tvTripTelemetry = findViewById(R.id.tvTripTelemetry)

        ivMediaBackdropArt = findViewById(R.id.ivMediaBackdropArt)
        viewMediaArtScrim = findViewById(R.id.viewMediaArtScrim)
        ivMediaAlbumThumb = findViewById(R.id.ivMediaAlbumThumb)
        ivMediaAlbumFallback = findViewById(R.id.ivMediaAlbumFallback)
        tvMediaTitle = findViewById(R.id.tvMediaTitle)
        tvMediaSubtitle = findViewById(R.id.tvMediaSubtitle)
        mediaProgressContainer = findViewById(R.id.mediaProgressContainer)
        viewMediaProgressFill = findViewById(R.id.viewMediaProgressFill)
        tvMediaTimecode = findViewById(R.id.tvMediaTimecode)
        ivMediaPlayPause = findViewById(R.id.ivMediaPlayPause)
        tvMediaPlayPause = findViewById(R.id.tvMediaPlayPause)
    }

    private fun refreshCockpitThemeIfNeeded() {
        val daytime = CockpitThemeMode.isDaytimeNow()
        if (appliedDaytimeTheme == daytime) return
        appliedDaytimeTheme = daytime
        mediaPanelGlass = LiquidGlassStyler.applyToDashboard(
            findViewById(R.id.rootDashboard),
            isDaytime = daytime
        )
        mediaPanelGlass?.setAmbientTintColor(lastAmbientAccentColor)
        speedGaugeView.setDaytimeMode(daytime)
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
        val voiceButton = findViewById<View>(R.id.btnVoiceCommand)
        val routineButton = findViewById<View>(R.id.btnRunSplitRoutine)
        val splitNavButton = findViewById<View>(R.id.btnSplitNav)
        val speedcamButton = findViewById<View>(R.id.btnSpeedcam)
        val vehicleInfoButton = findViewById<View>(R.id.btnVehicleInfo)
        val spotifyHeader = findViewById<View>(R.id.btnSpotifyHeader)
        val prevButton = findViewById<View>(R.id.btnMediaPrev)
        val playPauseButton = findViewById<View>(R.id.btnMediaPlayPause)
        val nextButton = findViewById<View>(R.id.btnMediaNext)

        TactileFeedbackHelper.attach(
            layoutAutoDriveStatus,
            tvClusterDrawerTrigger,
            btnSettings,
            voiceButton,
            routineButton,
            splitNavButton,
            speedcamButton,
            vehicleInfoButton,
            spotifyHeader,
            playPauseButton,
            prevButton,
            nextButton
        )

        layoutAutoDriveStatus.setOnClickListener {
            showSystemHealthDialog()
        }

        // Direct short-tap (and legacy long-press) opens All-Apps Drawer with pinned favorites.
        tvClusterDrawerTrigger.setOnClickListener {
            showAllAppsDrawerDialog()
        }
        tvClusterDrawerTrigger.setOnLongClickListener {
            showAllAppsDrawerDialog()
            true
        }

        btnSettings.setOnClickListener {
            openSystemSettings()
        }

        layoutGpsHeader.setOnClickListener {
            if (!SystemHealthHelper.hasLocationPermission(this)) {
                requestStartupPermissions()
            }
        }

        layoutGpsHeader.setOnLongClickListener {
            tripTracker.reset()
            renderSpeedTelemetry(
                tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()),
                animate = false
            )
            Toast.makeText(this, R.string.msg_trip_reset, Toast.LENGTH_SHORT).show()
            true
        }

        voiceButton.setOnClickListener {
            VoiceTriggerActivity.launch(this)
        }
        voiceButton.setOnLongClickListener {
            showSavedPlacesDialog()
            true
        }

        routineButton.setOnClickListener {
            val service = AutoDriveService.instance
            if (service != null) {
                service.triggerFullRoutineNow()
                Toast.makeText(this, R.string.msg_routine_triggered, Toast.LENGTH_SHORT).show()
            } else {
                promptEnableAccessibility()
            }
        }

        splitNavButton.setOnClickListener {
            val service = AutoDriveService.instance
            if (service != null) {
                service.triggerSplitNavigationNow()
                Toast.makeText(this, R.string.msg_split_triggered, Toast.LENGTH_SHORT).show()
            } else {
                promptEnableAccessibility()
            }
        }

        speedcamButton.setOnClickListener {
            AppLauncher.launchSpeedcam(this)
        }

        vehicleInfoButton.setOnClickListener {
            AppLauncher.launchPackage(
                this,
                primaryPackage = AppLauncher.PKG_CAR_INFO,
                fallbackPackage = AppLauncher.PKG_CANBUS
            )
        }

        spotifyHeader.setOnClickListener {
            AppLauncher.launchPackage(this, AppLauncher.PKG_SPOTIFY)
        }
        spotifyHeader.setOnLongClickListener {
            openNotificationListenerSettings()
            true
        }

        prevButton.setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        playPauseButton.setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        nextButton.setOnClickListener {
            AppLauncher.dispatchMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    private fun renderSpeedTelemetry(
        snapshot: TripTelemetryTracker.Snapshot,
        animate: Boolean = true
    ) {
        val overspeed = TripTelemetryTracker.isOverspeed(snapshot.currentSpeedKmh)
        val speedTextColor = if (overspeed) {
            Color.parseColor("#FFD999")
        } else {
            ContextCompat.getColor(this, R.color.text_primary)
        }
        val unitColor = ContextCompat.getColor(
            this,
            if (overspeed) R.color.accent_amber_warn else R.color.accent_fine_vision
        )
        tvSpeedValue.setTextColor(speedTextColor)
        tvSpeedUnit.setTextColor(unitColor)

        speedGaugeView.setSpeedKmh(snapshot.currentSpeedKmh, animate = animate) { displayed ->
            tvSpeedValue.text = displayed.toString()
        }

        val duration = TripTelemetryTracker.formatDuration(snapshot.elapsedMs)
        tvTripTelemetry.text = getString(
            R.string.trip_telemetry_format,
            snapshot.maxSpeedKmh,
            snapshot.avgSpeedKmh,
            duration
        )
    }

    private fun renderMediaState(state: MediaSessionMonitor.NowPlayingState) {
        if (!state.hasNotificationAccess) {
            tvMediaTitle.setText(R.string.media_title)
            tvMediaSubtitle.setText(R.string.media_subtitle_sync_hint)
            tvMediaTimecode.setText(R.string.media_timecode_idle)
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play_pause)
            tvMediaPlayPause.setText(R.string.media_play_pause)
            applyMediaArtwork(albumArt = null, accentRgb = null)
            updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, 0f)
            return
        }

        if (!state.hasActiveSession || state.title.isNullOrEmpty()) {
            tvMediaTitle.setText(R.string.media_title)
            tvMediaSubtitle.setText(R.string.media_subtitle_idle)
            tvMediaTimecode.setText(R.string.media_timecode_idle)
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play_pause)
            tvMediaPlayPause.setText(R.string.media_play_pause)
            applyMediaArtwork(albumArt = null, accentRgb = null)
            updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, 0f)
            return
        }

        tvMediaTitle.text = state.title
        tvMediaSubtitle.text = state.artist ?: getString(R.string.media_title)
        tvMediaTimecode.text = MediaSessionMonitor.formatTrackTimecode(
            state.positionMs,
            state.durationMs
        )
        if (state.isPlaying) {
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_pause)
            tvMediaPlayPause.setText(R.string.media_pause)
        } else {
            ivMediaPlayPause.setImageResource(R.drawable.ic_media_play)
            tvMediaPlayPause.setText(R.string.media_play)
        }
        applyMediaArtwork(state.albumArt, state.albumAccentColor)
        updateOpticalBar(mediaProgressContainer, viewMediaProgressFill, state.progressRatio)
    }

    private fun applyMediaArtwork(albumArt: Bitmap?, accentRgb: Int?) {
        if (lastAmbientAccentColor != accentRgb) {
            lastAmbientAccentColor = accentRgb
            mediaPanelGlass?.setAmbientTintColor(accentRgb)
            val barColor = if (accentRgb != null) {
                (0xFF shl 24) or accentRgb
            } else {
                ContextCompat.getColor(this, R.color.accent_fine_vision)
            }
            viewMediaProgressFill.setBackgroundColor(barColor)
        }

        if (albumArt === lastDisplayedAlbumArt) return
        lastDisplayedAlbumArt = albumArt

        if (albumArt != null && !albumArt.isRecycled) {
            ivMediaBackdropArt.setImageBitmap(albumArt)
            ivMediaBackdropArt.visibility = View.VISIBLE
            viewMediaArtScrim.visibility = View.VISIBLE
            ivMediaAlbumThumb.setImageBitmap(albumArt)
            ivMediaAlbumThumb.visibility = View.VISIBLE
            ivMediaAlbumFallback.visibility = View.GONE
        } else {
            ivMediaBackdropArt.setImageDrawable(null)
            ivMediaBackdropArt.visibility = View.GONE
            viewMediaArtScrim.visibility = View.GONE
            ivMediaAlbumThumb.setImageDrawable(null)
            ivMediaAlbumThumb.visibility = View.GONE
            ivMediaAlbumFallback.visibility = View.VISIBLE
        }
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

    private fun showSystemHealthDialog() {
        activeHealthDialog?.dismiss()
        val dialog = createGlassDialog(R.layout.dialog_system_health, widthFraction = 0.62f)
        dialog.findViewById<View>(R.id.panelSystemHealth)?.let {
            LiquidGlassStyler.applyToSystemHealthDialog(it)
        }
        dialog.findViewById<TextView>(R.id.tvHealthVersion)?.text =
            SystemHealthHelper.formatVersionLabel()

        val closeBtn = dialog.findViewById<View>(R.id.btnHealthClose)
        val rowHome = dialog.findViewById<View>(R.id.rowHealthHome)
        val rowAccess = dialog.findViewById<View>(R.id.rowHealthAccessibility)
        val rowMedia = dialog.findViewById<View>(R.id.rowHealthMediaSync)
        val rowPerms = dialog.findViewById<View>(R.id.rowHealthPermissions)
        val rowBattery = dialog.findViewById<View>(R.id.rowHealthBattery)
        val rowRemoteNav = dialog.findViewById<View>(R.id.rowHealthRemoteNav)

        TactileFeedbackHelper.attach(
            closeBtn,
            rowHome,
            rowAccess,
            rowMedia,
            rowPerms,
            rowBattery,
            rowRemoteNav
        )

        closeBtn.setOnClickListener { dialog.dismiss() }
        rowHome.setOnClickListener { SystemHealthHelper.openDefaultHomeSettings(this) }
        rowAccess.setOnClickListener { openAccessibilitySettings() }
        rowMedia.setOnClickListener { openNotificationListenerSettings() }
        rowPerms.setOnClickListener {
            if (!SystemHealthHelper.hasLocationPermission(this) ||
                !SystemHealthHelper.hasRecordAudioPermission(this)
            ) {
                requestStartupPermissions()
            } else {
                SystemHealthHelper.openAppDetailsSettings(this)
            }
        }
        rowBattery.setOnClickListener { SystemHealthHelper.requestIgnoreBatteryOptimizations(this) }
        rowRemoteNav.setOnClickListener {
            dialog.dismiss()
            showRemoteNavPairingDialog()
        }

        dialog.setOnDismissListener {
            if (activeHealthDialog === dialog) {
                activeHealthDialog = null
            }
            applyImmersiveFullscreen()
        }

        activeHealthDialog = dialog
        bindSystemHealthSnapshot(dialog, SystemHealthHelper.collect(this))
        dialog.show()
    }

    private fun refreshOpenHealthDialogIfNeeded() {
        val dialog = activeHealthDialog ?: return
        if (!dialog.isShowing) return
        bindSystemHealthSnapshot(dialog, SystemHealthHelper.collect(this))
    }

    private fun bindSystemHealthSnapshot(
        dialog: Dialog,
        snapshot: SystemHealthHelper.HealthSnapshot
    ) {
        bindHealthRow(dialog, R.id.dotHealthHome, R.id.badgeHealthHome, snapshot.isDefaultHome)
        bindHealthRow(
            dialog,
            R.id.dotHealthAccessibility,
            R.id.badgeHealthAccessibility,
            snapshot.isAutoDriveEnabled
        )
        bindHealthRow(
            dialog,
            R.id.dotHealthMediaSync,
            R.id.badgeHealthMediaSync,
            snapshot.isMediaSyncEnabled
        )
        bindHealthRow(
            dialog,
            R.id.dotHealthPermissions,
            R.id.badgeHealthPermissions,
            snapshot.hasLocationAndMic
        )
        bindHealthRow(
            dialog,
            R.id.dotHealthBattery,
            R.id.badgeHealthBattery,
            snapshot.isBatteryUnrestricted
        )

        val remoteEnabled = RemoteNavStore.isEnabled(this)
        val pairingCode = RemoteNavStore.getOrCreatePairingCode(this)
        val remoteActive = remoteEnabled && snapshot.isAutoDriveEnabled
        dialog.findViewById<View>(R.id.dotHealthRemoteNav)?.setBackgroundResource(
            if (remoteActive) R.drawable.indicator_ready else R.drawable.indicator_warn
        )
        dialog.findViewById<TextView>(R.id.tvHealthRemoteNavSub)?.text = pairingCode
        dialog.findViewById<TextView>(R.id.badgeHealthRemoteNav)?.apply {
            setText(if (remoteEnabled) R.string.remote_nav_badge_on else R.string.remote_nav_badge_off)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (remoteActive) R.color.accent_ready_green else R.color.accent_amber_warn
                )
            )
        }
    }

    private fun showRemoteNavPairingDialog() {
        val dialog = createGlassDialog(R.layout.dialog_remote_nav, widthFraction = 0.58f)
        dialog.findViewById<View>(R.id.panelRemoteNavDialog)?.let {
            LiquidGlassStyler.applyToRemoteNavDialog(it)
        }

        val tvCode = dialog.findViewById<TextView>(R.id.tvRemoteNavPairingCode)
        val tvState = dialog.findViewById<TextView>(R.id.tvRemoteNavStateHint)
        val closeBtn = dialog.findViewById<View>(R.id.btnRemoteNavDialogClose)
        val toggleBtn = dialog.findViewById<TextView>(R.id.btnRemoteNavToggle)
        val regenBtn = dialog.findViewById<View>(R.id.btnRemoteNavRegenerate)
        val testBtn = dialog.findViewById<View>(R.id.btnRemoteNavTest)

        TactileFeedbackHelper.attach(closeBtn, toggleBtn, regenBtn, testBtn)

        fun renderPairingState() {
            val enabled = RemoteNavStore.isEnabled(this)
            val serviceConnected = AutoDriveService.instance != null
            tvCode.text = RemoteNavStore.getOrCreatePairingCode(this)
            toggleBtn.setText(
                if (enabled) R.string.remote_nav_toggle_disable else R.string.remote_nav_toggle_enable
            )
            val stateRes = when {
                !enabled -> R.string.remote_nav_state_disabled
                serviceConnected -> R.string.remote_nav_state_listening
                else -> R.string.remote_nav_state_need_service
            }
            tvState.setText(stateRes)
            tvState.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (enabled && serviceConnected) R.color.accent_ready_green else R.color.accent_amber_warn
                )
            )
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        toggleBtn.setOnClickListener {
            val next = !RemoteNavStore.isEnabled(this)
            RemoteNavStore.setEnabled(this, next)
            AutoDriveService.instance?.syncRemoteNavClient(forceRestart = true)
            renderPairingState()
        }
        regenBtn.setOnClickListener {
            RemoteNavStore.regeneratePairingCode(this)
            AutoDriveService.instance?.syncRemoteNavClient(forceRestart = true)
            renderPairingState()
            Toast.makeText(this, R.string.remote_nav_msg_regenerated, Toast.LENGTH_SHORT).show()
        }
        testBtn.setOnClickListener {
            dialog.dismiss()
            RemoteNavLaunchActivity.launch(
                context = this,
                displayLabel = "台北101（本機模擬測試）",
                navigationQuery = "25.033964,121.564472"
            )
        }

        renderPairingState()
        dialog.show()
    }

    private fun bindHealthRow(dialog: Dialog, dotId: Int, badgeId: Int, ready: Boolean) {
        dialog.findViewById<View>(dotId)?.setBackgroundResource(
            if (ready) R.drawable.indicator_ready else R.drawable.indicator_warn
        )
        dialog.findViewById<TextView>(badgeId)?.apply {
            setText(if (ready) R.string.health_badge_ready else R.string.health_badge_action)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (ready) R.color.accent_ready_green else R.color.accent_amber_warn
                )
            )
        }
    }

    private fun showAllAppsDrawerDialog() {
        val rawApps = AppLauncher.queryDrawerApps(this)
        val dialog = createGlassDialog(R.layout.dialog_app_drawer, widthFraction = 0.74f)
        dialog.findViewById<View>(R.id.panelAppDrawer)?.let {
            LiquidGlassStyler.applyToAppDrawer(it)
        }
        dialog.findViewById<TextView>(R.id.tvDrawerVersion)?.text =
            SystemHealthHelper.formatVersionLabel()

        val closeBtn = dialog.findViewById<View>(R.id.btnDrawerClose)
        TactileFeedbackHelper.attach(closeBtn)
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        val grid = dialog.findViewById<GridView>(R.id.gridAppDrawer)
        val adapter = AppDrawerAdapter(
            context = this,
            initialItems = sortDrawerApps(rawApps),
            initialPinned = PinnedAppsStore.getPinnedPackages(this).toSet()
        )
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            dialog.dismiss()
            AppLauncher.launchPackage(this, selected.packageName)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            val nowPinned = PinnedAppsStore.togglePin(this, selected.packageName)
            val updatedPinned = PinnedAppsStore.getPinnedPackages(this)
            adapter.updateData(
                newItems = PinnedAppsStore.sortWithPinnedFirst(rawApps, updatedPinned) {
                    it.packageName
                },
                newPinned = updatedPinned.toSet()
            )
            val toastRes = if (nowPinned) R.string.msg_app_pinned else R.string.msg_app_unpinned
            Toast.makeText(this, getString(toastRes, selected.label), Toast.LENGTH_SHORT).show()
            true
        }
        dialog.show()
    }

    private fun sortDrawerApps(apps: List<AppLauncher.DrawerAppItem>): List<AppLauncher.DrawerAppItem> {
        val pinned = PinnedAppsStore.getPinnedPackages(this)
        return PinnedAppsStore.sortWithPinnedFirst(apps, pinned) { it.packageName }
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

        val cancelBtn = dialog.findViewById<View>(R.id.btnPlacesCancel)
        val saveBtn = dialog.findViewById<View>(R.id.btnPlacesSave)
        TactileFeedbackHelper.attach(cancelBtn, saveBtn)

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        saveBtn.setOnClickListener {
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
        val snapshot = SystemHealthHelper.collect(this)
        val indicatorRes = when {
            snapshot.isAllReady -> R.drawable.indicator_ready
            snapshot.isAutoDriveEnabled -> R.drawable.indicator_active
            else -> R.drawable.indicator_warn
        }
        viewStatusIndicator.setBackgroundResource(indicatorRes)

        if (snapshot.isAutoDriveEnabled) {
            tvAutoDriveStatus.text = getString(
                R.string.status_active,
                snapshot.readyCount,
                snapshot.totalChecks
            )
            tvAutoDriveStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            tvAutoDriveStatus.text = getString(
                R.string.status_inactive,
                snapshot.readyCount,
                snapshot.totalChecks
            )
            tvAutoDriveStatus.setTextColor(
                ContextCompat.getColor(this, R.color.accent_amber_warn)
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

    private fun requestStartupPermissions() {
        val missing = ArrayList<String>(3)
        if (!SystemHealthHelper.hasLocationPermission(this)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!SystemHealthHelper.hasRecordAudioPermission(this)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) {
            startupPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureLocationPermissionAndStart() {
        if (SystemHealthHelper.hasLocationPermission(this)) {
            startGpsUpdates()
            if (!SystemHealthHelper.hasRecordAudioPermission(this)) {
                requestStartupPermissions()
            }
        } else {
            updateGpsStatus(R.string.gps_state_no_perm, isLocked = false)
            requestStartupPermissions()
        }
    }

    private fun startGpsUpdates() {
        if (!SystemHealthHelper.hasLocationPermission(this)) {
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
            renderSpeedTelemetry(
                tripTracker.currentSnapshot(0, SystemClock.elapsedRealtime()),
                animate = false
            )
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
                renderSpeedTelemetry(
                    tripTracker.currentSnapshot(filtered, SystemClock.elapsedRealtime()),
                    animate = false
                )
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
        updateGpsStatusText(getString(statusResId), isLocked)
    }

    private fun updateGpsStatusText(statusText: String, isLocked: Boolean) {
        tvGpsStatus.text = statusText
        val colorRes = if (isLocked) R.color.accent_fine_vision else R.color.text_secondary
        tvGpsStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun applyImmersiveFullscreen() {
        AppLauncher.applyImmersiveFullscreen(window)
    }

    private class AppDrawerAdapter(
        context: Context,
        initialItems: List<AppLauncher.DrawerAppItem>,
        initialPinned: Set<String>
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)
        private var items: List<AppLauncher.DrawerAppItem> = initialItems
        private var pinnedPackages: Set<String> = initialPinned

        fun updateData(
            newItems: List<AppLauncher.DrawerAppItem>,
            newPinned: Set<String>
        ) {
            items = newItems
            pinnedPackages = newPinned
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): AppLauncher.DrawerAppItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_app_drawer, parent, false)
            val item = items[position]
            val isPinned = item.packageName in pinnedPackages
            val previousPinTag = view.getTag(R.id.tileDrawerApp) as? Boolean
            if (previousPinTag != isPinned) {
                LiquidGlassStyler.applyToDrawerTile(view, isPinned = isPinned)
                view.setTag(R.id.tileDrawerApp, isPinned)
            }
            view.findViewById<View>(R.id.viewDrawerPinBadge).visibility =
                if (isPinned) View.VISIBLE else View.GONE
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
