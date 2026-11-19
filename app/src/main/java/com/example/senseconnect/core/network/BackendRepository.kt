package com.example.senseconnect.core.network

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import com.example.senseconnect.core.location.LocationFix
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface BackendStatus {
    data object Unknown : BackendStatus
    data object Checking : BackendStatus
    /** Health check is taking long - typically a Render free instance waking from sleep. */
    data object Waking : BackendStatus
    data class Online(val latencyMs: Long, val version: String, val checkedAt: Long) : BackendStatus
    data class Offline(val reason: String) : BackendStatus
}

/** Configuration served by the backend, with safe built-in defaults for offline use. */
data class RemoteConfig(
    val emergencyNumbers: Map<String, String>,
    val defaultEmergencyNumber: String,
    val announcement: String?,
    val fromServer: Boolean,
) {
    fun emergencyNumberFor(countryIso: String?): String =
        countryIso?.uppercase()?.let { emergencyNumbers[it] } ?: defaultEmergencyNumber

    companion object {
        val DEFAULT = RemoteConfig(
            emergencyNumbers = mapOf("IN" to "112", "US" to "911", "CA" to "911", "GB" to "999", "AU" to "000"),
            defaultEmergencyNumber = "112",
            announcement = null,
            fromServer = false,
        )
    }
}

data class IncidentReceipt(val id: String, val receivedAt: String)

/**
 * Single entry point for the Render-hosted backend (UI → ViewModel → Repository → ApiClient).
 *
 * Core assistive features never depend on this class: OCR, TTS, speech recognition, phrases and
 * GPS all work offline. The backend adds health monitoring, remote configuration and an
 * emergency incident log.
 */
class BackendRepository(
    private val api: ApiClient,
    private val prefs: SharedPreferences,
    private val network: NetworkMonitor,
) {
    val baseUrl: String get() = api.baseUrl

    private val _status = MutableStateFlow<BackendStatus>(BackendStatus.Unknown)
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow(readCachedConfig())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()

    /**
     * GET {baseUrl}/health. Shows Checking, then Waking if the server is slow to answer (a sleeping
     * Render instance needs ~30-60 s), then Online or Offline with a human-readable reason.
     */
    suspend fun refreshHealth(): BackendStatus = coroutineScope {
        if (!network.online.value) {
            return@coroutineScope BackendStatus.Offline("No internet connection").also { _status.value = it }
        }
        _status.value = BackendStatus.Checking
        val wakingHint = launch {
            delay(WAKING_HINT_MS)
            if (_status.value == BackendStatus.Checking) _status.value = BackendStatus.Waking
        }
        val started = SystemClock.elapsedRealtime()
        val result = try {
            val json = api.get("health")
            if (json.optString("status") == "ok" && json.optString("service") == "SenseConnect") {
                BackendStatus.Online(
                    latencyMs = SystemClock.elapsedRealtime() - started,
                    version = json.optString("version", "?"),
                    checkedAt = System.currentTimeMillis(),
                )
            } else {
                BackendStatus.Offline("Unexpected response from server")
            }
        } catch (e: Exception) {
            BackendStatus.Offline(describe(e))
        } finally {
            wakingHint.cancel()
        }
        _status.value = result
        result
    }

    /** Maps network exceptions to messages a user can act on. */
    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Server did not respond in time (it may still be waking up)"
        is UnknownHostException -> "Cannot reach the server - check your internet connection"
        is SSLException -> "Secure (HTTPS) connection failed"
        is ApiException -> if ((e.statusCode ?: 0) >= 500) "Server error (HTTP ${e.statusCode})" else e.message ?: "Request rejected"
        is IOException -> "Connection failed - ${e.message ?: "network error"}"
        else -> e.message ?: "Server unreachable"
    }

    suspend fun refreshConfig() {
        if (!network.online.value) return
        runCatching { api.get("api/v1/config") }.getOrNull()?.let { json ->
            prefs.edit { putString(KEY_CONFIG, json.toString()) }
            _config.value = parseConfig(json, fromServer = true)
        }
    }

    /** Logs an SOS incident. Returns a receipt only when the server confirms it was stored. */
    suspend fun reportIncident(
        installationId: String,
        triggeredAt: Long,
        location: LocationFix?,
        contactConfigured: Boolean,
        appVersion: String,
    ): Result<IncidentReceipt> = runCatching {
        val body = JSONObject()
            .put("deviceId", installationId)
            .put("triggeredAt", triggeredAt)
            .put("contactConfigured", contactConfigured)
            .put("appVersion", appVersion)
        if (location != null) {
            body.put(
                "location",
                JSONObject()
                    .put("lat", location.latitude)
                    .put("lng", location.longitude)
                    .put("accuracy", location.accuracyMeters?.toDouble() ?: JSONObject.NULL)
            )
        }
        val json = try {
            api.post("api/v1/incidents", body)
        } catch (e: Exception) {
            throw IOException(describe(e), e)
        }
        IncidentReceipt(id = json.getString("id"), receivedAt = json.optString("receivedAt"))
    }

    suspend fun resolveIncident(installationId: String, incidentId: String): Result<Unit> = runCatching {
        api.post("api/v1/incidents/$incidentId/resolve", JSONObject().put("deviceId", installationId))
        Unit
    }

    private fun readCachedConfig(): RemoteConfig =
        prefs.getString(KEY_CONFIG, null)
            ?.let { runCatching { parseConfig(JSONObject(it), fromServer = true) }.getOrNull() }
            ?: RemoteConfig.DEFAULT

    private fun parseConfig(json: JSONObject, fromServer: Boolean): RemoteConfig {
        val numbers = mutableMapOf<String, String>()
        json.optJSONObject("emergencyNumbers")?.let { obj ->
            obj.keys().forEach { key -> numbers[key.uppercase()] = obj.getString(key) }
        }
        return RemoteConfig(
            emergencyNumbers = numbers.ifEmpty { RemoteConfig.DEFAULT.emergencyNumbers },
            defaultEmergencyNumber = json.optString("defaultEmergencyNumber", "112"),
            announcement = json.optString("announcement").takeIf { it.isNotBlank() && it != "null" },
            fromServer = fromServer,
        )
    }

    private companion object {
        const val KEY_CONFIG = "remote_config"
        const val WAKING_HINT_MS = 4_000L
    }
}
