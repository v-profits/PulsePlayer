package com.example.pulseplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки приложения (хранятся в SharedPreferences). Язык хранит сам AppCompat (per-app locales). */
object AppSettings {
    private var prefs: SharedPreferences? = null

    private val _theme = MutableStateFlow(0)        // 0 — системная, 1 — светлая, 2 — тёмная
    val theme: StateFlow<Int> = _theme.asStateFlow()

    private val _minLength = MutableStateFlow(0)    // скрывать треки короче N секунд (0 — не скрывать)
    val minLength: StateFlow<Int> = _minLength.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = p.getInt("theme", 0)
        _minLength.value = p.getInt("min_length", 0)
        _keepScreenOn.value = p.getBoolean("keep_screen_on", false)
        applyTheme(_theme.value)
    }

    fun setTheme(v: Int) {
        _theme.value = v
        prefs?.edit()?.putInt("theme", v)?.apply()
        applyTheme(v)
    }

    fun setMinLength(seconds: Int) {
        _minLength.value = seconds
        prefs?.edit()?.putInt("min_length", seconds)?.apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs?.edit()?.putBoolean("keep_screen_on", on)?.apply()
    }

    private fun applyTheme(v: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (v) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
