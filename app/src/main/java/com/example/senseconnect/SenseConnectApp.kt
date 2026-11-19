package com.example.senseconnect

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.senseconnect.core.AppContainer

/**
 * Application entry point. Owns the [AppContainer] so every screen shares the same
 * settings, activity log, text-to-speech engine, location and backend repositories.
 */
class SenseConnectApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppCompatDelegate.setDefaultNightMode(container.settings.current.themeMode.nightMode)
        container.start()
    }
}
