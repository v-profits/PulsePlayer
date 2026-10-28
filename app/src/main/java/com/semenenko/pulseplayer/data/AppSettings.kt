package com.semenenko.pulseplayer.data

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

    private val _contentFilter = MutableStateFlow(0)   // 0 — все, 1 — музыка, 2 — книги
    val contentFilter: StateFlow<Int> = _contentFilter.asStateFlow()

    private val _bookMinutes = MutableStateFlow(20)    // трек длиннее N минут считается «книгой»
    val bookMinutes: StateFlow<Int> = _bookMinutes.asStateFlow()

    private val _openPlayerOnPlay = MutableStateFlow(false)   // по умолчанию плеер открывают вручную
    val openPlayerOnPlay: StateFlow<Boolean> = _openPlayerOnPlay.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _ratePrompt = MutableStateFlow(false)
    val ratePrompt: StateFlow<Boolean> = _ratePrompt.asStateFlow()

    /** Вызывается при каждом реальном запуске: окно оценки — на 10-й раз, затем каждые 50 запусков, пока не оценено. */
    fun registerLaunch() {
        val p = prefs ?: return
        if (p.getBoolean("rated", false)) return
        val n = p.getInt("launch_count", 0) + 1
        p.edit().putInt("launch_count", n).apply()
        if (n == 10 || (n > 10 && (n - 10) % 50 == 0)) _ratePrompt.value = true
    }

    fun requestRate() { _ratePrompt.value = true }
    fun dismissRatePrompt() { _ratePrompt.value = false }
    fun markRated() {
        prefs?.edit()?.putBoolean("rated", true)?.apply()
        _ratePrompt.value = false
    }

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = p.getInt("theme", 0)
        _minLength.value = p.getInt("min_length", 0)
        _keepScreenOn.value = p.getBoolean("keep_screen_on", false)
        _openPlayerOnPlay.value = p.getBoolean("open_player_on_play", false)
        _contentFilter.value = p.getInt("content_filter", 0)
        _bookMinutes.value = p.getInt("book_minutes", 20)
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

    fun setOpenPlayerOnPlay(on: Boolean) {
        _openPlayerOnPlay.value = on
        prefs?.edit()?.putBoolean("open_player_on_play", on)?.apply()
    }

    fun setContentFilter(v: Int) {
        _contentFilter.value = v
        prefs?.edit()?.putInt("content_filter", v)?.apply()
    }

    fun setBookMinutes(v: Int) {
        _bookMinutes.value = v
        prefs?.edit()?.putInt("book_minutes", v)?.apply()
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
