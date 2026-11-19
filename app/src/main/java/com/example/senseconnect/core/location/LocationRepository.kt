package com.example.senseconnect.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** A real GPS / network fix. Coordinates are never hard-coded or simulated. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    /** False when we fell back to the last known location because no fresh fix arrived in time. */
    val isFresh: Boolean,
) {
    val mapsUrl: String
        get() = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", latitude, longitude)

    val coordinatesText: String
        get() = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
}

sealed interface LocationResult {
    data class Success(val fix: LocationFix) : LocationResult
    data object PermissionDenied : LocationResult
    data object ServicesDisabled : LocationResult
    data class Unavailable(val reason: String) : LocationResult
}

class LocationRepository(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Requests a fresh high-accuracy fix. If none arrives within [timeoutMs], falls back to the
     * last known location (flagged with isFresh = false) so SOS still has something useful.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun getCurrentLocation(timeoutMs: Long = 15_000): LocationResult {
        if (!hasPermission()) return LocationResult.PermissionDenied
        if (!isLocationEnabled()) return LocationResult.ServicesDisabled

        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(30_000)
                .build()
            val tokenSource = CancellationTokenSource()
            val fresh = withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(request, tokenSource.token).await()
            }
            if (fresh == null) tokenSource.cancel()

            val location = fresh ?: client.lastLocation.await()
            if (location != null) {
                LocationResult.Success(location.toFix(isFresh = fresh != null))
            } else {
                LocationResult.Unavailable("No GPS signal yet. Move near a window or outdoors and try again.")
            }
        } catch (e: SecurityException) {
            LocationResult.PermissionDenied
        } catch (e: Exception) {
            LocationResult.Unavailable(e.message ?: "Location services returned an error.")
        }
    }

    private fun Location.toFix(isFresh: Boolean) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = time,
        isFresh = isFresh,
    )

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
    }
}
