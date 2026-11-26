/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Adds automotive-grade physical press micro-scaling and subtle haptic feedback to glass controls
 * without consuming click or long-click events.
 */
object TactileFeedbackHelper {

    private const val PRESSED_SCALE = 0.97f
    private const val DOWN_DURATION_MS = 85L
    private const val UP_DURATION_MS = 150L
    private val downInterpolator = DecelerateInterpolator()
    private val upInterpolator = OvershootInterpolator(1.1f)

    @SuppressLint("ClickableViewAccessibility")
    fun attach(vararg views: View) {
        for (view in views) {
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        target.animate()
                            .scaleX(PRESSED_SCALE)
                            .scaleY(PRESSED_SCALE)
                            .setDuration(DOWN_DURATION_MS)
                            .setInterpolator(downInterpolator)
                            .start()
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(UP_DURATION_MS)
                            .setInterpolator(upInterpolator)
                            .start()
                    }
                }
                false
            }
        }
    }
}
