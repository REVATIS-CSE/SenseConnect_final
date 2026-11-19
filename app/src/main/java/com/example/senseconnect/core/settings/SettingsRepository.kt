package com.example.senseconnect.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
}

/** In-app text size multiplier, applied on top of the Android system font scale. */
enum class TextSize(val scale: Float) {
    DEFAULT(1.0f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f),
}

data class AppSettings(
    val textSize: TextSize = TextSize.DEFAULT,
    val highContrast: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val voiceFeedback: Boolean = true,
    val hapticFeedback: Boolean = true,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val speakOnTap: Boolean = true,
    val autoReadScans: Boolean = false,
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val emergencyMessage: String = DEFAULT_EMERGENCY_MESSAGE,
    val directSms: Boolean = false,
    val cloudLogging: Boolean = true,
    val onboardingComplete: Boolean = false,
) {
    val hasEmergencyContact: Boolean get() = emergencyContactPhone.isNotBlank()

    companion object {
        const val DEFAULT_EMERGENCY_MESSAGE =
            "EMERGENCY: I need help. This alert was sent from SenseConnect."
    }
}

/**
 * Persists user preferences in SharedPreferences and exposes them as a [StateFlow] so
 * every screen reacts to changes immediately.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    /** Stable, random installation id used to correlate backend events. Not a hardware id. */
    val installationId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_INSTALL_ID, it) }
        }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        write(updated)
        _settings.value = updated
    }

    private fun read() = AppSettings(
        textSize = enumOrDefault(prefs.getString(KEY_TEXT_SIZE, null), TextSize.DEFAULT),
        highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
        themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
        voiceFeedback = prefs.getBoolean(KEY_VOICE_FEEDBACK, true),
        hapticFeedback = prefs.getBoolean(KEY_HAPTICS, true),
        speechRate = prefs.getFloat(KEY_SPEECH_RATE, 1.0f),
        speechPitch = prefs.getFloat(KEY_SPEECH_PITCH, 1.0f),
        speakOnTap = prefs.getBoolean(KEY_SPEAK_ON_TAP, true),
        autoReadScans = prefs.getBoolean(KEY_AUTO_READ, false),
        emergencyContactName = prefs.getString(KEY_CONTACT_NAME, "").orEmpty(),
        emergencyContactPhone = prefs.getString(KEY_CONTACT_PHONE, "").orEmpty(),
        emergencyMessage = prefs.getString(KEY_EMERGENCY_MESSAGE, null)
            ?: AppSettings.DEFAULT_EMERGENCY_MESSAGE,
        directSms = prefs.getBoolean(KEY_DIRECT_SMS, false),
        cloudLogging = prefs.getBoolean(KEY_CLOUD_LOGGING, true),
        onboardingComplete = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    private fun write(s: AppSettings) = prefs.edit {
        putString(KEY_TEXT_SIZE, s.textSize.name)
        putBoolean(KEY_HIGH_CONTRAST, s.highContrast)
        putString(KEY_THEME, s.themeMode.name)
        putBoolean(KEY_REDUCE_MOTION, s.reduceMotion)
        putBoolean(KEY_VOICE_FEEDBACK, s.voiceFeedback)
        putBoolean(KEY_HAPTICS, s.hapticFeedback)
        putFloat(KEY_SPEECH_RATE, s.speechRate)
        putFloat(KEY_SPEECH_PITCH, s.speechPitch)
        putBoolean(KEY_SPEAK_ON_TAP, s.speakOnTap)
        putBoolean(KEY_AUTO_READ, s.autoReadScans)
        putString(KEY_CONTACT_NAME, s.emergencyContactName)
        putString(KEY_CONTACT_PHONE, s.emergencyContactPhone)
        putString(KEY_EMERGENCY_MESSAGE, s.emergencyMessage)
        putBoolean(KEY_DIRECT_SMS, s.directSms)
        putBoolean(KEY_CLOUD_LOGGING, s.cloudLogging)
        putBoolean(KEY_ONBOARDING, s.onboardingComplete)
    }

    companion object {
        private const val PREFS_NAME = "senseconnect_settings"
        private const val KEY_INSTALL_ID = "installation_id"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_SPEECH_PITCH = "speech_pitch"
        private const val KEY_SPEAK_ON_TAP = "speak_on_tap"
        private const val KEY_AUTO_READ = "auto_read_scans"
        private const val KEY_CONTACT_NAME = "emergency_contact_name"
        private const val KEY_CONTACT_PHONE = "emergency_contact_phone"
        private const val KEY_EMERGENCY_MESSAGE = "emergency_message"
        private const val KEY_DIRECT_SMS = "direct_sms"
        private const val KEY_CLOUD_LOGGING = "cloud_logging"
        private const val KEY_ONBOARDING = "onboarding_complete"

        /**
         * Synchronous read used in Activity.attachBaseContext, before the container exists
         * for that context.
         */
        fun readTextScale(context: Context): Float {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return enumOrDefault(prefs.getString(KEY_TEXT_SIZE, null), TextSize.DEFAULT).scale
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }
}
