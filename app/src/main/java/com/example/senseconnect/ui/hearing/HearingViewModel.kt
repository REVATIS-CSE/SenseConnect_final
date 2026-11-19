package com.example.senseconnect.ui.hearing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.activitylog.ActivityType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class TranscriptSegment(val id: Long, val text: String, val timestamp: Long)

enum class ListenPhase { IDLE, STARTING, LISTENING, HEARING_SPEECH, PROCESSING }

data class HearingError(val message: String, val needsPermission: Boolean = false, val retryable: Boolean = true)

data class HearingUiState(
    val phase: ListenPhase = ListenPhase.IDLE,
    val partial: String = "",
    val segments: List<TranscriptSegment> = emptyList(),
    val level: Float = 0f,
    val error: HearingError? = null,
    val sessionStartedAt: Long? = null,
    val offlineMode: Boolean = false,
) {
    val isActive get() = phase != ListenPhase.IDLE
    val fullTranscript get() = segments.joinToString("\n") { it.text }
}

/**
 * Continuous live transcription built on Android's [SpeechRecognizer].
 *
 * Android delivers one utterance per recognition session, so the ViewModel restarts the
 * recogniser after each result to provide uninterrupted captions. When the phone is offline it
 * asks for the on-device engine (EXTRA_PREFER_OFFLINE). Audio is never recorded or stored.
 */
class HearingViewModel(private val container: AppContainer) : ViewModel() {

    private val context: Context get() = container.appContext

    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var restartJob: Job? = null
    private var watchdogJob: Job? = null
    private var segmentCounter = 0L
    private var sessionSegments = 0
    private var sessionWords = 0

    private val _state = MutableStateFlow(HearingUiState())
    val state: StateFlow<HearingUiState> = _state.asStateFlow()

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) {
            _state.update { it.copy(error = HearingError("No speech recognition service is installed on this device.", retryable = false)) }
            return
        }
        wantListening = true
        sessionSegments = 0
        sessionWords = 0
        _state.update {
            it.copy(
                phase = ListenPhase.STARTING,
                error = null,
                sessionStartedAt = SystemClock.elapsedRealtime(),
                offlineMode = !container.network.online.value,
            )
        }
        container.speech.stop()
        listenOnce()
    }

    fun stop() {
        wantListening = false
        restartJob?.cancel()
        watchdogJob?.cancel()
        recognizer?.cancel()
        val partial = _state.value.partial
        if (partial.isNotBlank()) appendSegment(partial)
        _state.update { it.copy(phase = ListenPhase.IDLE, partial = "", level = 0f, sessionStartedAt = null) }
        logSession()
    }

    fun clear() {
        _state.update { it.copy(segments = emptyList(), partial = "") }
    }

    fun onPermissionDenied() {
        wantListening = false
        _state.update {
            it.copy(phase = ListenPhase.IDLE, error = HearingError("Microphone access is needed to turn speech into text.", needsPermission = true))
        }
    }

    private fun listenOnce() {
        if (!wantListening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Offline-first: use the on-device engine whenever there is no internet.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !container.network.online.value)
        }
        try {
            r.startListening(intent)
            armWatchdog()
        } catch (e: Exception) {
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /**
     * In noisy places the recogniser may never detect end-of-speech. Cap each session so captions
     * keep flowing: commit whatever partial text we have and start a fresh session.
     */
    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            delay(MAX_SESSION_MS)
            if (!wantListening) return@launch
            recognizer?.cancel()
            val partial = _state.value.partial
            if (partial.isNotBlank()) appendSegment(partial)
            _state.update { it.copy(partial = "", phase = ListenPhase.LISTENING, level = 0f) }
            scheduleRestart(150)
        }
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean = false) {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            if (recreate) {
                recognizer?.destroy()
                recognizer = null
            }
            delay(delayMs)
            listenOnce()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.update { it.copy(phase = ListenPhase.LISTENING, error = null) }
        }

        override fun onBeginningOfSpeech() {
            _state.update { it.copy(phase = ListenPhase.HEARING_SPEECH) }
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(level = it.level * 0.5f + normalized * 0.5f) }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _state.update { it.copy(phase = ListenPhase.PROCESSING, level = 0f) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) _state.update { it.copy(partial = text) }
        }

        override fun onResults(results: Bundle?) {
            watchdogJob?.cancel()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) appendSegment(text)
            _state.update { it.copy(partial = "") }
            if (wantListening) scheduleRestart(150) else _state.update { it.copy(phase = ListenPhase.IDLE) }
        }

        override fun onError(error: Int) = handleError(error)

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleError(code: Int) {
        watchdogJob?.cancel()
        if (!wantListening) return
        when (code) {
            // Silence or nothing intelligible: keep captioning.
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                _state.update { it.copy(phase = ListenPhase.LISTENING, level = 0f) }
                scheduleRestart(100)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(400, recreate = true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onPermissionDenied()
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                fail("Speech recognition could not reach its service. Connect to the internet, or install an offline speech pack in Android settings.")
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                fail("Your language isn't available for offline recognition. Connect to the internet or download the language pack.")
            ERROR_TOO_MANY_REQUESTS -> fail("The speech service is busy. Please wait a moment and try again.")
            else -> fail("Speech recognition stopped unexpectedly (code $code).")
        }
    }

    private fun fail(message: String) {
        wantListening = false
        restartJob?.cancel()
        _state.update { it.copy(phase = ListenPhase.IDLE, level = 0f, error = HearingError(message)) }
        logSession()
    }

    private fun appendSegment(text: String) {
        val clean = text.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        sessionSegments++
        sessionWords += clean.split(Regex("\\s+")).count { it.isNotBlank() }
        _state.update {
            it.copy(segments = it.segments + TranscriptSegment(++segmentCounter, clean, System.currentTimeMillis()), partial = "")
        }
    }

    /** History keeps only counts — transcripts are sensitive and are never persisted. */
    private fun logSession() {
        if (sessionSegments == 0) return
        container.activityLog.log(
            ActivityType.HEARING,
            "Live transcription",
            "$sessionSegments phrases · $sessionWords words captioned",
        )
        sessionSegments = 0
        sessionWords = 0
    }

    override fun onCleared() {
        wantListening = false
        logSession()
        recognizer?.destroy()
        recognizer = null
        super.onCleared()
    }

    private companion object {
        // Added in API 31; referenced by value so the app still compiles for minSdk 26.
        const val MAX_SESSION_MS = 15_000L
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
