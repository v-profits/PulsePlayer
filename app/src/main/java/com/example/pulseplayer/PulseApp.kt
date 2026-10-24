package com.example.pulseplayer

import android.app.Application
import com.example.pulseplayer.audio.EqualizerManager

class PulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EqualizerManager.init(this)
    }
}
