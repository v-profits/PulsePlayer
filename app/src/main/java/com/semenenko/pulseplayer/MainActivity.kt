package com.semenenko.pulseplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.semenenko.pulseplayer.data.AppSettings
import com.semenenko.pulseplayer.ui.MainViewModel
import com.semenenko.pulseplayer.ui.PulseRoot
import com.semenenko.pulseplayer.ui.theme.PulseTheme

// AppCompatActivity нужен для переключения языка приложения (per-app locales) на всех версиях Android.
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            AppSettings.registerLaunch()
        }
        setContent { PulseTheme { PulseRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { vm.playExternal(it) }
    }
}
