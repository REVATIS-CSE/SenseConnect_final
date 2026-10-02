package com.example.senseconnect.core

import android.content.Context
import com.example.senseconnect.BuildConfig
import com.example.senseconnect.core.activitylog.ActivityRepository
import com.example.senseconnect.core.location.LocationRepository
import com.example.senseconnect.core.network.ApiClient
import com.example.senseconnect.core.network.BackendRepository
import com.example.senseconnect.core.network.NetworkMonitor
import com.example.senseconnect.core.settings.SettingsRepository
import com.example.senseconnect.core.speech.SpeechOutput
import com.example.senseconnect.core.status.ServiceStatusRepository
import com.example.senseconnect.ui.communication.PhraseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Keeps the app free of a DI framework while still giving
 * every screen one shared instance of each repository.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Scope for app-wide background work (backend health checks, config refresh). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(appContext)
    val activityLog = ActivityRepository(appContext)
    val speech = SpeechOutput(appContext, settings)
    val location = LocationRepository(appContext)
    val network = NetworkMonitor(appContext)
    val backend = BackendRepository(
        api = ApiClient(BuildConfig.API_BASE_URL),
        prefs = appContext.getSharedPreferences("senseconnect_backend", Context.MODE_PRIVATE),
        network = network,
    )
    val phrases = PhraseRepository(appContext)
    val serviceStatus = ServiceStatusRepository(appContext, speech, location, backend, network)

    fun start() {
        network.start()
        speech.initialize()
        appScope.launch {
            backend.refreshHealth()
            backend.refreshConfig()
        }
        // Re-check the cloud automatically whenever the phone regains internet.
        appScope.launch {
            network.online.drop(1).filter { it }.collect {
                backend.refreshHealth()
                backend.refreshConfig()
            }
        }
    }
}
