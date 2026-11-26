/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide singleton SSE client for Phone-to-Car Remote Navigation.
 * Runs whenever either [AutoDriveService] is connected or [MainActivity] is active,
 * disconnects cleanly on ACC/screen-off, reconnects immediately when Wi-Fi/hotspot becomes
 * available, and reports live diagnostic status to the pairing dialog.
 */
object RemoteNavClient {

    enum class State {
        STOPPED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class StatusSnapshot(
        val state: State,
        val detail: String = "",
        val lastDestination: String? = null
    )

    private const val TAG = "Y51RemoteNav"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 70_000
    private const val INITIAL_BACKOFF_MS = 2_500L
    private const val MAX_BACKOFF_MS = 18_000L

    private val running = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArrayList<(StatusSnapshot) -> Unit>()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var serverReferenceTimeSec: Long? = null
    @Volatile
    private var onDestinationCallback: ((RemoteNavParser.ParsedDestination) -> Unit)? = null
    @Volatile
    var currentStatus: StatusSnapshot = StatusSnapshot(State.STOPPED)
        private set

    fun addStatusListener(listener: (StatusSnapshot) -> Unit) {
        statusListeners.addIfAbsent(listener)
        listener(currentStatus)
    }

    fun removeStatusListener(listener: (StatusSnapshot) -> Unit) {
        statusListeners.remove(listener)
    }

    fun ensureStarted(
        context: Context,
        onDestination: (RemoteNavParser.ParsedDestination) -> Unit
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        onDestinationCallback = onDestination

        if (!RemoteNavStore.isEnabled(appCtx)) {
            stop()
            return
        }
        if (!running.compareAndSet(false, true)) {
            return
        }

        registerNetworkObserver(appCtx)
        launchWorker(appCtx)
    }

    fun restart(
        context: Context,
        onDestination: ((RemoteNavParser.ParsedDestination) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (onDestination != null) {
            onDestinationCallback = onDestination
        }
        stop()
        if (!RemoteNavStore.isEnabled(appCtx)) return
        if (running.compareAndSet(false, true)) {
            registerNetworkObserver(appCtx)
            launchWorker(appCtx)
        }
    }

    fun stop() {
        unregisterNetworkObserver()
        if (!running.compareAndSet(true, false)) {
            updateStatus(State.STOPPED, "")
            return
        }
        Log.i(TAG, "Stopping RemoteNavClient SSE listener")
        disconnectActiveSocket()
        workerThread?.interrupt()
        workerThread = null
        updateStatus(State.STOPPED, "")
    }

    /**
     * Performs an on-demand one-shot poll against `ntfy.sh/<topic>/json?poll=1&since=5m`
     * to diagnose connectivity immediately and deliver any queued message without waiting.
     */
    fun pollOnceAsync(context: Context, onComplete: (Boolean, String) -> Unit) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val code = RemoteNavStore.getOrCreatePairingCode(appCtx)
        val pollUrl = RemoteNavStore.buildPollJsonUrl(code)

        Thread({
            var conn: HttpURLConnection? = null
            try {
                conn = openGetConnection(pollUrl, readTimeoutMs = 8_000, acceptHeader = "application/x-ndjson")
                val httpCode = conn.responseCode
                if (httpCode != HttpURLConnection.HTTP_OK) {
                    val msg = "HTTP $httpCode"
                    updateStatus(State.ERROR, msg)
                    onComplete(false, msg)
                    return@Thread
                }

                var delivered = 0
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (handleIncomingLine(appCtx, line, ignoreAgeCheck = true)) {
                            delivered++
                        }
                    }
                }
                val summary = if (delivered > 0) {
                    "已拉取 $delivered 筆導航目的地"
                } else {
                    "伺服器連線正常（目前無待出發目的地）"
                }
                if (running.get()) {
                    updateStatus(State.CONNECTED, "ntfy.sh OK")
                }
                onComplete(true, summary)
            } catch (e: Exception) {
                val detail = formatError(e)
                Log.w(TAG, "Manual poll failed: $detail", e)
                updateStatus(State.ERROR, detail)
                onComplete(false, detail)
            } finally {
                conn?.disconnect()
            }
        }, "Y51-RemoteNav-Poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun launchWorker(appCtx: Context) {
        val code = RemoteNavStore.getOrCreatePairingCode(appCtx)
        val sseUrl = RemoteNavStore.buildSseUrl(code)
        Log.i(TAG, "Starting RemoteNavClient SSE listener")
        updateStatus(State.CONNECTING, "ntfy.sh")

