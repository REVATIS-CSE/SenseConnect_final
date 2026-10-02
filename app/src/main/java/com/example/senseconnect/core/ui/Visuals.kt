package com.example.senseconnect.core.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.senseconnect.R
import com.example.senseconnect.core.activitylog.ActivityEvent
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.databinding.ItemActivityRowBinding
import com.google.android.material.chip.ChipGroup

/** Consistent iconography + colour per assistive module, used by dashboard, history and onboarding. */
data class ModuleVisual(@DrawableRes val icon: Int, @ColorRes val container: Int, @ColorRes val onContainer: Int, val label: String)

fun ActivityType.visual(): ModuleVisual = when (this) {
    ActivityType.VISION -> ModuleVisual(R.drawable.ic_document_scanner, R.color.sc_primary_container, R.color.sc_on_primary_container, "Vision")
    ActivityType.HEARING -> ModuleVisual(R.drawable.ic_hearing, R.color.sc_secondary_container, R.color.sc_on_secondary_container, "Hearing")
    ActivityType.COMMUNICATION -> ModuleVisual(R.drawable.ic_record_voice_over, R.color.sc_tertiary_container, R.color.sc_on_tertiary_container, "Communication")
    ActivityType.SOS -> ModuleVisual(R.drawable.ic_sos, R.color.sc_emergency_container, R.color.sc_on_emergency_container, "Emergency")
    ActivityType.LOCATION -> ModuleVisual(R.drawable.ic_location_on, R.color.sc_success_container, R.color.sc_on_success_container, "Location")
}

fun ItemActivityRowBinding.bind(event: ActivityEvent) {
    val v = event.type.visual()
    icon.setImageResource(v.icon)
    icon.tintTile(v.container, v.onContainer)
    title.text = event.title
    detail.text = event.detail
    detail.visibility = if (event.detail.isBlank()) View.GONE else View.VISIBLE
    time.text = TimeFormat.relative(event.timestamp)
    root.contentDescription = "${v.label}: ${event.title}. ${event.detail}. ${TimeFormat.relative(event.timestamp)}"
}

/** Fills a ChipGroup with a workflow such as Camera → OCR → Speech. */
fun ChipGroup.setPipeline(steps: List<String>) {
    removeAllViews()
    steps.forEachIndexed { index, step ->
        if (index > 0) {
            addView(TextView(context, null, 0, R.style.SenseConnect_PipelineStep).apply {
                text = "→"
                background = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        addView(TextView(context, null, 0, R.style.SenseConnect_PipelineStep).apply { text = step })
    }
    contentDescription = "Workflow: " + steps.joinToString(", then ")
}

/** Small translucent pill with icon used on the brand-gradient hero card. */
fun ChipGroup.addHeroPill(label: String, ok: Boolean) {
    val pill = TextView(context, null, 0, R.style.SenseConnect_StatusPill).apply {
        text = label
        setTextColor(ContextCompat.getColor(context, R.color.white))
        backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
        val icon = ContextCompat.getDrawable(context, if (ok) R.drawable.ic_check_circle else R.drawable.ic_error_outline)!!.mutate()
        icon.setTint(ContextCompat.getColor(context, R.color.white))
        icon.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(icon, null, null, null)
        contentDescription = "$label: ${if (ok) "ready" else "needs attention"}"
    }
    addView(pill)
}
