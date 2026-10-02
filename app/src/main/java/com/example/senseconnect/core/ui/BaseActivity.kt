package com.example.senseconnect.core.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.senseconnect.R
import com.example.senseconnect.SenseConnectApp
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.settings.SettingsRepository

/**
 * Base for every SenseConnect screen. Applies the user's accessibility preferences
 * consistently: in-app text size (on top of Android font scale), high-contrast theme overlay,
 * and edge-to-edge drawing.
 */
abstract class BaseActivity : AppCompatActivity() {

    val container: AppContainer get() = (application as SenseConnectApp).container

    protected open val highContrastOverlay: Int = R.style.ThemeOverlay_SenseConnect_HighContrast

    override fun attachBaseContext(newBase: Context) {
        val scale = SettingsRepository.readTextScale(newBase)
        if (scale != 1f) {
            // Sparse override: only fontScale is changed, so night mode etc. are untouched.
            applyOverrideConfiguration(Configuration().apply {
                fontScale = newBase.resources.configuration.fontScale * scale
            })
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After super.onCreate so AppCompat's in-app night mode is applied and status-bar
        // icon colours match the app theme rather than the system theme.
        enableEdgeToEdge()
        if (container.settings.current.highContrast) {
            theme.applyStyle(highContrastOverlay, true)
        }
    }
}
