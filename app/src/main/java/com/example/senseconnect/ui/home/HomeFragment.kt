package com.example.senseconnect.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.senseconnect.BuildConfig
import com.example.senseconnect.R
import com.example.senseconnect.core.location.LocationResult
import com.example.senseconnect.core.network.BackendStatus
import com.example.senseconnect.core.status.FixAction
import com.example.senseconnect.core.status.ServiceId
import com.example.senseconnect.core.status.ServiceStatus
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.TimeFormat
import com.example.senseconnect.core.ui.addHeroPill
import com.example.senseconnect.core.ui.appViewModelFactory
import com.example.senseconnect.core.ui.bind
import com.example.senseconnect.core.ui.bindStatusPill
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.openLocationSettings
import com.example.senseconnect.core.ui.openTtsSettings
import com.example.senseconnect.core.ui.permissions
import com.example.senseconnect.core.ui.setPipeline
import com.example.senseconnect.core.ui.shareText
import com.example.senseconnect.core.ui.tintTile
import com.example.senseconnect.databinding.FragmentHomeBinding
import com.example.senseconnect.databinding.ItemActivityRowBinding
import com.example.senseconnect.databinding.ItemQuickActionBinding
import com.example.senseconnect.databinding.ItemServiceCardBinding
import com.example.senseconnect.databinding.ItemServiceStatusBinding
import com.example.senseconnect.ui.communication.CommunicationActivity
import com.example.senseconnect.ui.emergency.EmergencyActivity
import com.example.senseconnect.ui.hearing.HearingActivity
import com.example.senseconnect.ui.main.MainActivity
import com.example.senseconnect.ui.vision.VisionActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The SenseConnect dashboard — the hub that ties every assistive module together. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val recentRowCache = mutableListOf<ItemActivityRowBinding>()
    private val statusRowCache = mutableListOf<ItemServiceStatusBinding>()
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels { appViewModelFactory { HomeViewModel(it) } }

    private val permissionRequester = PermissionRequester(this, { requireActivity() }) { granted, blocked ->
        viewModel.refresh()
        if (!granted && blocked) {
            Snackbar.make(binding.root, R.string.permission_blocked, Snackbar.LENGTH_LONG)
                .setAction(R.string.open_settings) { requireContext().openAppSettings() }
                .show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupStaticContent()
        setupNavigation()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.locating.collect(::renderLocating) }
                launch { viewModel.locationEvents.collect(::onLocationEvent) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvGreeting.text = TimeFormat.greeting()
        binding.tvDate.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        viewModel.refresh()
    }

    private fun setupStaticContent() = with(binding) {
        bindServiceCard(serviceVision, R.drawable.ic_visibility, R.color.sc_primary_container, R.color.sc_on_primary_container,
            R.string.vision_title, R.string.vision_summary, listOf("Camera", "OCR", "Speech"))
        bindServiceCard(serviceHearing, R.drawable.ic_hearing, R.color.sc_secondary_container, R.color.sc_on_secondary_container,
            R.string.hearing_title, R.string.hearing_summary, listOf("Mic", "Recognition", "Captions"))
        bindServiceCard(serviceCommunication, R.drawable.ic_record_voice_over, R.color.sc_tertiary_container, R.color.sc_on_tertiary_container,
            R.string.communication_title, R.string.communication_summary, listOf("Phrase", "TTS", "Voice"))

        bindQuickAction(qaScan, R.drawable.ic_document_scanner, R.color.sc_primary_container, R.color.sc_on_primary_container,
            R.string.qa_scan, R.string.qa_scan_sub)
        bindQuickAction(qaListen, R.drawable.ic_mic, R.color.sc_secondary_container, R.color.sc_on_secondary_container,
            R.string.qa_listen, R.string.qa_listen_sub)
        bindQuickAction(qaSpeak, R.drawable.ic_record_voice_over, R.color.sc_tertiary_container, R.color.sc_on_tertiary_container,
            R.string.qa_speak, R.string.qa_speak_sub)
        bindQuickAction(qaLocation, R.drawable.ic_my_location, R.color.sc_success_container, R.color.sc_on_success_container,
            R.string.qa_location, R.string.qa_location_sub)

        tvFooter.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
    }

    private fun setupNavigation() = with(binding) {
        serviceVision.card.setOnClickListener { open(VisionActivity::class.java) }
        serviceHearing.card.setOnClickListener { open(HearingActivity::class.java) }
        serviceCommunication.card.setOnClickListener { open(CommunicationActivity::class.java) }

        qaScan.card.setOnClickListener { open(VisionActivity::class.java) }
        qaListen.card.setOnClickListener {
            startActivity(Intent(requireContext(), HearingActivity::class.java).putExtra(HearingActivity.EXTRA_AUTO_START, true))
        }
        qaSpeak.card.setOnClickListener { open(CommunicationActivity::class.java) }
        qaLocation.card.setOnClickListener {
            Feedback.tap(it)
            if (viewModel.state.value.status(ServiceId.LOCATION)?.fix == FixAction.REQUEST_LOCATION) {
                permissionRequester.request(FixAction.REQUEST_LOCATION.permissions()!!)
            } else {
                viewModel.requestLocationForSharing()
            }
        }

        btnHeaderSos.setOnClickListener { openSos(it) }
        btnOpenSos.setOnClickListener { openSos(it) }
        btnViewAllActivity.setOnClickListener { (activity as? MainActivity)?.selectTab(R.id.nav_activity) }
        btnRefreshStatus.setOnClickListener {
            Feedback.tap(it)
            viewModel.refresh()
            viewModel.retryCloud()
        }
    }

    private fun render(state: HomeUiState) = with(binding) {
        // ---- Hero: system status
        val local = state.localStatuses
        if (state.statuses.isEmpty()) {
            tvHeroTitle.setText(R.string.hero_checking_title)
            tvHeroSubtitle.text = null
            return@with
        }
        if (state.allReady) {
            tvHeroTitle.setText(R.string.hero_ready_title)
            tvHeroSubtitle.text = getString(R.string.hero_ready_body, local.size)
        } else {
            tvHeroTitle.setText(R.string.hero_attention_title)
            tvHeroSubtitle.text = getString(R.string.hero_attention_body, local.size - state.readyCount)
        }
        heroCapabilities.removeAllViews()
        heroCapabilities.addHeroPill(getString(R.string.module_vision), state.status(ServiceId.CAMERA)?.tone == Tone.SUCCESS)
        heroCapabilities.addHeroPill(getString(R.string.module_hearing), state.status(ServiceId.MICROPHONE)?.tone == Tone.SUCCESS)
        heroCapabilities.addHeroPill(getString(R.string.module_communication), state.status(ServiceId.SPEECH)?.tone == Tone.SUCCESS)
        heroCapabilities.addHeroPill(getString(R.string.module_sos), state.status(ServiceId.LOCATION)?.tone == Tone.SUCCESS && state.settings.hasEmergencyContact)
        tvHeroCloud.text = when (state.backend) {
            is BackendStatus.Online -> getString(R.string.cloud_online)
            BackendStatus.Checking, BackendStatus.Unknown -> getString(R.string.cloud_checking)
            BackendStatus.Waking -> getString(R.string.cloud_waking)
            is BackendStatus.Offline -> getString(R.string.cloud_offline)
        }
        tvHeroCloud.contentDescription = getString(R.string.cd_cloud_status, tvHeroCloud.text)

        tvAnnouncement.visibility = if (state.announcement != null) View.VISIBLE else View.GONE
        tvAnnouncement.text = state.announcement

        // ---- Service cards reflect the real subsystem state
        state.status(ServiceId.CAMERA)?.let { bindCardStatus(serviceVision, it) }
        state.status(ServiceId.MICROPHONE)?.let { bindCardStatus(serviceHearing, it) }
        state.status(ServiceId.SPEECH)?.let { bindCardStatus(serviceCommunication, it) }

        // ---- Recent activity (rows are reused between renders, only re-bound)
        val recentRows = rows(recentRowCache, state.recent.size.coerceAtLeast(1)) {
            ItemActivityRowBinding.inflate(layoutInflater, recentContainer, true)
        }
        if (state.recent.isEmpty()) {
            val empty = recentRows.first()
            empty.icon.setImageResource(R.drawable.ic_history)
            empty.icon.tintTile(R.color.sc_neutral_container, R.color.sc_on_neutral_container)
            empty.title.setText(R.string.recent_empty_title)
            empty.detail.setText(R.string.recent_empty_body)
            empty.detail.visibility = View.VISIBLE
            empty.time.text = null
            empty.root.contentDescription = null
        } else {
            state.recent.forEachIndexed { i, event -> recentRows[i].bind(event) }
        }

        // ---- Service availability
        val statusRows = rows(statusRowCache, state.statuses.size) {
            if (statusRowCache.isNotEmpty()) layoutInflater.inflate(R.layout.view_divider, statusContainer, true)
            ItemServiceStatusBinding.inflate(layoutInflater, statusContainer, true)
        }
        state.statuses.forEachIndexed { i, status -> bindStatusRow(statusRows[i], status) }

        // ---- Emergency access
        val s = state.settings
        tvEmergencyContact.text = if (s.hasEmergencyContact) {
            getString(R.string.emergency_contact_set, s.emergencyContactName.ifBlank { s.emergencyContactPhone })
        } else {
            getString(R.string.emergency_contact_missing)
        }
    }

    /** Grows [cache] to [count] rows (inflating only new ones) and hides any surplus rows. */
    private fun <T : ViewBinding> rows(cache: MutableList<T>, count: Int, create: () -> T): List<T> {
        while (cache.size < count) cache += create()
        cache.forEachIndexed { i, row -> row.root.visibility = if (i < count) View.VISIBLE else View.GONE }
        return cache
    }

    private fun renderLocating(locating: Boolean) {
        binding.qaLocation.sublabel.setText(if (locating) R.string.qa_location_busy else R.string.qa_location_sub)
        binding.qaLocation.card.isEnabled = !locating
    }

    private fun onLocationEvent(event: LocationShareEvent) {
        when (event) {
            is LocationShareEvent.Ready -> {
                Feedback.confirm(binding.root)
                requireContext().shareText(
                    getString(R.string.share_location_message, event.fix.mapsUrl),
                    getString(R.string.share_location_title),
                )
            }
            is LocationShareEvent.Failed -> {
                val message = when (val r = event.result) {
                    LocationResult.PermissionDenied -> getString(R.string.location_permission_needed)
                    LocationResult.ServicesDisabled -> getString(R.string.location_disabled)
                    is LocationResult.Unavailable -> r.reason
                    is LocationResult.Success -> return
                }
                Feedback.reject(binding.root)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).apply {
                    if (event.result == LocationResult.ServicesDisabled) {
                        setAction(R.string.turn_on) { requireContext().openLocationSettings() }
                    }
                }.show()
            }
        }
    }

    private fun bindServiceCard(
        card: ItemServiceCardBinding, icon: Int, container: Int, onContainer: Int,
        title: Int, summary: Int, pipeline: List<String>,
    ) {
        card.icon.setImageResource(icon)
        card.icon.tintTile(container, onContainer)
        card.title.setText(title)
        card.description.setText(summary)
        card.pipeline.setPipeline(pipeline)
    }

    private fun bindCardStatus(card: ItemServiceCardBinding, status: ServiceStatus) {
        card.status.bindStatusPill(status.tone, status.stateLabel)
        card.card.contentDescription = "${card.title.text}. ${card.description.text}. " +
            "Status: ${status.stateLabel}. ${card.pipeline.contentDescription}. Double tap to open."
    }

    private fun bindQuickAction(qa: ItemQuickActionBinding, icon: Int, container: Int, onContainer: Int, label: Int, sub: Int) {
        qa.icon.setImageResource(icon)
        qa.icon.tintTile(container, onContainer)
        qa.label.setText(label)
        qa.sublabel.setText(sub)
    }

    private fun bindStatusRow(row: ItemServiceStatusBinding, status: ServiceStatus) {
        row.icon.setImageResource(status.iconRes)
        row.label.text = status.label
        row.detail.text = status.detail
        row.state.bindStatusPill(status.tone, status.stateLabel)
        row.root.contentDescription = "${status.label}: ${status.stateLabel}. ${status.detail}"
        val fix = status.fix
        row.root.isFocusable = true
        if (fix != null) {
            row.root.setOnClickListener {
                Feedback.tap(it)
                handleFix(fix)
            }
        } else {
            row.root.setOnClickListener(null)
        }
        row.root.isClickable = fix != null
    }

    private fun handleFix(fix: FixAction) {
        val permissions = fix.permissions()
        when {
            permissions != null -> permissionRequester.request(permissions)
            fix == FixAction.OPEN_LOCATION_SETTINGS -> requireContext().openLocationSettings()
            fix == FixAction.OPEN_TTS_SETTINGS -> requireContext().openTtsSettings()
            fix == FixAction.RETRY_CLOUD -> viewModel.retryCloud()
        }
    }

    private fun openSos(view: View) {
        Feedback.tap(view)
        open(EmergencyActivity::class.java)
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(requireContext(), target))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recentRowCache.clear()
        statusRowCache.clear()
        _binding = null
    }
}
