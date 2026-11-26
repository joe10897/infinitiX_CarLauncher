/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware-accelerated Infiniti Y51 Fine Vision 240-degree precision twin-ring speed gauge.
 * Smoothly animates speed transitions and shifts from signature violet-indigo glow to warm amber
 * above [TripTelemetryTracker.OVERSPEED_WARN_KMH].
 */
class FineVisionSpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.85f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val baseTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val glowMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val tipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tipCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF8FAFF")
    }

    private val bezelRect = RectF()
    private val arcRect = RectF()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private var displayedSpeed = 0f
    private var lastReportedIntSpeed = 0
    private var isDaytimeMode = false
    private var speedAnimator: ValueAnimator? = null

    init {
        applyModeColors()
    }

    fun setDaytimeMode(isDaytime: Boolean) {
        if (isDaytimeMode == isDaytime) return
        isDaytimeMode = isDaytime
        applyModeColors()
        invalidate()
    }

    fun setSpeedKmh(
        targetKmh: Int,
        animate: Boolean = true,
        onDisplayedSpeedChanged: ((Int) -> Unit)? = null
    ) {
        val clamped = targetKmh.coerceAtLeast(0).toFloat()
        speedAnimator?.cancel()

        if (!animate || kotlin.math.abs(clamped - displayedSpeed) < 0.5f) {
            displayedSpeed = clamped
            val intSpeed = clamped.roundToInt()
            lastReportedIntSpeed = intSpeed
            onDisplayedSpeedChanged?.invoke(intSpeed)
            invalidate()
            return
        }

        val start = displayedSpeed
        speedAnimator = ValueAnimator.ofFloat(start, clamped).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                displayedSpeed = anim.animatedValue as Float
                val intSpeed = displayedSpeed.roundToInt()
                if (intSpeed != lastReportedIntSpeed) {
                    lastReportedIntSpeed = intSpeed
                    onDisplayedSpeedChanged?.invoke(intSpeed)
                }
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 8f * density
        val availW = (w - paddingLeft - paddingRight - margin * 2f).coerceAtLeast(0f)
        val availH = (h - paddingTop - paddingBottom - margin * 2f).coerceAtLeast(0f)
        if (availW <= 0f || availH <= 0f) {
            radius = 0f
            return
        }

        // 240-degree sweep spans -1.0r above center to +0.5r below center (height = 1.5r).
        radius = min(availW * 0.5f, availH / 1.55f)
        cx = paddingLeft + margin + availW * 0.5f
        val verticalSpan = radius * 1.5f
        cy = paddingTop + margin + (availH - verticalSpan) * 0.5f + radius

        val bezelRadius = radius + 4.5f * density
        bezelRect.set(cx - bezelRadius, cy - bezelRadius, cx + bezelRadius, cy + bezelRadius)

        val arcRadius = radius - 3.5f * density
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // 1. Outer watchmaker hairline bezel
        canvas.drawArc(bezelRect, START_ANGLE_DEG, SWEEP_ANGLE_DEG, false, outerBezelPaint)

        // 2. Precision radial graduation ticks (0..140 km/h in 5 km/h steps)
        val tickOuterR = radius + 2.2f * density
        for (step in 0..TOTAL_TICK_STEPS) {
            val fraction = step.toFloat() / TOTAL_TICK_STEPS.toFloat()
            val angleDeg = START_ANGLE_DEG + SWEEP_ANGLE_DEG * fraction
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val isMajor = step % MAJOR_TICK_INTERVAL == 0
            val speedAtTick = step * STEP_KMH
            val isWarnZone = TripTelemetryTracker.isOverspeed(speedAtTick)

            val tickLen = (if (isMajor) 5.5f else 2.8f) * density
            val innerR = tickOuterR - tickLen

            val paint = if (isMajor) majorTickPaint else minorTickPaint
            val originalColor = paint.color
            if (isWarnZone && isMajor) {
                paint.color = Color.parseColor("#B8F5A623")
            }

            canvas.drawLine(
                cx + innerR * cosA,
                cy + innerR * sinA,
                cx + tickOuterR * cosA,
                cy + tickOuterR * sinA,
                paint
            )
            paint.color = originalColor
        }

        // 3. Recessed track arc
        canvas.drawArc(arcRect, START_ANGLE_DEG, SWEEP_ANGLE_DEG, false, baseTrackPaint)

        // 4. Active luminous speed sweep + indicator bead
        val ratio = (displayedSpeed / TripTelemetryTracker.SPEED_BAR_MAX_KMH.toFloat()).coerceIn(0f, 1f)
        if (ratio > 0.004f) {
            val activeSweep = SWEEP_ANGLE_DEG * ratio
            val isOverspeed = TripTelemetryTracker.isOverspeed(displayedSpeed.roundToInt())
            val accentRgb = if (isOverspeed) COLOR_AMBER_RGB else COLOR_INDIGO_RGB

            glowOuterPaint.color = (0x26 shl 24) or accentRgb
            glowMidPaint.color = (0x54 shl 24) or accentRgb
            activeArcPaint.color = if (isOverspeed) COLOR_AMBER_CORE else COLOR_INDIGO_CORE

            canvas.drawArc(arcRect, START_ANGLE_DEG, activeSweep, false, glowOuterPaint)
            canvas.drawArc(arcRect, START_ANGLE_DEG, activeSweep, false, glowMidPaint)
            canvas.drawArc(arcRect, START_ANGLE_DEG, activeSweep, false, activeArcPaint)

            val tipRad = Math.toRadians((START_ANGLE_DEG + activeSweep).toDouble())
            val arcRadius = arcRect.width() * 0.5f
            val tipX = cx + arcRadius * cos(tipRad).toFloat()
            val tipY = cy + arcRadius * sin(tipRad).toFloat()

            tipGlowPaint.color = (0x78 shl 24) or accentRgb
            canvas.drawCircle(tipX, tipY, 4.2f * density, tipGlowPaint)
            canvas.drawCircle(tipX, tipY, 1.9f * density, tipCorePaint)
        }
    }

    override fun onDetachedFromWindow() {
        speedAnimator?.cancel()
        speedAnimator = null
        super.onDetachedFromWindow()
    }

    private fun applyModeColors() {
        outerBezelPaint.color = Color.parseColor(if (isDaytimeMode) "#44FFFFFF" else "#24FFFFFF")
        majorTickPaint.color = Color.parseColor(if (isDaytimeMode) "#8CFFFFFF" else "#62FFFFFF")
        minorTickPaint.color = Color.parseColor(if (isDaytimeMode) "#44FFFFFF" else "#2AFFFFFF")
        baseTrackPaint.color = Color.parseColor(if (isDaytimeMode) "#30FFFFFF" else "#1EFFFFFF")
    }

    companion object {
        private const val START_ANGLE_DEG = 150f
        private const val SWEEP_ANGLE_DEG = 240f
        private const val STEP_KMH = 5
        private const val TOTAL_TICK_STEPS = TripTelemetryTracker.SPEED_BAR_MAX_KMH / STEP_KMH
        private const val MAJOR_TICK_INTERVAL = 4 // Every 20 km/h
        private const val ANIM_DURATION_MS = 280L

        private const val COLOR_INDIGO_RGB = 0x7D85E6
        private const val COLOR_INDIGO_CORE = 0xFF9EA6FF.toInt()
        private const val COLOR_AMBER_RGB = 0xF5A623
        private const val COLOR_AMBER_CORE = 0xFFFFC15E.toInt()
    }
}
