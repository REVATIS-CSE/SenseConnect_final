package com.example.senseconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.senseconnect.databinding.ActivityEmergencyBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class EmergencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmergencyBinding

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                getCurrentLocation()
            } else {
                binding.tvLocation.text =
                    "Location permission denied."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSos.setOnClickListener {
            triggerEmergency()
        }

        binding.btnGetLocation.setOnClickListener {
            checkLocationPermission()
        }

        binding.btnEmergencyBack.setOnClickListener {
            finish()
        }
    }

    private fun triggerEmergency() {

        binding.tvEmergencyStatus.text =
            "🚨 EMERGENCY ALERT!"

        binding.tvEmergencyMessage.text =
            "Emergency button has been pressed.\n\n" +
                    "Getting your current location..."

        checkLocationPermission()
    }

    private fun checkLocationPermission() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {

            getCurrentLocation()

        } else {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun getCurrentLocation() {

        val fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        val cancellationTokenSource =
            CancellationTokenSource()

        try {

            binding.tvLocation.text =
                "Getting current location..."

            fusedLocationClient
                .getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                )
                .addOnSuccessListener { location ->

                    if (location != null) {

                        val latitude =
                            location.latitude

                        val longitude =
                            location.longitude

                        binding.tvLocation.text =
                            "Latitude: $latitude\n" +
                                    "Longitude: $longitude"

                        binding.tvEmergencyMessage.text =
                            "🚨 Emergency! I need help.\n\n" +
                                    "My current location:\n" +
                                    "https://maps.google.com/?q=$latitude,$longitude"

                    } else {

                        binding.tvLocation.text =
                            "Unable to get current location."

                        binding.tvEmergencyMessage.text =
                            "GPS signal is unavailable. " +
                                    "Please try again."
                    }
                }
                .addOnFailureListener { error ->

                    binding.tvLocation.text =
                        "Location error: ${error.message}"

                }

        } catch (e: SecurityException) {

            Toast.makeText(
                this,
                "Location permission is required.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}