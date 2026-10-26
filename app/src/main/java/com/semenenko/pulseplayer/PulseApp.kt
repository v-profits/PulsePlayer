package com.semenenko.pulseplayer

import android.app.Application
import com.semenenko.pulseplayer.audio.EqualizerManager
import com.semenenko.pulseplayer.data.AppSettings

class PulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)   // применяет сохранённую тему до создания экранов
        EqualizerManager.init(this)
    }
}
