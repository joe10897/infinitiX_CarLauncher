package com.infiniti.m25launcher

import java.util.Calendar

/**
 * Determines daytime anti-glare vs. nighttime low-luminance Fine Vision cockpit rendering.
 */
object CockpitThemeMode {

    private const val DAY_START_HOUR = 6
    private const val DAY_END_HOUR_EXCLUSIVE = 18

    fun isDaytimeHour(hourOfDay: Int): Boolean =
        hourOfDay in DAY_START_HOUR until DAY_END_HOUR_EXCLUSIVE

    fun isDaytimeNow(calendar: Calendar = Calendar.getInstance()): Boolean =
        isDaytimeHour(calendar.get(Calendar.HOUR_OF_DAY))
}
