package com.example.senseconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.senseconnect.databinding.ActivityBlindBinding

class BlindActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlindBinding

    // Camera permission request
    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for Vision Assistance",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBlindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Read Text button
        binding.btnReadText.setOnClickListener {
            Toast.makeText(
                this,
                "OCR will be added next",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Read Aloud button
        binding.btnReadAloud.setOnClickListener {
            Toast.makeText(
                this,
                "Text-to-Speech will be added next",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Check camera permission
        checkCameraPermission()
    }

    private fun checkCameraPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            // Permission already granted
            startCamera()

        } else {

            // Ask user for permission
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            // Camera preview
            val preview = Preview.Builder()
                .build()

            // Send camera output to PreviewView
            preview.surfaceProvider =
                binding.previewView.surfaceProvider

            // Use back camera
            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            try {

                // Remove previous camera connections
                cameraProvider.unbindAll()

                // Start camera
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )

            } catch (exception: Exception) {

                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_LONG
                ).show()

                exception.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }
}