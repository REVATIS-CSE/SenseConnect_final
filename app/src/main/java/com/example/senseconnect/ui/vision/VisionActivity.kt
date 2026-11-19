package com.example.senseconnect.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.senseconnect.R
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.AppPermissions
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.appViewModelFactory
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.core.ui.bindStatusPill
import com.example.senseconnect.core.ui.colors
import com.example.senseconnect.core.ui.copyToClipboard
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.shareText
import com.example.senseconnect.core.ui.tintTile
import com.example.senseconnect.databinding.ActivityVisionBinding
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Vision Assistance screen: live camera with scan guide → capture → OCR → read aloud / copy / share. */
class VisionActivity : BaseActivity() {

    private lateinit var binding: ActivityVisionBinding
    private val viewModel: VisionViewModel by viewModels { appViewModelFactory { VisionViewModel(it) } }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private lateinit var analysisExecutor: ExecutorService
    private val liveBusy = AtomicBoolean(false)
    private var lastLiveAnalysis = 0L
    private var cameraReady = false

    private val permissionRequester = PermissionRequester(this, { this }) { granted, blocked ->
        if (granted) startCamera() else showCameraPermissionState(blocked)
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(this, uri) }
                .onSuccess { viewModel.recognize(it, ScanSource.GALLERY) }
                .onFailure { viewModel.failCapture(getString(R.string.vision_image_open_failed)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.root.applySystemBarPadding(top = true)
        binding.panel.applySystemBarPadding(bottom = true)
        binding.cameraContainer.clipToOutline = true // rounded preview corners
        analysisExecutor = Executors.newSingleThreadExecutor()

        setupToolbar()
        setupActions()
        observe()

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            showCameraState(R.drawable.ic_camera_alt, R.string.camera_unavailable_title, R.string.camera_unavailable_body, null, null)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionRequester.request(AppPermissions.CAMERA)
        }
    }

