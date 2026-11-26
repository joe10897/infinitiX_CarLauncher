/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Translucent Fine Vision Liquid Glass voice capture overlay.
 * Triggered from the dashboard VOICE COMMAND button, steering-wheel voice assist key,
 * or system ACTION_ASSIST / ACTION_VOICE_COMMAND intents.
 */
class VoiceTriggerActivity : AppCompatActivity() {

    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvVoiceTranscript: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var toneGenerator: ToneGenerator? = null
    private var isListening = false
    private var isSwcLearning = false
    private var accessibilityPromptShown = false
    private var fallbackAttemptedForSession = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginVoiceSession()
        } else {
            showErrorState(getString(R.string.voice_err_permission))
        }
    }

    private val speechActivityFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val candidates = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            if (candidates.isNotEmpty()) {
                handleRecognitionResults(candidates)
            } else {
                showErrorState(getString(R.string.voice_err_no_match))
            }
        } else if (!isSwcLearning) {
            updateStatus(R.string.voice_status_standby, isAccent = false)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            // Skip confirmation beep here: ToneGenerator can contend with the FYT mic HAL.
            updateStatus(R.string.voice_status_listening, isAccent = true)
            tvVoiceTranscript.setText(R.string.voice_prompt_ready)
        }

        override fun onBeginningOfSpeech() {
            updateStatus(R.string.voice_status_listening, isAccent = true)
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            updateStatus(R.string.voice_status_processing, isAccent = true)
        }

        override fun onError(error: Int) {
            isListening = false
            abandonVoiceAudioFocus()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    showErrorState(getString(R.string.voice_err_permission))
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    showErrorState(getString(R.string.voice_err_no_match))
                else -> {
                    val errorMsg = getString(R.string.voice_err_code, formatSpeechErrorLabel(error))
                    if (shouldTryActivityFallback(error) && launchSpeechActivityFallback()) {
                        tvVoiceTranscript.text = errorMsg
                    } else {
                        showErrorState(errorMsg)
                    }
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!partial.isNullOrEmpty()) {
                tvVoiceTranscript.text = partial
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            abandonVoiceAudioFocus()
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: emptyList()
            handleRecognitionResults(candidates)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_overlay)

        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvVoiceTranscript = findViewById(R.id.tvVoiceTranscript)

        LiquidGlassStyler.applyToVoiceOverlay(findViewById(R.id.panelVoiceRouter))
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        AppLauncher.applyImmersiveFullscreen(window)

        setupListeners()
        ensureAudioPermissionAndListen()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        ensureAudioPermissionAndListen()
    }

    override fun onDestroy() {
        cancelSwcLearning()
        mainHandler.removeCallbacksAndMessages(null)
        abandonVoiceAudioFocus()
        destroyRecognizer()
        releaseToneGenerator()
        super.onDestroy()
    }

    private fun setupListeners() {
        findViewById<View>(R.id.voiceOverlayBackdrop).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.panelVoiceRouter).setOnClickListener {
            // Absorb taps on the glass panel itself so backdrop tap-to-close does not trigger.
        }
        val closeBtn = findViewById<View>(R.id.btnVoiceClose)
        val retryBtn = findViewById<View>(R.id.btnVoiceRetry)
        val bindSwcBtn = findViewById<View>(R.id.btnVoiceBindSwc)
        val assistantBtn = findViewById<View>(R.id.btnVoiceAssistant)

        TactileFeedbackHelper.attach(closeBtn, retryBtn, bindSwcBtn, assistantBtn)

        closeBtn.setOnClickListener {
            finish()
        }
        retryBtn.setOnClickListener {
            ensureAudioPermissionAndListen()
        }
        bindSwcBtn.setOnClickListener {
            toggleSwcLearnMode()
        }
        assistantBtn.setOnClickListener {
            cancelSwcLearning()
            abandonVoiceAudioFocus()
            destroyRecognizer()
            if (!VoiceCommandRouter.launchSystemAssistant(this)) {
                showErrorState(getString(R.string.voice_err_no_recognizer))
            } else {
                finish()
            }
        }
        assistantBtn.setOnLongClickListener {
            openSettingsScreen(Settings.ACTION_VOICE_INPUT_SETTINGS)
            true
        }
    }

    private fun ensureAudioPermissionAndListen() {
        cancelSwcLearning()
        accessibilityPromptShown = false
        fallbackAttemptedForSession = false
        mainHandler.removeCallbacksAndMessages(null)
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            beginVoiceSession()
        } else {
            updateStatus(R.string.voice_status_standby, isAccent = false)
            tvVoiceTranscript.setText(R.string.voice_err_permission)
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginVoiceSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (!launchSpeechActivityFallback()) {
                showErrorState(getString(R.string.voice_err_no_recognizer))
            }
            return
        }

        destroyRecognizer()
        requestDuckAudioFocus()

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            abandonVoiceAudioFocus()
            if (!launchSpeechActivityFallback()) {
                showErrorState(getString(R.string.voice_err_no_recognizer))
            }
            return
        }

        recognizer.setRecognitionListener(recognitionListener)
        speechRecognizer = recognizer

        val recognizerIntent = buildSpeechRecognizerIntent(includePartial = true)

        updateStatus(R.string.voice_status_listening, isAccent = true)
        tvVoiceTranscript.setText(R.string.voice_prompt_ready)
        try {
            recognizer.startListening(recognizerIntent)
        } catch (_: Exception) {
            abandonVoiceAudioFocus()
            if (!launchSpeechActivityFallback()) {
                showErrorState(getString(R.string.voice_err_no_recognizer))
            }
        }
    }

    private fun buildSpeechRecognizerIntent(includePartial: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (includePartial) {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            }
        }

    private fun launchSpeechActivityFallback(): Boolean {
        if (fallbackAttemptedForSession) return false
        fallbackAttemptedForSession = true
        destroyRecognizer()
        abandonVoiceAudioFocus()

        val intent = buildSpeechRecognizerIntent(includePartial = false)
        if (intent.resolveActivity(packageManager) == null) {
            return false
        }
        return try {
            updateStatus(R.string.voice_status_listening, isAccent = true)
            speechActivityFallbackLauncher.launch(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleSwcLearnMode() {
        if (isSwcLearning) {
            cancelSwcLearning()
            updateStatus(R.string.voice_status_standby, isAccent = false)
            tvVoiceTranscript.setText(R.string.voice_prompt_ready)
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        abandonVoiceAudioFocus()

        val service = AutoDriveService.instance
        if (service == null) {
            if (!accessibilityPromptShown) {
                accessibilityPromptShown = true
                showErrorState(getString(R.string.voice_swc_need_accessibility))
            } else {
                openSettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            return
        }

        accessibilityPromptShown = false
        isSwcLearning = true
        updateStatus(R.string.voice_status_standby, isAccent = true)
        tvVoiceTranscript.setText(R.string.voice_swc_waiting)

        service.startSwcLearnMode(
            timeoutMs = AutoDriveService.SWC_LEARN_TIMEOUT_MS,
            onCaptured = { keyCode ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isSwcLearning = false
                    playConfirmationTone(ToneGenerator.TONE_PROP_ACK, 85)
                    updateStatus(R.string.voice_status_executed, isAccent = true)
                    tvVoiceTranscript.text = getString(
                        R.string.voice_swc_bound,
                        SwcKeyStore.formatKeyName(keyCode),
                        keyCode
                    )
                }
            },
            onTimeout = {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isSwcLearning = false
                    showErrorState(getString(R.string.voice_swc_timeout))
                }
            }
        )
    }

    private fun cancelSwcLearning() {
        isSwcLearning = false
        AutoDriveService.instance?.cancelSwcLearnMode()
    }

    private fun openSettingsScreen(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.voice_err_no_recognizer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleRecognitionResults(candidates: List<String>) {
        val action = VoiceCommandRouter.parseCandidates(candidates)
        val bestHeard = candidates.firstOrNull()?.trim().orEmpty()

        if (action is VoiceCommandRouter.VoiceAction.FallbackAssistant) {
            updateStatus(R.string.voice_status_standby, isAccent = false)
            tvVoiceTranscript.text = if (bestHeard.isEmpty()) {
                getString(R.string.voice_err_no_match)
            } else {
                getString(R.string.voice_unrecognized, bestHeard)
            }
            return
        }

        if (bestHeard.isNotEmpty()) {
            tvVoiceTranscript.text = bestHeard
        }
        playConfirmationTone(ToneGenerator.TONE_PROP_ACK, 85)
        val summary = VoiceCommandRouter.execute(this, action)
        tvVoiceStatus.text = summary
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_fine_vision))

        mainHandler.postDelayed({ finish() }, AUTO_CLOSE_DELAY_MS)
    }

    private fun playConfirmationTone(toneType: Int, durationMs: Int) {
        try {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50).also {
                toneGenerator = it
            }
            generator.startTone(toneType, durationMs)
        } catch (_: Exception) {
            // Ignore audio HAL unavailability on emulators or busy vendor streams.
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        } finally {
            toneGenerator = null
        }
    }

    private fun showErrorState(message: String) {
        updateStatus(R.string.voice_status_error, isAccent = false)
        tvVoiceTranscript.text = message
    }

    private fun updateStatus(resId: Int, isAccent: Boolean) {
        tvVoiceStatus.setText(resId)
        val colorRes = if (isAccent) R.color.accent_fine_vision else R.color.text_secondary
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun requestDuckAudioFocus() {
        val am = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonVoiceAudioFocus() {
        val req = audioFocusRequest ?: return
        audioManager?.abandonAudioFocusRequest(req)
        audioFocusRequest = null
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
            // Ignore cleanup errors on vendor speech services.
        } finally {
            speechRecognizer = null
            isListening = false
        }
    }

    companion object {
        private const val AUTO_CLOSE_DELAY_MS = 750L

        fun shouldTryActivityFallback(errorCode: Int): Boolean =
            errorCode == SpeechRecognizer.ERROR_CLIENT ||
                errorCode == SpeechRecognizer.ERROR_AUDIO ||
                errorCode == SpeechRecognizer.ERROR_SERVER

        fun formatSpeechErrorLabel(errorCode: Int): String = when (errorCode) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NET_TIMEOUT #$errorCode"
            SpeechRecognizer.ERROR_NETWORK -> "NET #$errorCode"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO #$errorCode"
            SpeechRecognizer.ERROR_SERVER -> "SERVER #$errorCode"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT #$errorCode"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY #$errorCode"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERM #$errorCode"
            else -> "CODE #$errorCode"
        }

        fun launch(context: Context) {
            val intent = Intent(context, VoiceTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }
}
