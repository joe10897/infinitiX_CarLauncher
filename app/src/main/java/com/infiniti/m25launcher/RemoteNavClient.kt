package com.infiniti.m25launcher

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight car-side SSE subscriber that listens to the vehicle's private pairing channel
 * while the screen is awake and cleanly disconnects on ACC/screen-off.
 */
class RemoteNavClient(
    private val context: Context,
    private val onDestinationReceived: (RemoteNavParser.ParsedDestination) -> Unit
) {

    private val running = AtomicBoolean(false)
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var workerThread: Thread? = null

    fun start() {
        if (!RemoteNavStore.isEnabled(context)) {
            Log.i(TAG, "RemoteNavClient disabled by user preference")
            return
        }
        if (!running.compareAndSet(false, true)) return

        val code = RemoteNavStore.getOrCreatePairingCode(context)
        val sseUrl = RemoteNavStore.buildSseUrl(code)
        Log.i(TAG, "Starting RemoteNavClient SSE listener")

        val thread = Thread({ runLoop(sseUrl) }, "Y51-RemoteNav-SSE").apply {
            isDaemon = true
        }
        workerThread = thread
        thread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping RemoteNavClient SSE listener")
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
            // Ignore socket close races on shutdown.
        }
        activeConnection = null
        workerThread?.interrupt()
        workerThread = null
    }

    fun restart() {
        stop()
        start()
    }

    private fun runLoop(sseUrl: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(sseUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                activeConnection = conn

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "SSE HTTP status $code; retrying in ${backoffMs}ms")
                    sleepInterruptibly(backoffMs)
                    backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                backoffMs = INITIAL_BACKOFF_MS
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        handleSseLine(line)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.d(TAG, "SSE stream interrupted (${e.javaClass.simpleName}); reconnecting in ${backoffMs}ms")
                    sleepInterruptibly(backoffMs)
                    backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                    // Ignore disconnect errors.
                }
                if (activeConnection === conn) {
                    activeConnection = null
                }
            }
        }
    }

    private fun handleSseLine(line: String) {
        val incoming = RemoteNavParser.parseSseEventLine(line) ?: return
        val nowSec = System.currentTimeMillis() / 1000L
        if (!RemoteNavParser.isFreshMessage(incoming.timeEpochSec, nowSec)) {
            Log.d(TAG, "Ignoring stale remote nav message id=${incoming.id}")
            return
        }
        if (RemoteNavStore.isMessageHandled(context, incoming.id)) {
            return
        }
        RemoteNavStore.markMessageHandled(context, incoming.id)

        val rawUrl = RemoteNavParser.extractFirstUrl(incoming.rawPayload)
        val resolvedUrl = if (rawUrl != null && RemoteNavParser.isShortMapUrl(rawUrl)) {
            RemoteNavParser.resolveShortUrl(rawUrl)
        } else {
            rawUrl
        }
        val parsed = RemoteNavParser.extractNavigationTarget(incoming.rawPayload, resolvedUrl) ?: return
        Log.i(TAG, "Accepted remote destination '${parsed.displayLabel}' -> '${parsed.navigationQuery}'")
        onDestinationReceived(parsed)
    }

    private fun sleepInterruptibly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "Y51RemoteNav"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 65_000
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 25_000L
    }
}
