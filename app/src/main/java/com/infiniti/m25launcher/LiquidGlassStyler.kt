package com.infiniti.m25launcher

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.infiniti.m25launcher.LiquidGlassDrawable.Variant

/**
 * Applies Fine Vision Liquid Glass surfaces, rounded clipping, and adaptive day/night backdrop
 * consistently across the dashboard, voice HUD, system health checklist, saved places, and drawer.
 */
object LiquidGlassStyler {

    private const val CORNER_PANEL_DP = 10f
    private const val CORNER_SUB_DP = 8f

    /**
     * Applies dashboard glass surfaces and returns the [LiquidGlassDrawable] bound to
     * `panelMediaControl` so live album-art ambient tinting can update it without reallocating.
     */
    fun applyToDashboard(rootView: View, isDaytime: Boolean = false): LiquidGlassDrawable? {
        rootView.findViewById<View>(R.id.rootDashboard)?.background =
            DashboardBackdropDrawable(isDaytime)

        applyGlass(rootView, R.id.panelTopStatus, CORNER_SUB_DP, Variant.PANEL, isDaytime)
        applyGlass(rootView, R.id.panelSpeedometer, CORNER_PANEL_DP, Variant.PANEL, isDaytime)
        val mediaPanelGlass =
            applyGlass(rootView, R.id.panelMediaControl, CORNER_PANEL_DP, Variant.PANEL, isDaytime)

        applyGlass(rootView, R.id.btnVoiceCommand, CORNER_SUB_DP, Variant.SUB_PANEL, isDaytime)
        applyGlass(rootView, R.id.btnRunSplitRoutine, CORNER_SUB_DP, Variant.ACCENT, isDaytime)
        applyGlass(rootView, R.id.btnSplitNav, CORNER_PANEL_DP, Variant.PANEL, isDaytime)
        applyGlass(rootView, R.id.btnSpeedcam, CORNER_PANEL_DP, Variant.PANEL, isDaytime)
        applyGlass(rootView, R.id.btnVehicleInfo, CORNER_PANEL_DP, Variant.PANEL, isDaytime)

        applyGlass(rootView, R.id.btnMediaPlayPause, CORNER_SUB_DP, Variant.ACCENT, isDaytime)
        applyGlass(rootView, R.id.btnMediaPrev, CORNER_SUB_DP, Variant.SUB_PANEL, isDaytime)
        applyGlass(rootView, R.id.btnMediaNext, CORNER_SUB_DP, Variant.SUB_PANEL, isDaytime)

        rootView.findViewById<View>(R.id.panelMediaControl)?.let {
            applyRoundedClip(it, CORNER_PANEL_DP)
        }
        rootView.findViewById<View>(R.id.ivMediaAlbumThumb)?.let {
            applyRoundedClip(it, 6f)
        }
        return mediaPanelGlass
    }

    fun applyToVoiceOverlay(rootView: View) {
        applyGlass(rootView, R.id.panelVoiceRouter, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(rootView, R.id.btnVoiceBindSwc, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(rootView, R.id.btnVoiceClose, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(rootView, R.id.btnVoiceRetry, CORNER_SUB_DP, Variant.ACCENT)
        applyGlass(rootView, R.id.btnVoiceAssistant, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToAppDrawer(dialogRoot: View) {
        applyGlass(dialogRoot, R.id.panelAppDrawer, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(dialogRoot, R.id.btnDrawerClose, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToDrawerTile(tileView: View, isPinned: Boolean = false) {
        tileView.background = LiquidGlassDrawable(
            cornerRadiusDp = CORNER_SUB_DP,
            variant = if (isPinned) Variant.ACCENT else Variant.SUB_PANEL,
            density = tileView.resources.displayMetrics.density
        )
    }

    fun applyToSavedPlacesDialog(dialogRoot: View) {
        applyGlass(dialogRoot, R.id.panelSavedPlaces, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(dialogRoot, R.id.btnPlacesCancel, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.btnPlacesSave, CORNER_SUB_DP, Variant.ACCENT)
        applyGlass(dialogRoot, R.id.etHomeAddress, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.etWorkAddress, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToSystemHealthDialog(dialogRoot: View) {
        applyGlass(dialogRoot, R.id.panelSystemHealth, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(dialogRoot, R.id.btnHealthClose, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.rowHealthHome, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.rowHealthAccessibility, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.rowHealthMediaSync, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.rowHealthPermissions, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(dialogRoot, R.id.rowHealthBattery, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyRoundedClip(view: View, cornerRadiusDp: Float) {
        val radiusPx = cornerRadiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }

    private fun applyGlass(
        parent: View,
        viewId: Int,
        radiusDp: Float,
        variant: Variant,
        isDaytime: Boolean = false
    ): LiquidGlassDrawable? {
        val view = parent.findViewById<View>(viewId) ?: return null
        val drawable = LiquidGlassDrawable(
            cornerRadiusDp = radiusDp,
            variant = variant,
            density = view.resources.displayMetrics.density,
            isDaytime = isDaytime
        )
        view.background = drawable
        return drawable
    }
}
