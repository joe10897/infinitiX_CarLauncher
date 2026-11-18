package com.infiniti.m25launcher

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Hardware-accelerated Liquid Glass surface drawable compatible with API 26+.
 * Renders translucent tinted glass, an internal specular refraction sweep,
 * subtle inner edge thickness bevel, and a directional prismatic glass rim.
 */
class LiquidGlassDrawable(
    private val cornerRadiusDp: Float = 10f,
    private val variant: Variant = Variant.PANEL,
    private val density: Float = Resources.getSystem().displayMetrics.density
) : Drawable() {

    enum class Variant {
        /** Primary dashboard card panels and 2x2 launcher tiles. */
        PANEL,
        /** Recessed inner glass sub-buttons (PREV / NEXT, dialog rows). */
        SUB_PANEL,
        /** Highlighted action glass (RUN SPLIT ROUTINE, PLAY / PAUSE). */
        ACCENT
    }

    private val cornerRadiusPx = cornerRadiusDp * density
    private val strokeWidthPx = 1.15f * density
    private val pressedStrokeWidthPx = 1.5f * density
    private val innerBevelInsetPx = 1.1f * density

    private val outerRect = RectF()
    private val strokeRect = RectF()
    private val innerBevelRect = RectF()
    private val clipPath = Path()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val innerBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    private var isPressedState = false
    private var alphaMultiplier = 255

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = hasPressedState(state)
        if (nowPressed != isPressedState) {
            isPressedState = nowPressed
            rebuildShaders()
            invalidateSelf()
            return true
        }
        return false
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val halfStroke = strokeWidthPx * 0.5f
        outerRect.set(bounds)
        strokeRect.set(
            bounds.left + halfStroke,
            bounds.top + halfStroke,
            bounds.right - halfStroke,
            bounds.bottom - halfStroke
        )
        innerBevelRect.set(
            bounds.left + innerBevelInsetPx,
            bounds.top + innerBevelInsetPx,
            bounds.right - innerBevelInsetPx,
            bounds.bottom - innerBevelInsetPx
        )
        clipPath.reset()
        clipPath.addRoundRect(outerRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val w = outerRect.width()
        val h = outerRect.height()
        if (w <= 0f || h <= 0f) return

        val l = outerRect.left
        val t = outerRect.top
        val r = outerRect.right
        val b = outerRect.bottom

        bodyPaint.shader = LinearGradient(
            l, t, r, b,
            bodyColors(),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Upper liquid meniscus specular sheen (softer for nested sub-panels)
        val sheenTopAlpha = when {
            isPressedState -> Color.parseColor("#38FFFFFF")
            variant == Variant.SUB_PANEL -> Color.parseColor("#18FFFFFF")
            else -> Color.parseColor("#28FFFFFF")
        }
        val sheenMidAlpha = if (variant == Variant.SUB_PANEL) {
            Color.parseColor("#07FFFFFF")
        } else {
            Color.parseColor("#0CFFFFFF")
        }
        sheenPaint.shader = LinearGradient(
            l, t, l + w * 0.35f, t + h * 0.62f,
            intArrayOf(
                sheenTopAlpha,
                sheenMidAlpha,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle lower-right internal refraction bounce
        causticPaint.shader = RadialGradient(
            r - w * 0.14f,
            b - h * 0.12f,
            maxOf(w, h) * 0.62f,
            intArrayOf(
                when {
                    isPressedState -> Color.parseColor("#387D85E6")
                    variant == Variant.ACCENT -> Color.parseColor("#2E7D85E6")
                    else -> Color.parseColor("#1D7D85E6")
                },
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Inner top-edge glass thickness rim
        innerBevelPaint.shader = LinearGradient(
            l, t, l, t + h * 0.55f,
            intArrayOf(
                if (isPressedState) Color.parseColor("#3CFFFFFF") else Color.parseColor("#26FFFFFF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Prismatic directional outer glass rim (catches light top-left, refracts indigo bottom-right)
        rimPaint.strokeWidth = if (isPressedState) pressedStrokeWidthPx else strokeWidthPx
        rimPaint.shader = LinearGradient(
            l, t, r, b,
            rimColors(),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun bodyColors(): IntArray {
        return when {
            isPressedState && variant == Variant.ACCENT -> intArrayOf(
                Color.parseColor("#A6333B70"),
                Color.parseColor("#B8232952"),
                Color.parseColor("#CC181C38")
            )
            isPressedState -> intArrayOf(
                Color.parseColor("#9E252C40"),
                Color.parseColor("#B01A1F2E"),
                Color.parseColor("#BF131622")
            )
            variant == Variant.ACCENT -> intArrayOf(
                Color.parseColor("#8A242B54"),
                Color.parseColor("#9C181D3B"),
                Color.parseColor("#B012162C")
            )
            variant == Variant.SUB_PANEL -> intArrayOf(
                Color.parseColor("#751B2030"),
                Color.parseColor("#8A141824"),
                Color.parseColor("#9C0E111A")
            )
            else -> intArrayOf(
                Color.parseColor("#7A1A2030"),
                Color.parseColor("#92121622"),
                Color.parseColor("#A80C0F18")
            )
        }
    }

    private fun rimColors(): IntArray {
        return when {
            isPressedState -> intArrayOf(
                Color.parseColor("#D8FFFFFF"),
                Color.parseColor("#999AA4FF"),
                Color.parseColor("#777D85E6"),
                Color.parseColor("#C4BFC6FF")
            )
            variant == Variant.ACCENT -> intArrayOf(
                Color.parseColor("#9CE0E4FF"),
                Color.parseColor("#667D85E6"),
                Color.parseColor("#387D85E6"),
                Color.parseColor("#8C9EA6FF")
            )
            variant == Variant.SUB_PANEL -> intArrayOf(
                Color.parseColor("#5CFFFFFF"),
                Color.parseColor("#20FFFFFF"),
                Color.parseColor("#14FFFFFF"),
                Color.parseColor("#427D85E6")
            )
            else -> intArrayOf(
                Color.parseColor("#78FFFFFF"),
                Color.parseColor("#2CA4AEFF"),
                Color.parseColor("#18FFFFFF"),
                Color.parseColor("#547D85E6")
            )
        }
    }

    override fun draw(canvas: Canvas) {
        if (outerRect.isEmpty) return

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(outerRect, bodyPaint)
        canvas.drawRect(outerRect, sheenPaint)
        canvas.drawRect(outerRect, causticPaint)
        val innerRadius = (cornerRadiusPx - innerBevelInsetPx).coerceAtLeast(0f)
        canvas.drawRoundRect(innerBevelRect, innerRadius, innerRadius, innerBevelPaint)
        canvas.restoreToCount(saveCount)

        val strokeRadius = (cornerRadiusPx - rimPaint.strokeWidth * 0.5f).coerceAtLeast(0f)
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        alphaMultiplier = alpha
        bodyPaint.alpha = alpha
        sheenPaint.alpha = alpha
        causticPaint.alpha = alpha
        innerBevelPaint.alpha = alpha
        rimPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        sheenPaint.colorFilter = colorFilter
        causticPaint.colorFilter = colorFilter
        innerBevelPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Returns true when Android drawable state flags include pressed feedback. */
        fun hasPressedState(state: IntArray): Boolean =
            state.contains(android.R.attr.state_pressed)
    }
}
