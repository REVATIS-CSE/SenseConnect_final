package com.example.senseconnect

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import com.example.senseconnect.databinding.ActivitySpeechBinding
import java.util.Locale

class SpeechActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpeechBinding
    private lateinit var textToSpeech: TextToSpeech

    private var selectedPhrase = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySpeechBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Text-to-Speech safely
        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            }
        }

        binding.btnHelp.setOnClickListener {
            selectPhrase("I need help.")
        }

        binding.btnWater.setOnClickListener {
            selectPhrase("I need water.")
        }

        binding.btnDoctor.setOnClickListener {
            selectPhrase("I need a doctor.")
        }

        binding.btnFood.setOnClickListener {
            selectPhrase("I need food.")
        }

        binding.btnYes.setOnClickListener {
            selectPhrase("Yes.")
        }

        binding.btnNo.setOnClickListener {
            selectPhrase("No.")
        }

        binding.btnSpeak.setOnClickListener {
            speakSelectedPhrase()
        }

        binding.btnSpeechBack.setOnClickListener {
            finish()
        }
    }

    private fun selectPhrase(phrase: String) {

        selectedPhrase = phrase

        binding.tvSelectedPhrase.text = phrase
    }

    private fun speakSelectedPhrase() {

        if (selectedPhrase.isNotEmpty()) {

            textToSpeech.speak(
                selectedPhrase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SenseConnectSpeech"
            )
        }
    }

    override fun onDestroy() {

        textToSpeech.stop()
        textToSpeech.shutdown()

        super.onDestroy()
    }
}