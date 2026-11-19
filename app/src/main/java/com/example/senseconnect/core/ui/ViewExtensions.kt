package com.example.senseconnect.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.senseconnect.R
import com.example.senseconnect.core.status.Tone

// ---------------------------------------------------------------- Insets

/** Pads this view by the system bar insets while preserving its XML padding. */
fun View.applySystemBarPadding(top: Boolean = false, bottom: Boolean = false) {
    val initialTop = paddingTop
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.updatePadding(
            top = if (top) initialTop + bars.top else v.paddingTop,
            bottom = if (bottom) initialBottom + maxOf(bars.bottom, ime.bottom) else v.paddingBottom,
        )
        insets
    }
}

/** Adds the bottom system bar inset to this view's bottom margin (for floating controls). */
fun View.applySystemBarMargin(bottom: Boolean = true) {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val initialBottom = lp.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        if (bottom) v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = initialBottom + bars.bottom }
        insets
    }
}

// ---------------------------------------------------------------- Status tones

data class ToneColors(@ColorRes val container: Int, @ColorRes val onContainer: Int, @ColorRes val accent: Int)

fun Tone.colors(): ToneColors = when (this) {
    Tone.SUCCESS -> ToneColors(R.color.sc_success_container, R.color.sc_on_success_container, R.color.sc_success)
    Tone.WARNING -> ToneColors(R.color.sc_warning_container, R.color.sc_on_warning_container, R.color.sc_warning)
    Tone.DANGER -> ToneColors(R.color.sc_emergency_container, R.color.sc_on_emergency_container, R.color.sc_emergency)
    Tone.NEUTRAL -> ToneColors(R.color.sc_neutral_container, R.color.sc_on_neutral_container, R.color.sc_neutral)
    Tone.INFO -> ToneColors(R.color.sc_primary_container, R.color.sc_on_primary_container, R.color.sc_primary)
}

fun Context.color(@ColorRes res: Int) = ContextCompat.getColor(this, res)

/**
 * Renders a status "pill" (TextView styled with bg_pill + a leading dot). The label is always
 * text, so state is never conveyed by colour alone.
 */
fun TextView.bindStatusPill(tone: Tone, label: String) {
    val c = tone.colors()
    text = label
    backgroundTintList = ColorStateList.valueOf(context.color(c.container))
    setTextColor(context.color(c.onContainer))
    compoundDrawablesRelative.firstOrNull()?.setTint(context.color(c.accent))
}

fun ImageView.tintTile(@ColorRes container: Int, @ColorRes icon: Int) {
    backgroundTintList = ColorStateList.valueOf(context.color(container))
    imageTintList = ColorStateList.valueOf(context.color(icon))
}

// ---------------------------------------------------------------- Sharing

fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.shareText(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, title))
}
