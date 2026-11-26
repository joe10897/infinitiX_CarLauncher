/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Translucent Fine Vision Liquid Glass 3-second confirmation HUD shown whenever a destination
 * arrives from the Y51 Send phone companion app. Auto-launches Maps + Spotify split navigation
 * unless cancelled by the driver.
 */
class RemoteNavLaunchActivity : AppCompatActivity() {

    private lateinit var tvDestinationLabel: TextView
    private lateinit var tvDestinationQuery: TextView
    private lateinit var tvCountdownBadge: TextView
    private lateinit var countdownBarContainer: View
    private lateinit var viewCountdownBarFill: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressAnimator: ValueAnimator? = null
    private var toneGenerator: ToneGenerator? = null
    private var navQuery: String = ""
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_nav_overlay)

        tvDestinationLabel = findViewById(R.id.tvRemoteNavDestination)
        tvDestinationQuery = findViewById(R.id.tvRemoteNavQuery)
        tvCountdownBadge = findViewById(R.id.tvRemoteNavCountdown)
        countdownBarContainer = findViewById(R.id.remoteNavBarContainer)
        viewCountdownBarFill = findViewById(R.id.viewRemoteNavBarFill)

        LiquidGlassStyler.applyToRemoteNavOverlay(findViewById(R.id.panelRemoteNavOverlay))
        AppLauncher.applyImmersiveFullscreen(window)

        val goBtn = findViewById<View>(R.id.btnRemoteNavGoNow)
        val cancelBtn = findViewById<View>(R.id.btnRemoteNavCancel)
        TactileFeedbackHelper.attach(goBtn, cancelBtn)

        findViewById<View>(R.id.remoteNavBackdrop).setOnClickListener { cancelAndFinish() }
        findViewById<View>(R.id.panelRemoteNavOverlay).setOnClickListener { /* Absorb panel taps */ }
        cancelBtn.setOnClickListener { cancelAndFinish() }
        goBtn.setOnClickListener { executeNavigationNow() }

        bindAndStartCountdown(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            bindAndStartCountdown(intent)
        }
    }

    override fun onDestroy() {
        stopTimers()
        releaseToneGenerator()
        super.onDestroy()
    }

    private fun bindAndStartCountdown(intent: Intent) {
        stopTimers()
        launched = false

        val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) {
            finish()
            return
        }

        navQuery = query
        tvDestinationLabel.text = if (label.isNotEmpty()) label else query
        tvDestinationQuery.text = query
        playChime()

        tvCountdownBadge.text = getString(R.string.remote_nav_countdown_fmt, 3)
        mainHandler.postDelayed({
            if (!launched) tvCountdownBadge.text = getString(R.string.remote_nav_countdown_fmt, 2)
        }, 1000L)
        mainHandler.postDelayed({
            if (!launched) tvCountdownBadge.text = getString(R.string.remote_nav_countdown_fmt, 1)
        }, 2000L)
        mainHandler.postDelayed({
            executeNavigationNow()
        }, COUNTDOWN_MS)

        countdownBarContainer.post {
            val fullWidth = countdownBarContainer.width
            if (fullWidth <= 0) return@post
            progressAnimator = ValueAnimator.ofInt(fullWidth, 0).apply {
                duration = COUNTDOWN_MS
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val lp = viewCountdownBarFill.layoutParams
                    lp.width = anim.animatedValue as Int
                    viewCountdownBarFill.layoutParams = lp
                }
                start()
            }
        }
    }

    private fun executeNavigationNow() {
        if (launched || navQuery.isEmpty()) return
        launched = true
        stopTimers()

        val service = AutoDriveService.instance
        if (service != null) {
            service.triggerNavigationWithSplit(navQuery)
        } else {
            VoiceCommandRouter.launchGoogleMapsNavigation(this, navQuery)
        }
        finish()
    }

    private fun cancelAndFinish() {
        launched = true
        stopTimers()
        finish()
    }

    private fun stopTimers() {
        mainHandler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        progressAnimator = null
    }

    private fun playChime() {
        try {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).also {
                toneGenerator = it
            }
            generator.startTone(ToneGenerator.TONE_PROP_ACK, 90)
        } catch (_: Exception) {
            // Ignore audio HAL unavailability.
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
            // Ignore cleanup failures.
        } finally {
            toneGenerator = null
        }
    }

    companion object {
        private const val EXTRA_LABEL = "extra_remote_nav_label"
        private const val EXTRA_QUERY = "extra_remote_nav_query"
        private const val COUNTDOWN_MS = 3000L

        fun launch(context: Context, displayLabel: String, navigationQuery: String) {
            val intent = Intent(context, RemoteNavLaunchActivity::class.java).apply {
                putExtra(EXTRA_LABEL, displayLabel)
                putExtra(EXTRA_QUERY, navigationQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }
}
