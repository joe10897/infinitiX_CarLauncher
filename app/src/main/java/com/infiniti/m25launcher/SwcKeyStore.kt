/*
 * Copyright (c) 2026 Simon C.-Y. Wong. All rights reserved.
 */

package com.infiniti.m25launcher

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Persists and evaluates physical steering-wheel key bindings used to launch [VoiceTriggerActivity].
 * FYT head units often map the steering-wheel voice button to arbitrary Android key codes rather than
 * [KeyEvent.KEYCODE_VOICE_ASSIST], so users can capture the exact key code once via Accessibility.
 */
object SwcKeyStore {

    private const val PREFS_NAME = "y51_swc_prefs"
    private const val KEY_VOICE_SWC_KEYCODE = "voice_swc_keycode"

    private val PROTECTED_SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLearnedKeyCode(context: Context): Int? {
        val code = prefs(context).getInt(KEY_VOICE_SWC_KEYCODE, -1)
        return code.takeIf { it > 0 && !isProtectedSystemKey(it) }
    }

    fun saveLearnedKeyCode(context: Context, keyCode: Int): Boolean {
        if (keyCode <= 0 || isProtectedSystemKey(keyCode)) return false
        prefs(context).edit().putInt(KEY_VOICE_SWC_KEYCODE, keyCode).apply()
        return true
    }

    fun isProtectedSystemKey(keyCode: Int): Boolean = keyCode in PROTECTED_SYSTEM_KEYS

    fun isVoiceTriggerKey(keyCode: Int, learnedKeyCode: Int?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_SEARCH
        ) {
            return true
        }
        return learnedKeyCode != null &&
            learnedKeyCode > 0 &&
            !isProtectedSystemKey(learnedKeyCode) &&
            keyCode == learnedKeyCode
    }

    fun formatKeyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    fun formatKeyLabel(keyCode: Int): String = "${formatKeyName(keyCode)} (#$keyCode)"
}
