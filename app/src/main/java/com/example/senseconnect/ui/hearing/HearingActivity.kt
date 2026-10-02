package com.example.senseconnect.ui.hearing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.senseconnect.R
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.AppPermissions
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.TimeFormat
import com.example.senseconnect.core.ui.appViewModelFactory
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.core.ui.bindStatusPill
import com.example.senseconnect.core.ui.copyToClipboard
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.shareText
import com.example.senseconnect.databinding.ActivityHearingBinding
import com.example.senseconnect.databinding.ItemTranscriptSegmentBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Hearing Assistance: live captions of nearby speech plus a typed reply spoken aloud. */
class HearingActivity : BaseActivity() {

    private lateinit var binding: ActivityHearingBinding
    private val viewModel: HearingViewModel by viewModels { appViewModelFactory { HearingViewModel(it) } }
    private val adapter = TranscriptAdapter()
    private var permissionBlocked = false
    private var chronometerRunning = false
    private var renderedSegments = -1

    private val permissionRequester = PermissionRequester(this, { this }) { granted, blocked ->
        permissionBlocked = blocked
        if (granted) viewModel.start() else viewModel.onPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHearingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.root.applySystemBarPadding(top = true)
        binding.bottomPanel.applySystemBarPadding(bottom = true)

        with(binding.header.root) {
            setTitle(R.string.hearing_title)
            setSubtitle(R.string.hearing_workflow)
            setNavigationOnClickListener { finish() }
        }

        binding.transcriptList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.transcriptList.adapter = adapter

        binding.btnListen.setOnClickListener {
            Feedback.tap(it)
            if (viewModel.state.value.isActive) {
                viewModel.stop()
                container.speech.announce(getString(R.string.hearing_announce_stopped))
            } else {
                startListening()
            }
        }
        binding.btnCopy.setOnClickListener {
            val text = viewModel.state.value.fullTranscript
            if (text.isBlank()) return@setOnClickListener snackbar(getString(R.string.hearing_nothing_to_copy))
            copyToClipboard(getString(R.string.hearing_transcript), text)
            Feedback.confirm(it)
            snackbar(getString(R.string.copied))
        }
        binding.btnShare.setOnClickListener {
            val text = viewModel.state.value.fullTranscript
            if (text.isBlank()) return@setOnClickListener snackbar(getString(R.string.hearing_nothing_to_copy))
            shareText(text, getString(R.string.share_transcript))
        }
        binding.btnClear.setOnClickListener {
            Feedback.tap(it)
            viewModel.clear()
        }
        binding.btnSpeakReply.setOnClickListener { speakReply() }
        binding.inputReply.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                speakReply(); true
            } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_START, false)) startListening()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            permissionRequester.request(AppPermissions.MICROPHONE)
        }
    }

    /** A deaf or hard-of-hearing user can answer by typing; SenseConnect speaks the reply. */
    private fun speakReply() {
        val text = binding.inputReply.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            binding.replyLayout.error = getString(R.string.hearing_reply_empty)
            return
        }
        binding.replyLayout.error = null
        val wasListening = viewModel.state.value.isActive
        if (wasListening) viewModel.stop() // avoid transcribing our own voice
        if (container.speech.speak(text)) {
            Feedback.confirm(binding.btnSpeakReply)
            container.activityLog.log(ActivityType.COMMUNICATION, "Typed reply spoken", "From Hearing Assistance")
            binding.inputReply.text = null
        } else {
            snackbar(getString(R.string.tts_not_ready))
        }
    }

    private fun render(state: HearingUiState) = with(binding) {
        // Listening state — always text + colour + icon
        val (tone, label) = when (state.phase) {
            ListenPhase.IDLE -> if (state.error != null) Tone.WARNING to getString(R.string.listen_state_stopped)
                else Tone.NEUTRAL to getString(R.string.listen_state_ready)
            ListenPhase.STARTING -> Tone.INFO to getString(R.string.listen_state_starting)
            ListenPhase.LISTENING -> Tone.SUCCESS to getString(if (state.offlineMode) R.string.listen_state_listening_offline else R.string.listen_state_listening)
            ListenPhase.HEARING_SPEECH -> Tone.SUCCESS to getString(R.string.listen_state_hearing)
            ListenPhase.PROCESSING -> Tone.INFO to getString(R.string.listen_state_processing)
        }
        tvListenState.bindStatusPill(tone, label)
        levelView.active = state.phase == ListenPhase.LISTENING || state.phase == ListenPhase.HEARING_SPEECH
        levelView.level = state.level

        if (state.isActive && !chronometerRunning) {
            state.sessionStartedAt?.let { chronometer.base = it }
            chronometer.start()
            chronometer.visibility = View.VISIBLE
            chronometerRunning = true
        } else if (!state.isActive && chronometerRunning) {
            chronometer.stop()
            chronometer.visibility = View.INVISIBLE
            chronometerRunning = false
        }

        // Live caption
        tvCaption.text = when {
            state.partial.isNotBlank() -> state.partial
            state.isActive -> getString(R.string.hearing_caption_waiting)
            else -> getString(R.string.hearing_caption_idle)
        }
        tvCaption.alpha = if (state.partial.isNotBlank()) 1f else 0.6f
        captionCard.strokeColor = getColor(if (state.isActive) R.color.sc_secondary else R.color.sc_outline_variant)
        captionCard.strokeWidth = resources.displayMetrics.density.times(if (state.isActive) 2 else 1).toInt()

        // Primary control
        btnListen.setText(if (state.isActive) R.string.stop_listening else R.string.start_listening)
        btnListen.setIconResource(if (state.isActive) R.drawable.ic_stop else R.drawable.ic_mic)

        // Errors
        val error = state.error
        errorCard.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) {
            tvError.text = error.message
            btnErrorAction.visibility = if (error.retryable) View.VISIBLE else View.GONE
            when {
                error.needsPermission && permissionBlocked -> {
                    btnErrorAction.setText(R.string.open_settings)
                    btnErrorAction.setOnClickListener { openAppSettings() }
                }
                error.needsPermission -> {
                    btnErrorAction.setText(R.string.allow)
                    btnErrorAction.setOnClickListener { permissionRequester.request(AppPermissions.MICROPHONE) }
                }
                else -> {
                    btnErrorAction.setText(R.string.retry)
                    btnErrorAction.setOnClickListener { startListening() }
                }
            }
        }

        // Transcript
        tvTranscriptEmpty.visibility = if (state.segments.isEmpty()) View.VISIBLE else View.GONE
        val hasTranscript = state.segments.isNotEmpty()
        btnCopy.isEnabled = hasTranscript
        btnShare.isEnabled = hasTranscript
        btnClear.isEnabled = hasTranscript
        if (state.segments.size != renderedSegments) {
            renderedSegments = state.segments.size
            adapter.submitList(state.segments) {
                if (state.segments.isNotEmpty()) transcriptList.scrollToPosition(state.segments.size - 1)
            }
        }
    }

    override fun onStop() {
        // Never keep the microphone open in the background.
        if (viewModel.state.value.isActive) viewModel.stop()
        super.onStop()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).setAnchorView(binding.bottomPanel).show()
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}

private class TranscriptAdapter : ListAdapter<TranscriptSegment, TranscriptAdapter.Holder>(Diff) {
    class Holder(val binding: ItemTranscriptSegmentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemTranscriptSegmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val segment = getItem(position)
        holder.binding.time.text = TimeFormat.clock(segment.timestamp)
        holder.binding.text.text = segment.text
    }

    private object Diff : DiffUtil.ItemCallback<TranscriptSegment>() {
        override fun areItemsTheSame(a: TranscriptSegment, b: TranscriptSegment) = a.id == b.id
        override fun areContentsTheSame(a: TranscriptSegment, b: TranscriptSegment) = a == b
    }
}
