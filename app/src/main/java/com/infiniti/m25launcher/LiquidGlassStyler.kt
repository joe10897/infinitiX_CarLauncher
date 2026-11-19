package com.infiniti.m25launcher

import android.view.View
import com.infiniti.m25launcher.LiquidGlassDrawable.Variant

/**
 * Applies Fine Vision Liquid Glass surfaces and ambient backdrop consistently across
 * the dashboard layout, voice HUD, saved-places editor, and hidden app drawer.
 */
object LiquidGlassStyler {

    private const val CORNER_PANEL_DP = 10f
    private const val CORNER_SUB_DP = 8f

    fun applyToDashboard(rootView: View) {
        rootView.findViewById<View>(R.id.rootDashboard)?.background = DashboardBackdropDrawable()

        applyGlass(rootView, R.id.panelTopStatus, CORNER_SUB_DP, Variant.PANEL)
        applyGlass(rootView, R.id.panelSpeedometer, CORNER_PANEL_DP, Variant.PANEL)
        applyGlass(rootView, R.id.panelMediaControl, CORNER_PANEL_DP, Variant.PANEL)

        applyGlass(rootView, R.id.btnVoiceCommand, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(rootView, R.id.btnRunSplitRoutine, CORNER_SUB_DP, Variant.ACCENT)
        applyGlass(rootView, R.id.btnSplitNav, CORNER_PANEL_DP, Variant.PANEL)
        applyGlass(rootView, R.id.btnSpeedcam, CORNER_PANEL_DP, Variant.PANEL)
        applyGlass(rootView, R.id.btnVehicleInfo, CORNER_PANEL_DP, Variant.PANEL)

        applyGlass(rootView, R.id.btnMediaPlayPause, CORNER_SUB_DP, Variant.ACCENT)
        applyGlass(rootView, R.id.btnMediaPrev, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(rootView, R.id.btnMediaNext, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToVoiceOverlay(rootView: View) {
        applyGlass(rootView, R.id.panelVoiceRouter, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(rootView, R.id.btnVoiceClose, CORNER_SUB_DP, Variant.SUB_PANEL)
        applyGlass(rootView, R.id.btnVoiceRetry, CORNER_SUB_DP, Variant.ACCENT)
        applyGlass(rootView, R.id.btnVoiceAssistant, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToAppDrawer(dialogRoot: View) {
        applyGlass(dialogRoot, R.id.panelAppDrawer, CORNER_PANEL_DP, Variant.MODAL)
        applyGlass(dialogRoot, R.id.btnDrawerClose, CORNER_SUB_DP, Variant.SUB_PANEL)
    }

    fun applyToDrawerTile(tileView: View) {
        tileView.background = LiquidGlassDrawable(
            cornerRadiusDp = CORNER_SUB_DP,
            variant = Variant.SUB_PANEL,
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

    private fun applyGlass(parent: View, viewId: Int, radiusDp: Float, variant: Variant) {
        val view = parent.findViewById<View>(viewId) ?: return
        view.background = LiquidGlassDrawable(
            cornerRadiusDp = radiusDp,
            variant = variant,
            density = view.resources.displayMetrics.density
        )
    }
}
