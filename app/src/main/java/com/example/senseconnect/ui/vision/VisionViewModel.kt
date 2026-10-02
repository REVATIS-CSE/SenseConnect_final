package com.example.senseconnect.ui.vision

import androidx.lifecycle.ViewModel
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.activitylog.ActivityType
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanSource { CAMERA, GALLERY }

sealed interface ScanState {
    data object Idle : ScanState
    data object Processing : ScanState
    data class Result(val text: String, val words: Int, val lines: Int, val source: ScanSource) : ScanState
    data object NoText : ScanState
    data class Error(val message: String) : ScanState
}

/**
 * Vision pipeline state: Camera / gallery image → ML Kit on-device OCR → recognised text.
 * Recognition runs fully on the device; images are never stored or uploaded.
 */
class VisionViewModel(private val container: AppContainer) : ViewModel() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Words currently visible to the live analyser (0 = no text in view). */
    private val _liveWords = MutableStateFlow(0)
    val liveWords: StateFlow<Int> = _liveWords.asStateFlow()

    val isProcessing get() = _state.value == ScanState.Processing

    fun recognize(image: InputImage, source: ScanSource) {
        _state.value = ScanState.Processing
        recognizer.process(image)
            .addOnSuccessListener { onRecognized(it, source) }
            .addOnFailureListener { e ->
                _state.value = ScanState.Error(
                    e.message?.let { "Text recognition failed: $it" }
                        ?: "Text recognition failed. Make sure Google Play services is up to date."
                )
            }
    }

    fun failCapture(message: String) {
        _state.value = ScanState.Error(message)
    }

    /** Lightweight continuous check used only to guide the user ("Text detected - hold steady"). */
    fun analyzeLive(image: InputImage): Task<Text> =
        recognizer.process(image).addOnSuccessListener { text ->
            _liveWords.value = text.textBlocks.sumOf { b -> b.lines.sumOf { it.elements.size } }
        }

    fun clear() {
        container.speech.stop()
        _state.value = ScanState.Idle
    }

    private fun onRecognized(result: Text, source: ScanSource) {
        val text = result.textBlocks.joinToString("\n\n") { it.text }.trim()
        if (text.isEmpty()) {
            _state.value = ScanState.NoText
            container.speech.announce("No text found.")
            return
        }
        val lines = result.textBlocks.sumOf { it.lines.size }
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        _state.value = ScanState.Result(text, words, lines, source)

        // History stores only counts — never the scanned text itself.
        container.activityLog.log(
            ActivityType.VISION,
            if (source == ScanSource.CAMERA) "Text scanned" else "Image scanned",
            "$words words · $lines lines recognised on-device",
        )
        if (container.settings.current.autoReadScans) {
            container.speech.speak(text)
        } else {
            container.speech.announce("$words words recognised.")
        }
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }
}