        val thread = Thread({ runLoop(appCtx, sseUrl) }, "Y51-RemoteNav-SSE").apply {
            isDaemon = true
        }
        workerThread = thread
        thread.start()
    }

    private fun runLoop(appCtx: Context, sseUrl: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var conn: HttpURLConnection? = null
            try {
                updateStatus(State.CONNECTING, "連線中…")
                conn = openGetConnection(
                    urlString = sseUrl,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    acceptHeader = "text/event-stream"
                )
                activeConnection = conn

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val detail = "HTTP $code"
                    Log.w(TAG, "SSE HTTP status $code; retrying in ${backoffMs}ms")
                    updateStatus(State.ERROR, detail)
                    sleepInterruptibly(backoffMs)
                    backoffMs = (backoffMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                backoffMs = INITIAL_BACKOFF_MS
                updateStatus(State.CONNECTED, "ntfy.sh")

                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        handleIncomingLine(appCtx, line, ignoreAgeCheck = false)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    val detail = formatError(e)
                    Log.w(TAG, "SSE stream error ($detail); reconnecting in ${backoffMs}ms")
                    updateStatus(State.ERROR, detail)
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

    private fun openGetConnection(
        urlString: String,
        readTimeoutMs: Int,
        acceptHeader: String
    ): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            // Prevent transparent gzip buffering that stalls small SSE frames on Android 10.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Accept", acceptHeader)
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    private fun handleIncomingLine(
        appCtx: Context,
        line: String,
        ignoreAgeCheck: Boolean
    ): Boolean {
        RemoteNavParser.parseServerTimeFromControlFrame(line)?.let { serverTime ->
            serverReferenceTimeSec = serverTime
            updateStatus(State.CONNECTED, "ntfy.sh")
            return false
        }

        val incoming = RemoteNavParser.parseSseEventLine(line) ?: return false
        if (!ignoreAgeCheck) {
            // Compare against ntfy.sh's own server clock when available to avoid car clock drift.
            val refSec = serverReferenceTimeSec
            if (refSec != null && !RemoteNavParser.isFreshMessage(incoming.timeEpochSec, refSec, maxAgeSec = 240L)) {
                Log.d(TAG, "Ignoring stale remote nav message id=${incoming.id}")
                return false
            }
        }
        if (RemoteNavStore.isMessageHandled(appCtx, incoming.id)) {
            return false
        }
        RemoteNavStore.markMessageHandled(appCtx, incoming.id)

        val rawUrl = RemoteNavParser.extractFirstUrl(incoming.rawPayload)
        val resolvedUrl = if (rawUrl != null && RemoteNavParser.isShortMapUrl(rawUrl)) {
            RemoteNavParser.resolveShortUrl(rawUrl)
        } else {
            rawUrl
        }
        val parsed = RemoteNavParser.extractNavigationTarget(incoming.rawPayload, resolvedUrl) ?: return false
        Log.i(TAG, "Accepted remote destination '${parsed.displayLabel}' -> '${parsed.navigationQuery}'")
        currentStatus = currentStatus.copy(
            state = State.CONNECTED,
            detail = "ntfy.sh",
            lastDestination = parsed.displayLabel
        )
        notifyStatusListeners()
        onDestinationCallback?.invoke(parsed)
        return true
    }

    private fun registerNetworkObserver(appCtx: Context) {
        if (networkCallback != null) return
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (running.get() && currentStatus.state != State.CONNECTED) {
                    Log.i(TAG, "Network available; waking RemoteNavClient immediately")
                    disconnectActiveSocket()
                    workerThread?.interrupt()
                }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.d(TAG, "Unable to register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkObserver() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
            // Ignore unregister race.
        }
    }

    private fun disconnectActiveSocket() {
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
            // Ignore socket close races.
        }
        activeConnection = null
    }

    private fun updateStatus(state: State, detail: String) {
        currentStatus = currentStatus.copy(state = state, detail = detail)
        notifyStatusListeners()
    }

    private fun notifyStatusListeners() {
        val snap = currentStatus
        for (listener in statusListeners) {
            listener(snap)
        }
    }

    private fun formatError(e: Exception): String {
        val simple = e.javaClass.simpleName
        val msg = e.message?.trim()?.take(48)
        return if (msg.isNullOrEmpty()) simple else "$simple: $msg"
    }

    private fun sleepInterruptibly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
            // Wake early on network recovery or stop().
        }
    }
}
