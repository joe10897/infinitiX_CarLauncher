package com.infiniti.m25launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user-pinned favorite packages for the All-Apps Drawer so frequently used head-unit
 * apps (e.g. CarPlay / ZLink, YouTube, DVR) always appear in the top row.
 */
object PinnedAppsStore {

    private const val PREFS_NAME = "y51_pinned_apps"
    private const val KEY_PINNED_LIST = "pinned_packages"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPinnedPackages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PINNED_LIST, null).orEmpty()
        return parsePinnedList(raw)
    }

    fun isPinned(context: Context, packageName: String): Boolean =
        packageName in getPinnedPackages(context)

    /**
     * Toggles [packageName] in the pinned list and returns true when the package is now pinned.
     */
    fun togglePin(context: Context, packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        val current = getPinnedPackages(context).toMutableList()
        val nowPinned = if (trimmed in current) {
            current.remove(trimmed)
            false
        } else {
            current.add(trimmed)
            true
        }
        prefs(context).edit().putString(KEY_PINNED_LIST, serializePinnedList(current)).apply()
        return nowPinned
    }

    fun parsePinnedList(raw: String): List<String> =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun serializePinnedList(packages: List<String>): String =
        packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /**
     * Orders [items] so pinned packages appear first in pin order, followed by remaining items
     * in their existing order.
     */
    fun <T> sortWithPinnedFirst(
        items: List<T>,
        pinnedPackages: List<String>,
        packageSelector: (T) -> String
    ): List<T> {
        if (pinnedPackages.isEmpty() || items.isEmpty()) return items
        val rankMap = HashMap<String, Int>(pinnedPackages.size)
        pinnedPackages.forEachIndexed { index, pkg ->
            if (pkg !in rankMap) rankMap[pkg] = index
        }
        val pinned = ArrayList<T>()
        val unpinned = ArrayList<T>(items.size)
        for (item in items) {
            if (packageSelector(item) in rankMap) {
                pinned.add(item)
            } else {
                unpinned.add(item)
            }
        }
        pinned.sortBy { rankMap[packageSelector(it)] ?: Int.MAX_VALUE }
        return pinned + unpinned
    }
}
