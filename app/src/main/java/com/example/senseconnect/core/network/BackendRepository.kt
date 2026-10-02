package com.example.senseconnect.core.network

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import com.example.senseconnect.core.location.LocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

sealed interface BackendStatus {
    data object Unknown : BackendStatus
    data object Checking : BackendStatus
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

    suspend fun refreshHealth(): BackendStatus {
        if (!network.online.value) {
            return BackendStatus.Offline("No internet connection").also { _status.value = it }
        }
        _status.value = BackendStatus.Checking
        val started = SystemClock.elapsedRealtime()
        val result = try {
            val json = api.get("health")
            if (json.optString("status") == "ok") {
                BackendStatus.Online(
                    latencyMs = SystemClock.elapsedRealtime() - started,
                    version = json.optString("version", "?"),
                    checkedAt = System.currentTimeMillis(),
                )
            } else {
                BackendStatus.Offline("Unexpected health response")
            }
        } catch (e: Exception) {
            BackendStatus.Offline(e.message ?: "Server unreachable")
        }
        _status.value = result
        return result
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
        val json = api.post("api/v1/incidents", body)
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
    }
}
