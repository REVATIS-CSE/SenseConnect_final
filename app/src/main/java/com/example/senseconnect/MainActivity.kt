package com.example.senseconnect

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.senseconnect.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDeviceStatus.text = "● Device Disconnected"

        binding.btnBlind.setOnClickListener {
            startActivity(Intent(this, BlindActivity::class.java))
        }

        binding.btnDeaf.setOnClickListener {
            startActivity(Intent(this, DeafActivity::class.java))
        }

        binding.btnSpeech.setOnClickListener {
            startActivity(Intent(this, SpeechActivity::class.java))
        }

        binding.btnSos.setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }
    }
}