package com.example.senseconnect.ui.onboarding

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.senseconnect.R
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.core.ui.AppPermissions
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.PermissionRequester
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.core.ui.bindStatusPill
import com.example.senseconnect.core.ui.color
import com.example.senseconnect.core.ui.openAppSettings
import com.example.senseconnect.core.ui.setPipeline
import com.example.senseconnect.core.ui.tintTile
import com.example.senseconnect.databinding.ActivityOnboardingBinding
import com.example.senseconnect.databinding.ItemOnboardingPageBinding
import com.example.senseconnect.databinding.ItemOnboardingPermissionsBinding
import com.example.senseconnect.databinding.ItemPermissionCardBinding
import com.example.senseconnect.ui.main.MainActivity
import com.google.android.material.snackbar.Snackbar

/**
 * First-launch introduction: what SenseConnect is, its four modules, and why each permission is
 * needed — requested in context rather than all at once on start-up.
 */
class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var permissionsBinding: ItemOnboardingPermissionsBinding? = null

    private val replay by lazy { intent.getBooleanExtra(EXTRA_REPLAY, false) }

    private val pages = listOf(
        Page(R.drawable.ic_logo_mark, R.color.sc_primary, R.color.white, R.string.onb_welcome_overline, R.string.app_name, R.string.onb_welcome_body, emptyList(), brand = true),
        Page(R.drawable.ic_visibility, R.color.sc_primary_container, R.color.sc_on_primary_container, R.string.onb_vision_overline, R.string.vision_title, R.string.onb_vision_body, listOf("Camera", "On-device OCR", "Read aloud")),
        Page(R.drawable.ic_hearing, R.color.sc_secondary_container, R.color.sc_on_secondary_container, R.string.onb_hearing_overline, R.string.hearing_title, R.string.onb_hearing_body, listOf("Microphone", "Speech recognition", "Live captions")),
        Page(R.drawable.ic_record_voice_over, R.color.sc_tertiary_container, R.color.sc_on_tertiary_container, R.string.onb_comm_overline, R.string.communication_title, R.string.onb_comm_body, listOf("Phrase board", "Text-to-speech", "Spoken aloud")),
        Page(R.drawable.ic_sos, R.color.sc_emergency_container, R.color.sc_on_emergency_container, R.string.onb_sos_overline, R.string.emergency_card_title, R.string.onb_sos_body, listOf("GPS location", "Emergency message", "SMS · Call · Share")),
    )
    private val pageCount get() = pages.size + 1

    private val permissionRequester = PermissionRequester(this, { this }) { granted, blocked ->
        if (granted) Feedback.confirm(binding.root)
        if (!granted && blocked) {
            Snackbar.make(binding.root, R.string.permission_blocked, Snackbar.LENGTH_LONG)
                .setAction(R.string.open_settings) { openAppSettings() }
                .show()
        }
        renderPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(top = true, bottom = true)

        binding.pager.adapter = PagerAdapter()
        buildIndicator()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderChrome(position)
        })

        binding.btnSkip.setOnClickListener { binding.pager.currentItem = pageCount - 1 }
        binding.btnBack.setOnClickListener { binding.pager.currentItem = binding.pager.currentItem - 1 }
        binding.btnNext.setOnClickListener {
            Feedback.tap(it)
            if (binding.pager.currentItem < pageCount - 1) binding.pager.currentItem += 1 else finishOnboarding()
        }
        renderChrome(0)
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    private fun renderChrome(position: Int) {
        val last = position == pageCount - 1
        binding.tvStep.text = getString(R.string.onb_step, position + 1, pageCount)
        binding.btnBack.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        binding.btnSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        binding.btnNext.setText(if (last) R.string.get_started else R.string.next)
        for (i in 0 until binding.indicator.childCount) {
            val dot = binding.indicator.getChildAt(i) as ImageView
            dot.imageTintList = ColorStateList.valueOf(color(if (i == position) R.color.sc_primary else R.color.sc_outline_variant))
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = resources.getDimensionPixelSize(if (i == position) R.dimen.indicator_active else R.dimen.indicator_dot)
            }
        }
    }

    private fun buildIndicator() {
        val size = resources.getDimensionPixelSize(R.dimen.indicator_dot)
        repeat(pageCount) {
            binding.indicator.addView(ImageView(this).apply {
                setImageResource(R.drawable.bg_pill)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = size / 2; marginEnd = size / 2 }
            })
        }
    }

    private fun finishOnboarding() {
        container.settings.update { it.copy(onboardingComplete = true) }
        if (!replay) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun renderPermissions() {
        val b = permissionsBinding ?: return
        b.permCamera.render(R.drawable.ic_camera_alt, R.color.sc_primary_container, R.color.sc_on_primary_container,
            R.string.perm_camera, R.string.perm_camera_why, AppPermissions.CAMERA)
        b.permMic.render(R.drawable.ic_mic, R.color.sc_secondary_container, R.color.sc_on_secondary_container,
            R.string.perm_microphone, R.string.perm_microphone_why, AppPermissions.MICROPHONE)
        b.permLocation.render(R.drawable.ic_location_on, R.color.sc_emergency_container, R.color.sc_on_emergency_container,
            R.string.perm_location, R.string.perm_location_why, AppPermissions.LOCATION)
    }

    private fun ItemPermissionCardBinding.render(
        @DrawableRes iconRes: Int, @ColorRes container: Int, @ColorRes onContainer: Int,
        @StringRes titleRes: Int, @StringRes whyRes: Int, permissions: Array<String>,
    ) {
        val granted = permissions.any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        icon.setImageResource(iconRes)
        icon.tintTile(container, onContainer)
        title.setText(titleRes)
        why.setText(whyRes)
        state.bindStatusPill(if (granted) Tone.SUCCESS else Tone.NEUTRAL, getString(if (granted) R.string.allowed else R.string.not_allowed_yet))
        btnAllow.visibility = if (granted) View.GONE else View.VISIBLE
        btnAllow.contentDescription = getString(R.string.cd_allow_permission, getString(titleRes))
        btnAllow.setOnClickListener { permissionRequester.request(permissions) }
    }

    private data class Page(
        @DrawableRes val icon: Int,
        @ColorRes val container: Int,
        @ColorRes val onContainer: Int,
        @StringRes val overline: Int,
        @StringRes val title: Int,
        @StringRes val body: Int,
        val pipeline: List<String>,
        val brand: Boolean = false,
    )

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pageCount
        override fun getItemViewType(position: Int) = if (position < pages.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                PageHolder(ItemOnboardingPageBinding.inflate(inflater, parent, false))
            } else {
                val b = ItemOnboardingPermissionsBinding.inflate(inflater, parent, false)
                permissionsBinding = b
                renderPermissions()
                object : RecyclerView.ViewHolder(b.root) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is PageHolder) return
            val page = pages[position]
            val b = holder.binding
            b.icon.setImageResource(page.icon)
            if (page.brand) {
                b.icon.background = ContextCompat.getDrawable(this@OnboardingActivity, R.drawable.bg_brand_gradient)
                b.icon.backgroundTintList = null
                b.icon.imageTintList = ColorStateList.valueOf(color(R.color.white))
            } else {
                b.icon.background = ContextCompat.getDrawable(this@OnboardingActivity, R.drawable.bg_circle)
                b.icon.tintTile(page.container, page.onContainer)
            }
            b.overline.setText(page.overline)
            b.overline.setTextColor(color(if (page.brand) R.color.sc_primary else page.onContainer))
            b.title.setText(page.title)
            b.body.setText(page.body)
            b.pipeline.visibility = if (page.pipeline.isEmpty()) View.GONE else View.VISIBLE
            b.pipeline.setPipeline(page.pipeline)
            if (page.brand) {
                b.pipeline.visibility = View.VISIBLE
                b.pipeline.setPipeline(listOf(getString(R.string.motto)))
            }
        }
    }

    private class PageHolder(val binding: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val EXTRA_REPLAY = "replay"
    }
}
