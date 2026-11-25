package com.infiniti.y51send

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Translucent Share Sheet target invoked directly from Google Maps, LINE, Chrome, or geo: links.
 * Transmits a single HTTPS POST and closes immediately so zero background process stays alive.
 */
class ShareTargetActivity : AppCompatActivity() {

    private lateinit var tvShareStatus: TextView
    private lateinit var tvSharePreview: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = RemoteNavSender.extractSharedPayload(intent)
        if (payload.isNullOrBlank()) {
            finish()
            return
        }

        val pairingCode = RemoteNavSender.getPairingCode(this)
        if (pairingCode == null) {
            Toast.makeText(this, R.string.msg_need_pairing, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PREFILL_DESTINATION, payload)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            finish()
            return
        }

        setContentView(R.layout.activity_share_overlay)
        tvShareStatus = findViewById(R.id.tvShareStatus)
        tvSharePreview = findViewById(R.id.tvSharePreview)
        tvSharePreview.text = payload

        ioExecutor.execute {
            val result = RemoteNavSender.sendBlocking(pairingCode, payload)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    RemoteNavSender.SendResult.Success -> {
                        RemoteNavSender.saveLastSent(this, payload)
                        tvShareStatus.setText(R.string.status_sent_ok)
                        tvShareStatus.setTextColor(
                            ContextCompat.getColor(this, R.color.accent_ready_green)
                        )
                        mainHandler.postDelayed({ finish() }, AUTO_CLOSE_SUCCESS_MS)
                    }
                    is RemoteNavSender.SendResult.Failure -> {
                        tvShareStatus.text = getString(R.string.status_sent_fail, result.reason)
                        tvShareStatus.setTextColor(
                            ContextCompat.getColor(this, R.color.accent_amber_warn)
                        )
                        mainHandler.postDelayed({ finish() }, AUTO_CLOSE_ERROR_MS)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val AUTO_CLOSE_SUCCESS_MS = 950L
        private const val AUTO_CLOSE_ERROR_MS = 2200L
    }
}
