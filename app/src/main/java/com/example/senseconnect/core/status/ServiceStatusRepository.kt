package com.example.senseconnect.core.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.senseconnect.R
import com.example.senseconnect.core.location.LocationRepository
import com.example.senseconnect.core.network.BackendRepository
import com.example.senseconnect.core.network.BackendStatus
import com.example.senseconnect.core.network.NetworkMonitor
import com.example.senseconnect.core.speech.SpeechOutput
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Semantic tone of a status. Always rendered with text + icon, never colour alone. */
enum class Tone { SUCCESS, WARNING, DANGER, NEUTRAL, INFO }

enum class ServiceId { CAMERA, MICROPHONE, SPEECH, LOCATION, CLOUD }

/** What tapping a status row should do to fix it. */
enum class FixAction { REQUEST_CAMERA, REQUEST_MICROPHONE, REQUEST_LOCATION, OPEN_LOCATION_SETTINGS, OPEN_TTS_SETTINGS, RETRY_CLOUD }

data class ServiceStatus(
    val id: ServiceId,
    val label: String,
    val detail: String,
    val stateLabel: String,
    val tone: Tone,
    val iconRes: Int,
    val fix: FixAction? = null,
)

/** Aggregates the real availability of every subsystem SenseConnect relies on. */
class ServiceStatusRepository(
    private val context: Context,
    private val speech: SpeechOutput,
    private val location: LocationRepository,
    private val backend: BackendRepository,
    private val network: NetworkMonitor,
) {

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val hasCameraHardware: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val playServicesAvailable: Boolean
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    val speechRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun snapshot(): List<ServiceStatus> = listOf(camera(), microphone(), speechEngine(), gps(), cloud())

    fun camera(): ServiceStatus {
        val base = ServiceStatus(ServiceId.CAMERA, "Camera & OCR", "", "", Tone.SUCCESS, R.drawable.ic_camera_alt)
        return when {
            !hasCameraHardware -> base.copy(detail = "No camera on this device", stateLabel = "Unavailable", tone = Tone.NEUTRAL)
            !hasPermission(Manifest.permission.CAMERA) ->
                base.copy(detail = "Tap to allow camera access", stateLabel = "Permission needed", tone = Tone.WARNING, fix = FixAction.REQUEST_CAMERA)
            !playServicesAvailable ->
                base.copy(detail = "Google Play services required for OCR", stateLabel = "Limited", tone = Tone.WARNING)
            else -> base.copy(detail = "On-device text recognition", stateLabel = "Ready")
        }
    }

    fun microphone(): ServiceStatus {
        val base = ServiceStatus(ServiceId.MICROPHONE, "Microphone & transcription", "", "", Tone.SUCCESS, R.drawable.ic_mic)
        return when {
            !speechRecognitionAvailable ->
                base.copy(detail = "No speech recognition service installed", stateLabel = "Unavailable", tone = Tone.NEUTRAL)
            !hasPermission(Manifest.permission.RECORD_AUDIO) ->
                base.copy(detail = "Tap to allow microphone access", stateLabel = "Permission needed", tone = Tone.WARNING, fix = FixAction.REQUEST_MICROPHONE)
            else -> base.copy(detail = "Live speech-to-text", stateLabel = "Ready")
        }
    }

    fun speechEngine(): ServiceStatus {
        val base = ServiceStatus(ServiceId.SPEECH, "Text-to-speech", "", "", Tone.SUCCESS, R.drawable.ic_volume_up)
        return when (val s = speech.state.value) {
            is SpeechOutput.State.Ready -> base.copy(detail = "Voice: ${s.locale.displayName}", stateLabel = "Ready")
            SpeechOutput.State.Initializing -> base.copy(detail = "Starting voice engine", stateLabel = "Starting", tone = Tone.INFO)
            is SpeechOutput.State.Unavailable ->
                base.copy(detail = s.reason, stateLabel = "Unavailable", tone = Tone.DANGER, fix = FixAction.OPEN_TTS_SETTINGS)
        }
    }

    fun gps(): ServiceStatus {
        val base = ServiceStatus(ServiceId.LOCATION, "Location (GPS)", "", "", Tone.SUCCESS, R.drawable.ic_location_on)
        return when {
            !location.hasPermission() ->
                base.copy(detail = "Tap to allow location for SOS", stateLabel = "Permission needed", tone = Tone.WARNING, fix = FixAction.REQUEST_LOCATION)
            !location.isLocationEnabled() ->
                base.copy(detail = "Location is turned off - tap to enable", stateLabel = "Turned off", tone = Tone.WARNING, fix = FixAction.OPEN_LOCATION_SETTINGS)
            else -> base.copy(detail = "Used to share position in emergencies", stateLabel = "Ready")
        }
    }

    fun cloud(): ServiceStatus {
        val base = ServiceStatus(ServiceId.CLOUD, "SenseConnect Cloud", "", "", Tone.SUCCESS, R.drawable.ic_cloud_done)
        if (!network.online.value) {
            return base.copy(detail = "Offline - assistive features still work", stateLabel = "Offline", tone = Tone.NEUTRAL, iconRes = R.drawable.ic_cloud_off, fix = FixAction.RETRY_CLOUD)
        }
        return when (val s = backend.status.value) {
            is BackendStatus.Online -> base.copy(detail = "Connected over HTTPS · ${s.latencyMs} ms", stateLabel = "Online")
            BackendStatus.Checking, BackendStatus.Unknown ->
                base.copy(detail = "Contacting server", stateLabel = "Connecting", tone = Tone.INFO, iconRes = R.drawable.ic_cloud_queue)
            BackendStatus.Waking ->
                base.copy(detail = "Server is waking up - this can take up to a minute", stateLabel = "Waking", tone = Tone.INFO, iconRes = R.drawable.ic_cloud_queue)
            is BackendStatus.Offline ->
                base.copy(detail = "${s.reason}. Tap to retry", stateLabel = "Offline", tone = Tone.WARNING, iconRes = R.drawable.ic_cloud_off, fix = FixAction.RETRY_CLOUD)
        }
    }
}
