package com.example.senseconnect.core.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.senseconnect.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-wide on-device text-to-speech engine shared by Vision (read aloud), Communication
 * (speak phrases), Hearing (typed replies) and voice feedback.
 *
 * Speech rate and pitch are read from [SettingsRepository] on every utterance so Settings
 * changes apply instantly.
 */
class SpeechOutput(
    private val context: Context,
    private val settings: SettingsRepository,
) : TextToSpeech.OnInitListener {

    sealed interface State {
        data object Initializing : State
        data class Ready(val locale: Locale) : State
        data class Unavailable(val reason: String) : State
    }

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicInteger()

    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    val isReady: Boolean get() = _state.value is State.Ready

    fun initialize() {
        if (tts != null) return
        _state.value = State.Initializing
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        // Voice selection loads voice data and can block for seconds: do it off the UI thread.
        Thread({ configureEngine(status) }, "tts-init").start()
    }

    private fun configureEngine(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            _state.value = State.Unavailable("No text-to-speech engine is installed on this device.")
            return
        }
        val locale = listOf(Locale.getDefault(), Locale.US).firstOrNull { candidate ->
            val result = engine.setLanguage(candidate)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (locale == null) {
            _state.value = State.Unavailable("Voice data is missing. Install a voice in Android text-to-speech settings.")
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = post { _speaking.value = true }
            override fun onDone(utteranceId: String?) = post { _speaking.value = engine.isSpeaking }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = post { _speaking.value = false }
            override fun onError(utteranceId: String?, errorCode: Int) = post { _speaking.value = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = post { _speaking.value = false }
        })
        _state.value = State.Ready(locale)
    }

    /** Speaks [text]. Returns false if the engine is not ready or the text is blank. */
    fun speak(text: String, flush: Boolean = true): Boolean {
        val engine = tts ?: return false
        if (!isReady || text.isBlank()) return false
        val prefs = settings.current
        engine.setSpeechRate(prefs.speechRate)
        engine.setPitch(prefs.speechPitch)
        val result = engine.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "sc-${utteranceCounter.incrementAndGet()}",
        )
        return result == TextToSpeech.SUCCESS
    }

    /** Short spoken confirmation, only when the user has Voice Feedback enabled. */
    fun announce(text: String) {
        if (settings.current.voiceFeedback) speak(text, flush = false)
    }

    fun stop() {
        tts?.stop()
        _speaking.value = false
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
