package com.example.senseconnect.core.ui

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.example.senseconnect.core.status.Tone
import com.example.senseconnect.databinding.ItemSettingActionBinding
import com.example.senseconnect.databinding.ItemSettingSwitchBinding

/**
 * Configures a settings row with a switch. The whole row is one accessible "switch" element
 * so TalkBack reads title, summary and on/off state together.
 */
fun ItemSettingSwitchBinding.setup(
    @DrawableRes iconRes: Int,
    titleText: String,
    summaryText: String,
    onToggle: (Boolean) -> Unit,
) {
    icon.setImageResource(iconRes)
    title.text = titleText
    summary.text = summaryText
    row.setOnClickListener {
        val newValue = !toggle.isChecked
        toggle.isChecked = newValue
        Feedback.tap(it)
        onToggle(newValue)
    }
    ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            @Suppress("DEPRECATION") // setChecked(int) is API 36+; boolean form works on all versions
            info.isChecked = toggle.isChecked
        }
    })
}

fun ItemSettingSwitchBinding.setChecked(checked: Boolean) {
    if (toggle.isChecked != checked) toggle.isChecked = checked
}

fun ItemSettingActionBinding.setup(
    @DrawableRes iconRes: Int,
    titleText: String,
    summaryText: String,
    onClick: (View) -> Unit,
) {
    icon.setImageResource(iconRes)
    title.text = titleText
    summary.text = summaryText
    row.setOnClickListener {
        Feedback.tap(it)
        onClick(it)
    }
}

fun ItemSettingActionBinding.showPill(tone: Tone?, label: String?) {
    if (tone == null || label == null) {
        pill.visibility = View.GONE
        chevron.visibility = View.VISIBLE
    } else {
        pill.visibility = View.VISIBLE
        chevron.visibility = View.GONE
        pill.bindStatusPill(tone, label)
    }
    row.contentDescription = listOfNotNull(title.text, summary.text, label).joinToString(". ")
    // Ensure the row is still announced as actionable
    row.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }
}
