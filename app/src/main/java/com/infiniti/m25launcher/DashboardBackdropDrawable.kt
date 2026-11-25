package com.infiniti.m25launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Infiniti Fine Vision obsidian backdrop with soft pre-diffused ambient light pools.
 * Supports adaptive daytime anti-glare lift vs. deep nighttime zero-glare depth.
 */
class DashboardBackdropDrawable(
    private val isDaytime: Boolean = false
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isDaytime) "#0F121A" else "#07080B")
        style = Paint.Style.FILL
    }

    private val orbTopLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbCenterRightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbBottomCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // Top-left Infiniti indigo aura behind speedometer & Split Nav
        val r1 = max(w * 0.44f, h * 0.96f)
        orbTopLeftPaint.shader = RadialGradient(
            bounds.left + w * 0.24f,
            bounds.top + h * 0.22f,
            r1,
            intArrayOf(
                Color.parseColor(if (isDaytime) "#684452A6" else "#5838448C"),
                Color.parseColor(if (isDaytime) "#2E2C3975" else "#24242E61"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        // Right-side cool sapphire aura behind Speedcam & Media glass card
        val r2 = max(w * 0.42f, h * 0.92f)
        orbCenterRightPaint.shader = RadialGradient(
            bounds.left + w * 0.78f,
            bounds.top + h * 0.34f,
            r2,
            intArrayOf(
                Color.parseColor(if (isDaytime) "#5A335C8F" else "#4C274873"),
                Color.parseColor(if (isDaytime) "#24223B66" else "#1D1B3052"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle lower-center depth pool behind center cards
        val r3 = max(w * 0.36f, h * 0.82f)
        orbBottomCenterPaint.shader = RadialGradient(
            bounds.left + w * 0.48f,
            bounds.top + h * 0.88f,
            r3,
            intArrayOf(
                Color.parseColor(if (isDaytime) "#4A3A357A" else "#422E2A68"),
                Color.parseColor(if (isDaytime) "#1A202648" else "#14191D38"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        canvas.drawRect(b, orbTopLeftPaint)
        canvas.drawRect(b, orbCenterRightPaint)
        canvas.drawRect(b, orbBottomCenterPaint)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        orbTopLeftPaint.alpha = alpha
        orbCenterRightPaint.alpha = alpha
        orbBottomCenterPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        orbTopLeftPaint.colorFilter = colorFilter
        orbCenterRightPaint.colorFilter = colorFilter
        orbBottomCenterPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
