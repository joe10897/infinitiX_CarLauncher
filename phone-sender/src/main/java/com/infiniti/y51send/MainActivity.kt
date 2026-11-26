/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.y51send

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Pairing and manual-send screen for Y51 Send. Contains zero background services;
 * only transmits on explicit user action.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvPairingStatus: TextView
    private lateinit var etPairingCode: EditText
    private lateinit var etDestinationInput: EditText
    private lateinit var tvSendFeedback: TextView
    private lateinit var btnSendToCar: TextView

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPairingStatus = findViewById(R.id.tvPairingStatus)
        etPairingCode = findViewById(R.id.etPairingCode)
        etDestinationInput = findViewById(R.id.etDestinationInput)
        tvSendFeedback = findViewById(R.id.tvSendFeedback)
        btnSendToCar = findViewById(R.id.btnSendToCar)

        findViewById<TextView>(R.id.tvAppVersion).text =
            "Y51 Send v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_STAMP}"

        refreshPairingStatus()
        applyPrefillFromIntent(intent)

        findViewById<View>(R.id.btnSavePairing).setOnClickListener {
            savePairingCode()
        }
        findViewById<View>(R.id.btnPasteClipboard).setOnClickListener {
            pasteFromClipboard()
        }
        btnSendToCar.setOnClickListener {
            sendCurrentDestination()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            applyPrefillFromIntent(intent)
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applyPrefillFromIntent(intent: Intent?) {
        val prefill = intent?.getStringExtra(EXTRA_PREFILL_DESTINATION)?.trim().orEmpty()
        if (prefill.isNotEmpty()) {
            etDestinationInput.setText(prefill)
            intent?.removeExtra(EXTRA_PREFILL_DESTINATION)
        }
    }

    private fun refreshPairingStatus() {
        val saved = RemoteNavSender.getPairingCode(this)
        if (saved != null) {
            etPairingCode.setText(saved)
            tvPairingStatus.text = getString(R.string.pairing_saved_status, saved)
            tvPairingStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_ready_green))
        } else {
            tvPairingStatus.setText(R.string.pairing_missing_status)
            tvPairingStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber_warn))
        }

        val lastSent = RemoteNavSender.getLastSent(this)
        if (lastSent != null && tvSendFeedback.visibility != View.VISIBLE) {
            tvSendFeedback.text = getString(R.string.last_sent_fmt, lastSent)
            tvSendFeedback.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvSendFeedback.visibility = View.VISIBLE
        }
    }

    private fun savePairingCode() {
        val raw = etPairingCode.text?.toString().orEmpty()
        val saved = RemoteNavSender.savePairingCode(this, raw)
        if (saved == null) {
            Toast.makeText(this, R.string.msg_pairing_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        refreshPairingStatus()
        Toast.makeText(this, getString(R.string.msg_pairing_saved, saved), Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.msg_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        etDestinationInput.setText(text)
    }

    private fun sendCurrentDestination() {
        val typedCode = etPairingCode.text?.toString().orEmpty()
        val pairingCode = RemoteNavSender.getPairingCode(this)
            ?: RemoteNavSender.savePairingCode(this, typedCode)
        if (pairingCode == null) {
            Toast.makeText(this, R.string.msg_need_pairing, Toast.LENGTH_SHORT).show()
            return
        }
        refreshPairingStatus()

        val destination = etDestinationInput.text?.toString()?.trim().orEmpty()
        if (destination.isEmpty()) {
            Toast.makeText(this, R.string.msg_need_destination, Toast.LENGTH_SHORT).show()
            return
        }

        btnSendToCar.isEnabled = false
        showFeedback(getString(R.string.status_sending), R.color.accent_fine_vision)

        ioExecutor.execute {
            val result = RemoteNavSender.sendBlocking(pairingCode, destination)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnSendToCar.isEnabled = true
                when (result) {
                    RemoteNavSender.SendResult.Success -> {
                        RemoteNavSender.saveLastSent(this, destination)
                        showFeedback(getString(R.string.status_sent_ok), R.color.accent_ready_green)
                    }
                    is RemoteNavSender.SendResult.Failure -> {
                        showFeedback(
                            getString(R.string.status_sent_fail, result.reason),
                            R.color.accent_amber_warn
                        )
                    }
                }
            }
        }
    }

    private fun showFeedback(message: String, colorRes: Int) {
        tvSendFeedback.text = message
        tvSendFeedback.setTextColor(ContextCompat.getColor(this, colorRes))
        tvSendFeedback.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_PREFILL_DESTINATION = "extra_prefill_destination"
    }
}
