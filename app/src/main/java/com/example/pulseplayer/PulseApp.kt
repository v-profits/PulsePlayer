package com.example.pulseplayer

import android.app.Application
import com.example.pulseplayer.audio.EqualizerManager
import com.example.pulseplayer.data.AppSettings

class PulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)   // применяет сохранённую тему до создания экранов
        EqualizerManager.init(this)
    }
}
