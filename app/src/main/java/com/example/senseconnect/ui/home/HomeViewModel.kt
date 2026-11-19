package com.example.senseconnect.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.senseconnect.core.AppContainer
import com.example.senseconnect.core.activitylog.ActivityEvent
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.core.location.LocationFix
import com.example.senseconnect.core.location.LocationResult
import com.example.senseconnect.core.network.BackendStatus
import com.example.senseconnect.core.settings.AppSettings
import com.example.senseconnect.core.status.ServiceId
import com.example.senseconnect.core.status.ServiceStatus
import com.example.senseconnect.core.status.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val statuses: List<ServiceStatus> = emptyList(),
    val recent: List<ActivityEvent> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val backend: BackendStatus = BackendStatus.Unknown,
    val announcement: String? = null,
) {
    fun status(id: ServiceId) = statuses.firstOrNull { it.id == id }

    /** Local assistive subsystems (cloud is optional and excluded from readiness). */
    val localStatuses get() = statuses.filter { it.id != ServiceId.CLOUD }
    val readyCount get() = localStatuses.count { it.tone == Tone.SUCCESS }
    val allReady get() = localStatuses.isNotEmpty() && readyCount == localStatuses.size
}

sealed interface LocationShareEvent {
    data class Ready(val fix: LocationFix) : LocationShareEvent
    data class Failed(val result: LocationResult) : LocationShareEvent
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _locationEvents = Channel<LocationShareEvent>(Channel.BUFFERED)
    val locationEvents = _locationEvents.receiveAsFlow()

    private val systemSignals = combine(
        refreshTick,
        container.speech.state,
        container.backend.status,
        container.network.online,
    ) { _, _, backend, _ -> backend }

    val state: StateFlow<HomeUiState> = combine(
        systemSignals,
        container.activityLog.events,
        container.settings.settings,
        container.backend.config,
    ) { backend, events, settings, config ->
        HomeUiState(
            statuses = container.serviceStatus.snapshot(),
            recent = events.take(3),
            settings = settings,
            backend = backend,
            announcement = config.announcement,
        )
    }
        // Permission / Play services / recogniser queries are IPC calls - keep them off the UI thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Re-evaluates permissions/hardware (called on resume, after permission dialogs). */
    fun refresh() {
        refreshTick.value++
    }

    fun retryCloud() {
        viewModelScope.launch {
            container.backend.refreshHealth()
            container.backend.refreshConfig()
        }
    }

    fun requestLocationForSharing() {
        if (_locating.value) return
        _locating.value = true
        viewModelScope.launch {
            val result = container.location.getCurrentLocation()
            _locating.value = false
            if (result is LocationResult.Success) {
                container.activityLog.log(
                    ActivityType.LOCATION,
                    "Location shared",
                    "Accuracy ±${result.fix.accuracyMeters?.toInt() ?: "?"} m",
                )
                _locationEvents.send(LocationShareEvent.Ready(result.fix))
            } else {
                _locationEvents.send(LocationShareEvent.Failed(result))
            }
            refresh()
        }
    }
}
