package com.example.senseconnect.ui.emergency

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.senseconnect.R
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.AppPermissions
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.TimeFormat
import com.example.senseconnect.core.ui.appViewModelFactory
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.core.ui.bindStatusPill
import com.example.senseconnect.core.ui.color
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.openLocationSettings
import com.example.senseconnect.databinding.ActivityEmergencyBinding
import com.example.senseconnect.databinding.ItemSosStatusRowBinding
import com.example.senseconnect.ui.main.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SOS Emergency screen. Visually distinct from everyday features (emergency theme), activated
 * by press-and-hold + cancellable countdown, and honest about what was actually delivered.
 */
class EmergencyActivity : BaseActivity() {

    override val highContrastOverlay = R.style.ThemeOverlay_SenseConnect_HighContrast_Emergency

    private lateinit var binding: ActivityEmergencyBinding
    private val viewModel: EmergencyViewModel by viewModels { appViewModelFactory { EmergencyViewModel(it) } }
    private var locationBlocked = false
    private var lastPhase = SosPhase.READY

    private val locationPermission: PermissionRequester = PermissionRequester(this, { this }) { granted, blocked ->
        locationBlocked = blocked
        if (granted) viewModel.refreshLocation() else render(viewModel.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.root.applySystemBarPadding(top = true)
        binding.content.applySystemBarPadding(bottom = true)

        with(binding.header.root) {
            setTitle(R.string.sos_title)
            setSubtitle(R.string.sos_workflow)
            setNavigationOnClickListener { finish() }
        }

        setupHoldButton()
        setupActions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        if (savedInstanceState == null && !container.location.hasPermission()) {
            locationPermission.request(AppPermissions.LOCATION)
        }
    }

    private fun setupHoldButton() {
        binding.btnHold.listener = object : HoldToActivateButton.Listener {
            override fun onHoldStarted() {
                binding.tvHoldHint.setText(R.string.sos_keep_holding)
            }

            override fun onHoldCancelled() {
                binding.tvHoldHint.setText(R.string.sos_hold_released)
            }

            override fun onHoldCompleted() {
                viewModel.startCountdown()
            }

            override fun onAccessibleActivate() = confirmActivation()
        }
    }

    /** Explicit confirmation path for TalkBack, switch access and keyboard users. */
    private fun confirmActivation() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_sos)
            .setTitle(R.string.sos_confirm_title)
            .setMessage(R.string.sos_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sos_confirm_send) { _, _ -> viewModel.activateNow() }
            .show()
    }

    private fun setupActions() = with(binding) {
        btnCancelCountdown.setOnClickListener {
            Feedback.tap(it)
            viewModel.cancelCountdown()
        }
        btnCallEmergency.setOnClickListener {
            dial(viewModel.state.value.emergencyNumber)
            viewModel.recordManualAction("Emergency services dialer opened")
        }
        btnCallContact.setOnClickListener {
            val s = viewModel.state.value.settings
            if (!s.hasEmergencyContact) return@setOnClickListener promptContactSetup()
            dial(s.emergencyContactPhone)
            viewModel.recordManualAction("Call to emergency contact opened")
        }
        btnSendSms.setOnClickListener {
            val s = viewModel.state.value.settings
            if (!s.hasEmergencyContact) return@setOnClickListener promptContactSetup()
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(s.emergencyContactPhone)}"))
                .putExtra("sms_body", viewModel.state.value.message)
            launch(intent, R.string.no_sms_app)
            viewModel.recordManualAction("SMS composer opened")
        }
        btnShareLocation.setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.state.value.message)
            startActivity(Intent.createChooser(share, getString(R.string.share_location_title)))
            viewModel.recordManualAction("Location shared")
        }
        btnAlarm.setOnClickListener {
            Feedback.tap(it)
            viewModel.toggleAlarm()
        }
        btnEndSos.setOnClickListener {
            MaterialAlertDialogBuilder(this@EmergencyActivity)
                .setTitle(R.string.sos_end_title)
                .setMessage(R.string.sos_end_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sos_end) { _, _ -> viewModel.endSos() }
                .show()
        }
        btnOpenMaps.setOnClickListener {
            val fix = viewModel.state.value.fix ?: return@setOnClickListener
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(fix.mapsUrl)), R.string.no_maps_app)
        }
        rowContact.row.setOnClickListener { if (!viewModel.state.value.settings.hasEmergencyContact) promptContactSetup() }
    }

    private fun render(state: EmergencyUiState) = with(binding) {
        renderBanner(state)

        val active = state.phase == SosPhase.ACTIVE
        val countdown = state.phase == SosPhase.COUNTDOWN
        readySection.visibility = if (active) View.GONE else View.VISIBLE
        activeSection.visibility = if (active) View.VISIBLE else View.GONE
        headerReadiness.visibility = if (active) View.GONE else View.VISIBLE
        readinessCard.visibility = if (active) View.GONE else View.VISIBLE
        btnHold.visibility = if (countdown) View.GONE else View.VISIBLE
        btnCancelCountdown.visibility = if (countdown) View.VISIBLE else View.GONE
        if (countdown) tvHoldHint.text = getString(R.string.sos_countdown_hint, state.countdown)
        else if (lastPhase != SosPhase.READY && state.phase == SosPhase.READY) tvHoldHint.setText(R.string.sos_hold_hint)
        lastPhase = state.phase

        // Keep the screen awake while an emergency is in progress.
        if (active || countdown) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnCallEmergency.text = getString(R.string.call_emergency_services, state.emergencyNumber)
        val s = state.settings
        btnCallContact.text = if (s.hasEmergencyContact) getString(R.string.call_named, s.emergencyContactName.ifBlank { getString(R.string.contact) })
            else getString(R.string.call_contact)
        btnAlarm.setText(if (state.alarmOn) R.string.stop_alarm else R.string.sound_alarm)
        btnAlarm.setIconResource(if (state.alarmOn) R.drawable.ic_stop else R.drawable.ic_notifications_active)

        renderReadiness(state)
        renderDelivery(state)
        renderLocation(state)
        tvMessage.text = state.message
    }

    private fun renderBanner(state: EmergencyUiState) = with(binding) {
        data class Banner(val bg: Int, val fg: Int, val icon: Int, val title: String, val body: String)
        val s = state.settings
        val banner = when (state.phase) {
            SosPhase.READY -> {
                val ready = s.hasEmergencyContact && state.location is LocationUi.Available
                Banner(
                    if (ready) R.color.sc_success_container else R.color.sc_warning_container,
                    if (ready) R.color.sc_on_success_container else R.color.sc_on_warning_container,
                    if (ready) R.drawable.ic_verified_user else R.drawable.ic_warning_amber,
                    getString(R.string.sos_state_ready),
                    getString(if (ready) R.string.sos_ready_body else R.string.sos_ready_partial_body),
                )
            }
            SosPhase.COUNTDOWN -> Banner(
                R.color.sc_warning_container, R.color.sc_on_warning_container, R.drawable.ic_timer,
                getString(R.string.sos_state_countdown, state.countdown), getString(R.string.sos_countdown_body),
            )
            SosPhase.ACTIVE -> Banner(
                R.color.sc_emergency_deep, R.color.white, R.drawable.ic_sos,
                getString(R.string.sos_state_active),
                getString(R.string.sos_active_body, TimeFormat.full(state.activatedAt ?: System.currentTimeMillis())),
            )
        }
        bannerCard.setCardBackgroundColor(color(banner.bg))
        ivBanner.setImageResource(banner.icon)
        ivBanner.imageTintList = ColorStateList.valueOf(color(banner.fg))
        tvBannerOverline.setTextColor(color(banner.fg))
        tvBannerTitle.setTextColor(color(banner.fg))
        tvBannerBody.setTextColor(color(banner.fg))
        tvBannerTitle.text = banner.title
        tvBannerBody.text = banner.body
    }

    private fun renderReadiness(state: EmergencyUiState) = with(binding) {
        val s = state.settings
        rowContact.bind(
            R.drawable.ic_person, getString(R.string.emergency_contact),
            if (s.hasEmergencyContact) listOf(s.emergencyContactName, s.emergencyContactPhone).filter { it.isNotBlank() }.joinToString(" · ")
            else getString(R.string.sos_contact_missing_detail),
            if (s.hasEmergencyContact) Tone.SUCCESS else Tone.WARNING,
            getString(if (s.hasEmergencyContact) R.string.configured else R.string.not_configured),
        )
        val direct = s.directSms && s.hasEmergencyContact
        rowSmsMode.bind(
            R.drawable.ic_sms, getString(R.string.sos_sms_mode),
            getString(if (direct) R.string.sos_sms_direct_detail else R.string.sos_sms_manual_detail),
            if (direct) Tone.SUCCESS else Tone.NEUTRAL,
            getString(if (direct) R.string.automatic else R.string.manual),
        )
        val online = container.network.online.value
        rowCloudMode.bind(
            R.drawable.ic_cloud_queue, getString(R.string.sos_cloud_mode),
            getString(if (!s.cloudLogging) R.string.sos_cloud_off_detail else if (online) R.string.sos_cloud_on_detail else R.string.sos_cloud_offline_detail),
            if (s.cloudLogging && online) Tone.SUCCESS else Tone.NEUTRAL,
            getString(if (!s.cloudLogging) R.string.off else if (online) R.string.online else R.string.offline),
        )
    }

    private fun renderDelivery(state: EmergencyUiState) = with(binding) {
        val s = state.settings
        val target = s.emergencyContactName.ifBlank { s.emergencyContactPhone }.ifBlank { getString(R.string.contact) }
        rowSms.bindDelivery(R.drawable.ic_sms, getString(R.string.sos_sms_to, target), state.sms)
        rowCloud.bindDelivery(R.drawable.ic_cloud_queue, getString(R.string.sos_cloud_log), state.cloud)
    }

    private fun renderLocation(state: EmergencyUiState) = with(binding) {
        val loc = state.location
        locationProgress.visibility = if (loc == LocationUi.Loading) View.VISIBLE else View.GONE
        locationGrid.visibility = if (loc is LocationUi.Available) View.VISIBLE else View.GONE
        tvLocationMessage.visibility = if (loc is LocationUi.Available) View.GONE else View.VISIBLE
        btnOpenMaps.isEnabled = loc is LocationUi.Available
        btnLocationAction.isEnabled = loc != LocationUi.Loading

        when (loc) {
            LocationUi.Loading -> {
                tvLocationTitle.setText(R.string.location_loading)
                tvLocationMessage.setText(R.string.location_loading_body)
                setLocationAction(R.string.refresh, R.drawable.ic_refresh) { viewModel.refreshLocation() }
            }
            is LocationUi.Available -> {
                val fix = loc.fix
                tvLocationTitle.setText(if (fix.isFresh) R.string.location_available else R.string.location_last_known)
                tvLat.text = String.format(Locale.US, "%.5f", fix.latitude)
                tvLng.text = String.format(Locale.US, "%.5f", fix.longitude)
                tvAccuracy.text = fix.accuracyMeters?.let { getString(R.string.accuracy_meters, it.roundToInt()) } ?: getString(R.string.unknown)
                tvUpdated.text = TimeFormat.full(fix.timeMillis)
                setLocationAction(R.string.refresh, R.drawable.ic_refresh) { viewModel.refreshLocation() }
            }
            LocationUi.PermissionDenied -> {
                tvLocationTitle.setText(R.string.location_permission_title)
                tvLocationMessage.setText(R.string.location_permission_needed)
                setLocationAction(if (locationBlocked) R.string.open_settings else R.string.allow_location, R.drawable.ic_lock) {
                    if (locationBlocked) openAppSettings() else locationPermission.request(AppPermissions.LOCATION)
                }
            }
            LocationUi.Disabled -> {
                tvLocationTitle.setText(R.string.location_off_title)
                tvLocationMessage.setText(R.string.location_disabled)
                setLocationAction(R.string.turn_on, R.drawable.ic_gps_fixed) { openLocationSettings() }
            }
            is LocationUi.Unavailable -> {
                tvLocationTitle.setText(R.string.location_unavailable_title)
                tvLocationMessage.text = loc.reason
                setLocationAction(R.string.retry, R.drawable.ic_refresh) { viewModel.refreshLocation() }
            }
        }
    }

    private fun setLocationAction(text: Int, icon: Int, action: () -> Unit) = with(binding.btnLocationAction) {
        setText(text)
        setIconResource(icon)
        setOnClickListener { Feedback.tap(it); action() }
    }

    private fun ItemSosStatusRowBinding.bind(icon: Int, label: String, detail: String, tone: Tone, stateLabel: String) {
        this.icon.setImageResource(icon)
        this.label.text = label
        this.detail.text = detail
        progress.visibility = View.GONE
        state.bindStatusPill(tone, stateLabel)
        row.contentDescription = "$label: $stateLabel. $detail"
    }

    private fun ItemSosStatusRowBinding.bindDelivery(icon: Int, label: String, delivery: Delivery) {
        val (tone, stateLabel, detail) = when (delivery) {
            Delivery.Idle -> Triple(Tone.NEUTRAL, getString(R.string.waiting), "")
            Delivery.InProgress -> Triple(Tone.INFO, getString(R.string.sending), getString(R.string.sending_detail))
            is Delivery.Done -> Triple(Tone.SUCCESS, getString(R.string.delivered), delivery.detail)
            is Delivery.Failed -> Triple(Tone.DANGER, getString(R.string.failed), delivery.reason)
            is Delivery.Skipped -> Triple(Tone.WARNING, getString(R.string.action_needed), delivery.reason)
        }
        bind(icon, label, detail, tone, stateLabel)
        progress.visibility = if (delivery == Delivery.InProgress) View.VISIBLE else View.GONE
    }

    private fun promptContactSetup() {
        Snackbar.make(binding.root, R.string.sos_contact_missing, Snackbar.LENGTH_LONG)
            .setAction(R.string.set_up) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_TAB, R.id.nav_settings)
                )
            }
            .show()
    }

    private fun dial(number: String) {
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")), R.string.no_dialer)
    }

    private fun launch(intent: Intent, errorRes: Int) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, errorRes, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Location may have been enabled / permitted in system settings.
        val loc = viewModel.state.value.location
        if ((loc == LocationUi.Disabled || loc == LocationUi.PermissionDenied) && container.location.hasPermission() && container.location.isLocationEnabled()) {
            viewModel.refreshLocation()
        }
    }
}
