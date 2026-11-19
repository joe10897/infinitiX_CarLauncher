package com.infiniti.m25launcher

import android.content.Context

/**
 * Persists user-configured Home and Work addresses for quick voice navigation commands.
 */
object SavedPlacesStore {

    private const val PREFS_NAME = "y51_saved_places"
    private const val KEY_HOME = "home_address"
    private const val KEY_WORK = "work_address"

    fun getHomeAddress(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun getWorkAddress(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WORK, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun savePlaces(context: Context, home: String?, work: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            val cleanHome = home?.trim().orEmpty()
            val cleanWork = work?.trim().orEmpty()
            if (cleanHome.isEmpty()) remove(KEY_HOME) else putString(KEY_HOME, cleanHome)
            if (cleanWork.isEmpty()) remove(KEY_WORK) else putString(KEY_WORK, cleanWork)
        }.apply()
    }
}
