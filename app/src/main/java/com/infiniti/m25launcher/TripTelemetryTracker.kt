package com.infiniti.m25launcher

import kotlin.math.max

/**
 * Filters low-speed GPS drift (deadband) and tracks current-trip telemetry
 * (peak speed, moving average speed, and active drive duration).
 */
class TripTelemetryTracker {

    data class Snapshot(
        val currentSpeedKmh: Int,
        val maxSpeedKmh: Int,
        val avgSpeedKmh: Int,
        val elapsedMs: Long
    )

    private var maxSpeedKmh: Int = 0
    private var movingSpeedSum: Long = 0L
    private var movingSamples: Long = 0L
    private var firstMoveElapsedMs: Long? = null
    private var lastSampleElapsedMs: Long = 0L

    fun recordSample(speedKmh: Int, nowElapsedMs: Long): Snapshot {
        val safeSpeed = speedKmh.coerceAtLeast(0)
        if (safeSpeed > maxSpeedKmh) {
            maxSpeedKmh = safeSpeed
        }
        if (safeSpeed > 0) {
            if (firstMoveElapsedMs == null) {
                firstMoveElapsedMs = nowElapsedMs
            }
            movingSpeedSum += safeSpeed
            movingSamples += 1L
        }
        lastSampleElapsedMs = nowElapsedMs
        return currentSnapshot(safeSpeed, nowElapsedMs)
    }

    fun currentSnapshot(currentSpeedKmh: Int, nowElapsedMs: Long): Snapshot {
        val avg = if (movingSamples > 0L) (movingSpeedSum / movingSamples).toInt() else 0
        val start = firstMoveElapsedMs
        val elapsed = if (start != null) max(0L, nowElapsedMs - start) else 0L
        return Snapshot(
            currentSpeedKmh = currentSpeedKmh.coerceAtLeast(0),
            maxSpeedKmh = maxSpeedKmh,
            avgSpeedKmh = avg,
            elapsedMs = elapsed
        )
    }

    fun reset() {
        maxSpeedKmh = 0
        movingSpeedSum = 0L
        movingSamples = 0L
        firstMoveElapsedMs = null
        lastSampleElapsedMs = 0L
    }

    companion object {
        /** Speeds below ~2.0 km/h (0.55 m/s) are clamped to 0 to eliminate stationary GPS drift. */
        const val DEADBAND_MPS = 0.55f
        const val SPEED_BAR_MAX_KMH = 140
        const val OVERSPEED_WARN_KMH = 115
        const val COMPASS_MIN_SPEED_KMH = 4

        private val COMPASS_SECTORS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        fun filterSpeedKmh(speedMps: Float, hasSpeed: Boolean): Int {
            if (!hasSpeed || speedMps.isNaN() || speedMps < DEADBAND_MPS) return 0
            return (speedMps * 3.6f).toInt()
        }

        fun speedBarRatio(speedKmh: Int, maxScaleKmh: Int = SPEED_BAR_MAX_KMH): Float {
            if (speedKmh <= 0 || maxScaleKmh <= 0) return 0f
            return (speedKmh.toFloat() / maxScaleKmh.toFloat()).coerceIn(0f, 1f)
        }

        fun isOverspeed(speedKmh: Int, thresholdKmh: Int = OVERSPEED_WARN_KMH): Boolean =
            speedKmh >= thresholdKmh

        /**
         * Formats GPS bearing degrees into a compact 8-point cardinal readout (e.g. `NE 042°`).
         */
        fun formatCompassHeading(bearingDeg: Float): String {
            if (bearingDeg.isNaN() || bearingDeg.isInfinite()) return "N 000°"
            val normalized = ((bearingDeg % 360f) + 360f) % 360f
            val roundedDeg = Math.round(normalized) % 360
            val sectorIndex = (((normalized + 22.5f) / 45f).toInt()) % COMPASS_SECTORS.size
            return String.format("%s %03d°", COMPASS_SECTORS[sectorIndex], roundedDeg)
        }

        fun formatDuration(elapsedMs: Long): String {
            val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}
