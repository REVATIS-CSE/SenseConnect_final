package com.example.senseconnect.ui.emergency

import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.senseconnect.BuildConfig
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.core.emergency.AlarmPlayer
import com.example.senseconnect.core.emergency.EmergencyMessageBuilder
import com.example.senseconnect.core.emergency.SmsResult
import com.example.senseconnect.core.emergency.SmsSender
import com.example.senseconnect.core.location.LocationFix
import com.example.senseconnect.core.location.LocationResult
import com.example.senseconnect.core.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SosPhase { READY, COUNTDOWN, ACTIVE }

sealed interface LocationUi {
    data object Loading : LocationUi
    data class Available(val fix: LocationFix) : LocationUi
    data object PermissionDenied : LocationUi
    data object Disabled : LocationUi
    data class Unavailable(val reason: String) : LocationUi
}

/** Delivery state of each SOS channel. Only marked successful after real confirmation. */
sealed interface Delivery {
    data object Idle : Delivery
    data object InProgress : Delivery
    data class Done(val detail: String) : Delivery
    data class Failed(val reason: String) : Delivery
    data class Skipped(val reason: String) : Delivery
}

data class EmergencyUiState(
    val phase: SosPhase = SosPhase.READY,
    val countdown: Int = 0,
    val location: LocationUi = LocationUi.Loading,
    val settings: AppSettings = AppSettings(),
    val activatedAt: Long? = null,
    val sms: Delivery = Delivery.Idle,
    val cloud: Delivery = Delivery.Idle,
    val alarmOn: Boolean = false,
    val emergencyNumber: String = "112",
) {
    val fix: LocationFix? get() = (location as? LocationUi.Available)?.fix
    val message: String
        get() = EmergencyMessageBuilder.build(settings.emergencyMessage, fix, activatedAt ?: System.currentTimeMillis())
}

/**
 * SOS workflow: Location → Emergency message → SMS / Call / Share, plus optional cloud incident
 * logging. Activation is deliberately two-step (press-and-hold, then a cancellable countdown).
 */
class EmergencyViewModel(private val container: AppContainer) : ViewModel() {

    private val smsSender = SmsSender(container.appContext)
    private val alarm = AlarmPlayer(container.appContext)
    private var countdownJob: Job? = null
    private var incidentId: String? = null

