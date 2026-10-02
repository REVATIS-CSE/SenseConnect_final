package com.example.senseconnect.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.senseconnect.BuildConfig
import com.example.senseconnect.R
import com.example.senseconnect.SenseConnectApp
import com.example.senseconnect.core.network.BackendStatus
import com.example.senseconnect.core.settings.AppSettings
import com.example.senseconnect.core.settings.TextSize
import com.example.senseconnect.core.settings.ThemeMode
import com.example.senseconnect.core.speech.SpeechOutput
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.AppPermissions
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.openTtsSettings
import com.example.senseconnect.core.ui.setChecked
import com.example.senseconnect.core.ui.setup
import com.example.senseconnect.core.ui.showPill
import com.example.senseconnect.databinding.DialogTextFieldsBinding
import com.example.senseconnect.databinding.FragmentSettingsBinding
import com.example.senseconnect.databinding.ItemSettingActionBinding
import com.example.senseconnect.ui.onboarding.OnboardingActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Accessibility, speech, emergency, permission and cloud preferences. Every control is live. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as SenseConnectApp).container
    private val settings get() = container.settings

    /** Which permission row triggered the last request (to react to SMS specifically). */
    private var pendingSmsEnable = false

    private val permissionRequester = PermissionRequester(this, { requireActivity() }) { granted, blocked ->
        if (pendingSmsEnable) {
            pendingSmsEnable = false
            settings.update { it.copy(directSms = granted) }
            if (granted) snackbar(R.string.direct_sms_enabled)
        }
        if (!granted && blocked) {
            Snackbar.make(binding.root, R.string.permission_blocked, Snackbar.LENGTH_LONG)
                .setAction(R.string.open_settings) { requireContext().openAppSettings() }
                .show()
        }
        renderPermissions()
    }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                settings.update { it.copy(emergencyContactName = name, emergencyContactPhone = number) }
                Feedback.confirm(binding.root)
                snackbar(getString(R.string.contact_saved, name.ifBlank { number }))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupAccessibility()
        setupSpeech()
        setupEmergency()
        setupPermissions()
        setupAbout()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { settings.settings.collect(::render) }
                launch { container.speech.state.collect(::renderVoice) }
                launch {
                    combine(container.backend.status, container.network.online) { s, online -> s to online }
                        .collect { (s, online) -> renderBackend(s, online) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    // ------------------------------------------------------------------ Accessibility

    private fun setupAccessibility() = with(binding) {
        toggleTextSize.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val size = when (id) {
                R.id.btnTextLarge -> TextSize.LARGE
                R.id.btnTextXLarge -> TextSize.EXTRA_LARGE
                else -> TextSize.DEFAULT
            }
            if (size != settings.current.textSize) {
                settings.update { it.copy(textSize = size) }
                requireActivity().recreate()
            }
        }
        toggleTheme.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.btnThemeLight -> ThemeMode.LIGHT
                R.id.btnThemeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            if (mode != settings.current.themeMode) {
                settings.update { it.copy(themeMode = mode) }
                AppCompatDelegate.setDefaultNightMode(mode.nightMode)
            }
        }
        rowHighContrast.setup(R.drawable.ic_contrast, getString(R.string.setting_high_contrast), getString(R.string.setting_high_contrast_sub)) { on ->
            settings.update { it.copy(highContrast = on) }
            requireActivity().recreate()
        }
        rowReduceMotion.setup(R.drawable.ic_motion_photos_off, getString(R.string.setting_reduce_motion), getString(R.string.setting_reduce_motion_sub)) { on ->
            settings.update { it.copy(reduceMotion = on) }
        }
        rowVoiceFeedback.setup(R.drawable.ic_record_voice_over, getString(R.string.setting_voice_feedback), getString(R.string.setting_voice_feedback_sub)) { on ->
            settings.update { it.copy(voiceFeedback = on) }
            if (on) container.speech.announce(getString(R.string.voice_feedback_on))
        }
        rowHaptics.setup(R.drawable.ic_notifications_active, getString(R.string.setting_haptics), getString(R.string.setting_haptics_sub)) { on ->
            settings.update { it.copy(hapticFeedback = on) }
        }
    }

    // ------------------------------------------------------------------ Speech

    private fun setupSpeech() = with(binding) {
        sliderRate.setLabelFormatter { formatMultiplier(it) }
        sliderPitch.setLabelFormatter { formatMultiplier(it) }
        sliderRate.addOnChangeListener { _, value, fromUser ->
            tvRateValue.text = formatMultiplier(value)
            if (fromUser) settings.update { it.copy(speechRate = value) }
        }
        sliderPitch.addOnChangeListener { _, value, fromUser ->
            tvPitchValue.text = formatMultiplier(value)
            if (fromUser) settings.update { it.copy(speechPitch = value) }
        }
        btnTestVoice.setOnClickListener {
            Feedback.tap(it)
            if (!container.speech.speak(getString(R.string.test_voice_sentence))) {
                snackbar(R.string.tts_not_ready)
            }
        }
        btnVoiceSettings.setOnClickListener { requireContext().openTtsSettings() }
        rowSpeakOnTap.setup(R.drawable.ic_textsms, getString(R.string.setting_speak_on_tap), getString(R.string.setting_speak_on_tap_sub)) { on ->
            settings.update { it.copy(speakOnTap = on) }
        }
        rowAutoRead.setup(R.drawable.ic_document_scanner, getString(R.string.setting_auto_read), getString(R.string.setting_auto_read_sub)) { on ->
            settings.update { it.copy(autoReadScans = on) }
        }
    }

    // ------------------------------------------------------------------ Emergency

    private fun setupEmergency() = with(binding) {
        rowContact.setup(R.drawable.ic_person, getString(R.string.setting_contact), "") { editContact() }
        rowPickContact.setup(R.drawable.ic_phone_in_talk, getString(R.string.setting_pick_contact), getString(R.string.setting_pick_contact_sub)) {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            runCatching { contactPicker.launch(intent) }.onFailure { snackbar(R.string.no_contacts_app) }
        }
        rowMessage.setup(R.drawable.ic_sms, getString(R.string.setting_message), "") { editMessage() }
        rowDirectSms.setup(R.drawable.ic_textsms, getString(R.string.setting_direct_sms), getString(R.string.setting_direct_sms_sub)) { on ->
            if (!on) {
                settings.update { it.copy(directSms = false) }
            } else if (!requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                rowDirectSms.setChecked(false)
                snackbar(R.string.sms_not_supported)
            } else if (hasPermission(Manifest.permission.SEND_SMS)) {
                settings.update { it.copy(directSms = true) }
            } else {
                rowDirectSms.setChecked(false)
                pendingSmsEnable = true
                permissionRequester.request(AppPermissions.SMS)
            }
        }
        rowCloudLogging.setup(R.drawable.ic_cloud_queue, getString(R.string.setting_cloud_logging), getString(R.string.setting_cloud_logging_sub)) { on ->
            settings.update { it.copy(cloudLogging = on) }
        }
    }

    private fun editContact() {
        val dialog = DialogTextFieldsBinding.inflate(layoutInflater)
        val current = settings.current
        dialog.layoutFirst.hint = getString(R.string.contact_name_hint)
        dialog.inputFirst.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        dialog.inputFirst.setText(current.emergencyContactName)
        dialog.layoutSecond.hint = getString(R.string.contact_phone_hint)
        dialog.inputSecond.inputType = InputType.TYPE_CLASS_PHONE
        dialog.inputSecond.setText(current.emergencyContactPhone)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.setting_contact)
            .setView(dialog.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val phone = dialog.inputSecond.text?.toString().orEmpty().filter { it.isDigit() || it == '+' }
                settings.update {
                    it.copy(
                        emergencyContactName = dialog.inputFirst.text?.toString()?.trim().orEmpty(),
                        emergencyContactPhone = phone,
                    )
                }
                snackbar(if (phone.isBlank()) R.string.contact_removed else R.string.contact_updated)
            }
            .show()
    }

    private fun editMessage() {
        val dialog = DialogTextFieldsBinding.inflate(layoutInflater)
        dialog.layoutFirst.hint = getString(R.string.setting_message)
        dialog.inputFirst.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        dialog.inputFirst.minLines = 3
        dialog.inputFirst.setText(settings.current.emergencyMessage)
        dialog.layoutSecond.visibility = View.GONE
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.setting_message)
            .setMessage(R.string.setting_message_help)
            .setView(dialog.root)
            .setNeutralButton(R.string.reset) { _, _ ->
                settings.update { it.copy(emergencyMessage = AppSettings.DEFAULT_EMERGENCY_MESSAGE) }
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = dialog.inputFirst.text?.toString()?.trim().orEmpty()
                settings.update { it.copy(emergencyMessage = text.ifBlank { AppSettings.DEFAULT_EMERGENCY_MESSAGE }) }
            }
            .show()
    }

    // ------------------------------------------------------------------ Permissions

    private fun setupPermissions() = with(binding) {
        rowPermCamera.setup(R.drawable.ic_camera_alt, getString(R.string.perm_camera), getString(R.string.perm_camera_why)) {
            requestOrManage(AppPermissions.CAMERA)
        }
        rowPermMic.setup(R.drawable.ic_mic, getString(R.string.perm_microphone), getString(R.string.perm_microphone_why)) {
            requestOrManage(AppPermissions.MICROPHONE)
        }
        rowPermLocation.setup(R.drawable.ic_location_on, getString(R.string.perm_location), getString(R.string.perm_location_why)) {
            requestOrManage(AppPermissions.LOCATION)
        }
        rowPermSms.setup(R.drawable.ic_sms, getString(R.string.perm_sms), getString(R.string.perm_sms_why)) {
            requestOrManage(AppPermissions.SMS)
        }
    }

    private fun requestOrManage(permissions: Array<String>) {
        if (permissions.any { hasPermission(it) }) requireContext().openAppSettings()
        else permissionRequester.request(permissions)
    }

    private fun renderPermissions() {
        if (_binding == null) return
        fun ItemSettingActionBinding.render(permissions: Array<String>) {
            val granted = permissions.any { hasPermission(it) }
            showPill(if (granted) Tone.SUCCESS else Tone.WARNING, getString(if (granted) R.string.allowed else R.string.not_allowed))
        }
        binding.rowPermCamera.render(AppPermissions.CAMERA)
        binding.rowPermMic.render(AppPermissions.MICROPHONE)
        binding.rowPermLocation.render(AppPermissions.LOCATION)
        binding.rowPermSms.render(AppPermissions.SMS)
        // Direct SMS can't stay on if the permission was revoked in system settings.
        if (settings.current.directSms && !hasPermission(Manifest.permission.SEND_SMS)) {
            settings.update { it.copy(directSms = false) }
        }
    }

    // ------------------------------------------------------------------ About / Cloud

    private fun setupAbout() = with(binding) {
        tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        rowBackend.setup(R.drawable.ic_cloud_done, getString(R.string.cloud_title), container.backend.baseUrl) {
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = container.backend.refreshHealth()) {
                    is BackendStatus.Online -> snackbar(getString(R.string.cloud_test_ok, result.latencyMs, result.version))
                    is BackendStatus.Offline -> snackbar(getString(R.string.cloud_test_failed, result.reason))
                    else -> Unit
                }
                container.backend.refreshConfig()
            }
        }
        rowReplayIntro.setup(R.drawable.ic_play_arrow, getString(R.string.replay_intro), getString(R.string.replay_intro_sub)) {
            startActivity(Intent(requireContext(), OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REPLAY, true))
        }
    }

    // ------------------------------------------------------------------ Rendering

    private fun render(s: AppSettings) = with(binding) {
        toggleTextSize.check(
            when (s.textSize) {
                TextSize.DEFAULT -> R.id.btnTextDefault
                TextSize.LARGE -> R.id.btnTextLarge
                TextSize.EXTRA_LARGE -> R.id.btnTextXLarge
            }
        )
        toggleTheme.check(
            when (s.themeMode) {
                ThemeMode.SYSTEM -> R.id.btnThemeSystem
                ThemeMode.LIGHT -> R.id.btnThemeLight
                ThemeMode.DARK -> R.id.btnThemeDark
            }
        )
        rowHighContrast.setChecked(s.highContrast)
        rowReduceMotion.setChecked(s.reduceMotion)
        rowVoiceFeedback.setChecked(s.voiceFeedback)
        rowHaptics.setChecked(s.hapticFeedback)
        rowSpeakOnTap.setChecked(s.speakOnTap)
        rowAutoRead.setChecked(s.autoReadScans)
        rowDirectSms.setChecked(s.directSms)
        rowCloudLogging.setChecked(s.cloudLogging)

        sliderRate.value = snap(s.speechRate)
        sliderPitch.value = snap(s.speechPitch)

        rowContact.summary.text = if (s.hasEmergencyContact) {
            listOf(s.emergencyContactName, s.emergencyContactPhone).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            getString(R.string.contact_not_configured)
        }
        rowContact.showPill(
            if (s.hasEmergencyContact) Tone.SUCCESS else Tone.WARNING,
            getString(if (s.hasEmergencyContact) R.string.configured else R.string.not_configured),
        )
        rowMessage.summary.text = s.emergencyMessage
        rowMessage.showPill(null, null)
    }

    private fun renderVoice(state: SpeechOutput.State) {
        binding.tvVoiceEngine.text = when (state) {
            is SpeechOutput.State.Ready -> getString(R.string.voice_engine_ready, state.locale.displayName)
            SpeechOutput.State.Initializing -> getString(R.string.voice_engine_starting)
            is SpeechOutput.State.Unavailable -> state.reason
        }
    }

    private fun renderBackend(status: BackendStatus, online: Boolean) {
        val (tone, label) = when {
            !online -> Tone.NEUTRAL to getString(R.string.cloud_offline)
            status is BackendStatus.Online -> Tone.SUCCESS to getString(R.string.cloud_online_ms, status.latencyMs)
            status is BackendStatus.Offline -> Tone.NEUTRAL to getString(R.string.cloud_unreachable)
            else -> Tone.INFO to getString(R.string.cloud_checking)
        }
        binding.rowBackend.summary.text = getString(R.string.cloud_summary, container.backend.baseUrl.removePrefix("https://").trimEnd('/'))
        binding.rowBackend.showPill(tone, label)
    }

    // ------------------------------------------------------------------ Helpers

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

    private fun snap(value: Float) = ((value * 10).roundToInt() / 10f).coerceIn(0.5f, 2.0f)

    private fun formatMultiplier(value: Float) = String.format(Locale.getDefault(), "%.1f×", value)

    private fun snackbar(res: Int) = snackbar(getString(res))

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