    private fun setupToolbar() = with(binding.header.root) {
        setTitle(R.string.vision_title)
        setSubtitle(R.string.vision_workflow)
        setNavigationOnClickListener { finish() }
        inflateMenu(R.menu.menu_vision)
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_torch -> toggleTorch()
                R.id.action_gallery -> pickFromGallery()
            }
            true
        }
    }

    private fun setupActions() = with(binding) {
        TooltipCompat.setTooltipText(btnCopy, getString(R.string.copy))
        TooltipCompat.setTooltipText(btnShare, getString(R.string.share))
        TooltipCompat.setTooltipText(btnGallery, getString(R.string.scan_from_gallery))
        TooltipCompat.setTooltipText(btnClear, getString(R.string.clear))

        btnScan.setOnClickListener {
            Feedback.tap(it)
            capture()
        }
        btnGallery.setOnClickListener { pickFromGallery() }
        btnClear.setOnClickListener {
            Feedback.tap(it)
            viewModel.clear()
        }
        btnReadAloud.setOnClickListener {
            val text = (viewModel.state.value as? ScanState.Result)?.text ?: return@setOnClickListener
            Feedback.tap(it)
            if (container.speech.speaking.value) container.speech.stop()
            else if (!container.speech.speak(text)) snackbar(getString(R.string.tts_not_ready))
        }
        btnCopy.setOnClickListener {
            val text = (viewModel.state.value as? ScanState.Result)?.text ?: return@setOnClickListener
            copyToClipboard(getString(R.string.vision_result_title), text)
            Feedback.confirm(it)
            snackbar(getString(R.string.copied))
        }
        btnShare.setOnClickListener {
            val text = (viewModel.state.value as? ScanState.Result)?.text ?: return@setOnClickListener
            shareText(text, getString(R.string.share))
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderScan) }
                launch {
                    combine(viewModel.liveWords, viewModel.state) { words, state -> words to state }
                        .collect { (words, state) -> renderGuide(words, state) }
                }
                launch {
                    container.speech.speaking.collect { speaking ->
                        binding.btnReadAloud.setText(if (speaking) R.string.stop_reading else R.string.read_aloud)
                        binding.btnReadAloud.setIconResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_volume_up)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera() {
        binding.cameraState.root.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            ).build()
                    )
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }

                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                } catch (e: Exception) {
                    // Some devices cannot run three use cases at once; live guidance is optional.
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
                imageCapture = capture
                cameraReady = true
                binding.header.root.menu.findItem(R.id.action_torch)?.isVisible = camera?.cameraInfo?.hasFlashUnit() == true
                renderGuide(viewModel.liveWords.value, viewModel.state.value)
            } catch (e: Exception) {
                cameraReady = false
                showCameraState(R.drawable.ic_error_outline, R.string.camera_error_title, R.string.camera_error_body, R.string.retry) { startCamera() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (viewModel.isProcessing || now - lastLiveAnalysis < LIVE_INTERVAL_MS || !liveBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastLiveAnalysis = now
        val image = try {
            InputImage.fromBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            liveBusy.set(false)
            proxy.close()
            return
        }
        proxy.close()
        viewModel.analyzeLive(image).addOnCompleteListener { liveBusy.set(false) }
    }

    private fun capture() {
        val capture = imageCapture
        if (!cameraReady || capture == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionRequester.request(AppPermissions.CAMERA)
            } else {
                snackbar(getString(R.string.camera_not_ready))
            }
            return
        }
        if (viewModel.isProcessing) return
        container.speech.stop()
        container.speech.announce(getString(R.string.vision_announce_scanning))
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val input = InputImage.fromBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
                    viewModel.recognize(input, ScanSource.CAMERA)
                } catch (e: Exception) {
                    viewModel.failCapture(getString(R.string.vision_capture_failed))
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                viewModel.failCapture(getString(R.string.vision_capture_failed))
            }
        })
        Feedback.confirm(binding.btnScan)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        binding.header.root.menu.findItem(R.id.action_torch)?.apply {
            setIcon(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            setTitle(if (torchOn) R.string.torch_off else R.string.torch_on)
        }
    }

    private fun pickFromGallery() {
        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ---------------------------------------------------------------- Rendering

    private fun renderGuide(words: Int, state: ScanState) = with(binding) {
        val processing = state == ScanState.Processing
        processingOverlay.visibility = if (processing) View.VISIBLE else View.GONE
        scanOverlay.scanning = processing
        btnScan.isEnabled = !processing
        btnScan.setText(if (processing) R.string.vision_processing_short else if (state is ScanState.Result) R.string.scan_again else R.string.scan_text)

        if (!cameraReady) {
            tvGuide.visibility = View.GONE
            scanOverlay.textDetected = false
            return@with
        }
        tvGuide.visibility = View.VISIBLE
        val detected = words > 0 && !processing
        scanOverlay.textDetected = detected
        val label = when {
            processing -> getString(R.string.vision_hold_still)
            detected -> getString(R.string.vision_text_detected)
            else -> getString(R.string.vision_align_text)
        }
        if (tvGuide.text != label) tvGuide.text = label
        tvGuide.compoundDrawablesRelative.firstOrNull()?.setTint(
            getColor(if (detected) R.color.sc_scan_frame_active else R.color.white)
        )
    }

    private fun renderScan(state: ScanState) = with(binding) {
        val result = state as? ScanState.Result
        resultContent.visibility = if (result != null) View.VISIBLE else View.GONE
        resultEmpty.visibility = if (result == null) View.VISIBLE else View.GONE
        btnClear.isEnabled = state is ScanState.Result || state is ScanState.NoText || state is ScanState.Error

        when (state) {
            ScanState.Idle -> {
                setEmpty(R.drawable.ic_text_fields, Tone.NEUTRAL, R.string.vision_empty_title, R.string.vision_empty_body)
                tvResultMeta.bindStatusPill(Tone.NEUTRAL, getString(R.string.vision_meta_ready))
            }
            ScanState.Processing -> {
                setEmpty(R.drawable.ic_document_scanner, Tone.INFO, R.string.vision_processing, R.string.vision_processing_body)
                tvResultMeta.bindStatusPill(Tone.INFO, getString(R.string.vision_meta_processing))
            }
            ScanState.NoText -> {
                setEmpty(R.drawable.ic_help_outline, Tone.WARNING, R.string.vision_no_text_title, R.string.vision_no_text_body)
                tvResultMeta.bindStatusPill(Tone.WARNING, getString(R.string.vision_meta_none))
                Feedback.reject(root)
            }
            is ScanState.Error -> {
                setEmpty(R.drawable.ic_error_outline, Tone.DANGER, R.string.vision_error_title, null)
                tvEmptyBody.text = state.message
                tvResultMeta.bindStatusPill(Tone.DANGER, getString(R.string.vision_meta_error))
            }
            is ScanState.Result -> {
                tvResult.text = state.text
                tvResult.scrollTo(0, 0)
                tvResultMeta.bindStatusPill(Tone.SUCCESS, resources.getQuantityString(R.plurals.word_count, state.words, state.words))
                Feedback.reveal(resultContent)
            }
        }
    }

    private fun setEmpty(icon: Int, tone: Tone, title: Int, body: Int?) = with(binding) {
        ivEmptyIcon.setImageResource(icon)
        val colors = tone.colors()
        ivEmptyIcon.tintTile(colors.container, colors.onContainer)
        tvEmptyTitle.setText(title)
        if (body != null) tvEmptyBody.setText(body)
    }

    private fun showCameraPermissionState(blocked: Boolean) {
        showCameraState(
            R.drawable.ic_camera_alt, R.string.camera_permission_title, R.string.camera_permission_body,
            if (blocked) R.string.open_settings else R.string.allow_camera,
        ) {
            if (blocked) openAppSettings() else permissionRequester.request(AppPermissions.CAMERA)
        }
    }

    private fun showCameraState(icon: Int, title: Int, body: Int, action: Int?, onAction: (() -> Unit)?) = with(binding.cameraState) {
        cameraReady = false
        root.visibility = View.VISIBLE
        stateIcon.setImageResource(icon)
        stateIcon.tintTile(R.color.sc_neutral_container, R.color.sc_on_neutral_container)
        stateTitle.setText(title)
        stateTitle.setTextColor(getColor(R.color.white))
        stateBody.setText(body)
        stateBody.setTextColor(getColor(R.color.sc_outline_variant))
        if (action != null && onAction != null) {
            stateAction.visibility = View.VISIBLE
            stateAction.setText(action)
            stateAction.setOnClickListener { onAction() }
        } else {
            stateAction.visibility = View.GONE
        }
        binding.tvGuide.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Returning from App Settings after granting permission.
        if (!cameraReady && binding.cameraState.root.visibility == View.VISIBLE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onStop() {
        container.speech.stop()
        super.onStop()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).setAnchorView(binding.panel).show()
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 900L
    }
}
