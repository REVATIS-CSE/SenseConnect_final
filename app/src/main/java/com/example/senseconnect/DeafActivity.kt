package com.example.senseconnect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.senseconnect.databinding.ActivityDeafBinding
import java.util.Locale

class DeafActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeafBinding

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                openSpeechRecognizer()
            } else {
                binding.tvListeningStatus.text =
                    "Microphone permission denied."
            }
        }

    private val speechResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                val results =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                if (!results.isNullOrEmpty()) {

                    binding.tvRecognizedText.text =
                        results[0]

                    binding.tvListeningStatus.text =
                        "Speech recognized"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeafBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartListening.setOnClickListener {
            checkMicrophonePermission()
        }

        binding.btnClearSpeech.setOnClickListener {

            binding.tvRecognizedText.text =
                "No speech recognized yet."

            binding.tvListeningStatus.text =
                "Press Start Listening to begin"
        }

        binding.btnDeafBack.setOnClickListener {
            finish()
        }
    }

    private fun checkMicrophonePermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            openSpeechRecognizer()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun openSpeechRecognizer() {

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            Locale.getDefault()
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "Speak now"
        )

        speechResultLauncher.launch(intent)
    }
}