    private val _state = MutableStateFlow(
        EmergencyUiState(settings = container.settings.current, emergencyNumber = resolveEmergencyNumber())
    )
    val state: StateFlow<EmergencyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            container.backend.config.collect { _state.update { it.copy(emergencyNumber = resolveEmergencyNumber()) } }
        }
        refreshLocation()
    }

    fun refreshLocation() {
        _state.update { it.copy(location = LocationUi.Loading) }
        viewModelScope.launch { _state.update { it.copy(location = fetchLocation()) } }
    }

    private suspend fun fetchLocation(timeoutMs: Long = 15_000): LocationUi =
        when (val r = container.location.getCurrentLocation(timeoutMs)) {
            is LocationResult.Success -> LocationUi.Available(r.fix)
            LocationResult.PermissionDenied -> LocationUi.PermissionDenied
            LocationResult.ServicesDisabled -> LocationUi.Disabled
            is LocationResult.Unavailable -> LocationUi.Unavailable(r.reason)
        }

    fun startCountdown() {
        if (_state.value.phase != SosPhase.READY) return
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (s in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(phase = SosPhase.COUNTDOWN, countdown = s) }
                delay(1_000)
            }
            activate()
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        _state.update { it.copy(phase = SosPhase.READY, countdown = 0) }
        container.speech.announce("Emergency alert cancelled.")
    }

    /** Activates immediately (used after explicit confirmation, e.g. from TalkBack). */
    fun activateNow() {
        countdownJob?.cancel()
        viewModelScope.launch { activate() }
    }

    private suspend fun activate() {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(phase = SosPhase.ACTIVE, countdown = 0, activatedAt = now, sms = Delivery.InProgress, cloud = Delivery.InProgress)
        }
        container.speech.announce("Emergency alert activated.")

        // 1. Location: always try for a fresh fix at activation time.
        if (container.location.hasPermission()) {
            val current = _state.value.location
            _state.update { it.copy(location = if (current is LocationUi.Available) current else LocationUi.Loading) }
            val fresh = fetchLocation(timeoutMs = 10_000)
            if (fresh is LocationUi.Available || _state.value.location !is LocationUi.Available) {
                _state.update { it.copy(location = fresh) }
            }
        } else {
            _state.update { it.copy(location = LocationUi.PermissionDenied) }
        }

        val settings = _state.value.settings
        val message = _state.value.message

        // 2. SMS (direct only when the user enabled it and Android granted permission).
        viewModelScope.launch {
            val sms = when {
                !settings.hasEmergencyContact -> Delivery.Skipped("No emergency contact configured")
                !settings.directSms || !smsSender.canSendDirectly() ->
                    Delivery.Skipped("Tap “Send SMS” to send from your messaging app")
                else -> when (val r = smsSender.send(settings.emergencyContactPhone, message)) {
                    SmsResult.Sent -> Delivery.Done("Sent to ${settings.emergencyContactName.ifBlank { settings.emergencyContactPhone }} · confirmed by Android")
                    is SmsResult.Failed -> Delivery.Failed(r.reason)
                }
            }
            _state.update { it.copy(sms = sms) }
            logActivation()
        }

        // 3. Cloud incident log (optional, never blocks the local workflow).
        viewModelScope.launch {
            val cloud = when {
                !settings.cloudLogging -> Delivery.Skipped("Cloud logging is turned off in Settings")
                !container.network.online.value -> Delivery.Failed("Offline - incident not logged")
                else -> container.backend.reportIncident(
                    installationId = container.settings.installationId,
                    triggeredAt = now,
                    location = _state.value.fix,
                    contactConfigured = settings.hasEmergencyContact,
                    appVersion = BuildConfig.VERSION_NAME,
                ).fold(
                    onSuccess = { receipt -> incidentId = receipt.id; Delivery.Done("Incident #${receipt.id.take(8)} logged") },
                    onFailure = { e -> Delivery.Failed(e.message ?: "Server unreachable") },
                )
            }
            _state.update { it.copy(cloud = cloud) }
        }
    }

    private var activationLogged = false

    private fun logActivation() {
        if (activationLogged) return
        activationLogged = true
        val s = _state.value
        val parts = buildList {
            add(if (s.fix != null) "Location attached" else "No location")
            when (val sms = s.sms) {
                is Delivery.Done -> add("SMS sent")
                is Delivery.Failed -> add("SMS failed")
                else -> Unit
            }
        }
        container.activityLog.log(ActivityType.SOS, "SOS activated", parts.joinToString(" · "))
    }

    /** Called when the user opened the messaging app / dialer / share sheet themselves. */
    fun recordManualAction(action: String) {
        container.activityLog.log(ActivityType.SOS, action, "Opened by user during SOS")
        if (action.startsWith("SMS") && _state.value.sms !is Delivery.Done) {
            _state.update { it.copy(sms = Delivery.Skipped("Messaging app opened - confirm Send there")) }
        }
    }

    fun toggleAlarm() {
        if (alarm.isPlaying) alarm.stop() else alarm.start()
        _state.update { it.copy(alarmOn = alarm.isPlaying) }
    }

    fun endSos() {
        alarm.stop()
        val id = incidentId
        if (id != null) {
            viewModelScope.launch { container.backend.resolveIncident(container.settings.installationId, id) }
        }
        incidentId = null
        activationLogged = false
        container.activityLog.log(ActivityType.SOS, "SOS ended", "Marked safe by user")
        _state.update {
            it.copy(phase = SosPhase.READY, activatedAt = null, sms = Delivery.Idle, cloud = Delivery.Idle, alarmOn = false)
        }
    }

    private fun resolveEmergencyNumber(): String {
        val telephony = container.appContext.getSystemService(TelephonyManager::class.java)
        val country = telephony?.networkCountryIso?.takeIf { it.isNotBlank() } ?: telephony?.simCountryIso
        return container.backend.config.value.emergencyNumberFor(country)
    }

    override fun onCleared() {
        alarm.stop()
        super.onCleared()
    }

    companion object {
        const val COUNTDOWN_SECONDS = 5
    }
}
