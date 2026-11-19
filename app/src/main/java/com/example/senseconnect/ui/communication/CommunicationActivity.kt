package com.example.senseconnect.ui.communication

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.senseconnect.R
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.core.ui.color
import com.example.senseconnect.core.ui.copyToClipboard
import com.example.senseconnect.core.ui.tintTile
import com.example.senseconnect.databinding.ActivityCommunicationBinding
import com.example.senseconnect.databinding.DialogTextFieldsBinding
import com.example.senseconnect.databinding.ItemPhraseBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Communication board: choose or type a message, SenseConnect speaks it aloud. */
class CommunicationActivity : BaseActivity() {

    private lateinit var binding: ActivityCommunicationBinding
    private val phrases get() = container.phrases
    private val speech get() = container.speech

    private var category = PhraseCategory.FAVORITES
    /** Currently displayed message; [selectedPhrase] is null when the message was typed. */
    private var message: String = ""
    private var selectedPhrase: Phrase? = null

    private val adapter = PhraseAdapter(
        isSelected = { it.id == selectedPhrase?.id },
        isFavorite = { phrases.isFavorite(it.id) },
        onClick = ::onPhraseTapped,
        onToggleFavorite = ::toggleFavorite,
        onDelete = ::confirmDelete,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommunicationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.root.applySystemBarPadding(top = true)
        binding.bottomPanel.applySystemBarPadding(bottom = true)

        with(binding.header.root) {
            setTitle(R.string.communication_title)
            setSubtitle(R.string.communication_workflow)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_communication)
            setOnMenuItemClickListener { addPhrase(); true }
        }

        if (phrases.phrasesFor(PhraseCategory.FAVORITES).isEmpty()) category = PhraseCategory.EMERGENCY
        setupChips()
        binding.phraseGrid.layoutManager = GridLayoutManager(this, 2)
        binding.phraseGrid.adapter = adapter

        binding.btnYes.setOnClickListener { quickResponse("rs_yes") }
        binding.btnNo.setOnClickListener { quickResponse("rs_no") }
        binding.btnWait.setOnClickListener { quickResponse("rs_wait") }

        TooltipCompat.setTooltipText(binding.btnShow, getString(R.string.show_full_screen))
        TooltipCompat.setTooltipText(binding.btnCopy, getString(R.string.copy))
        binding.btnSpeak.setOnClickListener { if (speech.speaking.value) speech.stop() else speakCurrent() }
        binding.btnShow.setOnClickListener { if (message.isNotBlank()) showFullScreen(message) }
        binding.btnCopy.setOnClickListener {
            if (message.isBlank()) return@setOnClickListener
            copyToClipboard(getString(R.string.comm_message_label), message)
            Feedback.confirm(it)
            snackbar(getString(R.string.copied))
        }
        binding.btnFavorite.setOnClickListener { selectedPhrase?.let(::toggleFavorite) }
        binding.btnUseTyped.setOnClickListener { useTypedMessage() }
        binding.inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { useTypedMessage(); true } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(phrases.favorites, phrases.custom) { _, _ -> }.collect { refreshGrid() } }
                launch {
                    speech.speaking.collect { speaking ->
                        binding.btnSpeak.setText(if (speaking) R.string.stop else R.string.speak)
                        binding.btnSpeak.setIconResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_volume_up)
                    }
                }
            }
        }
        renderMessage()
    }

    private fun setupChips() {
        PhraseCategory.entries.forEach { cat ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = cat.label
                isCheckable = true
                isChecked = cat == category
                setChipIconResource(cat.iconRes)
                isChipIconVisible = true
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        category = cat
                        refreshGrid()
                    }
                }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun refreshGrid() {
        val list = phrases.phrasesFor(category)
        adapter.submitList(list)
        adapter.notifyItemRangeChanged(0, list.size) // favourite/selection markers
        val empty = list.isEmpty()
        binding.emptyState.root.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) with(binding.emptyState) {
            val custom = category == PhraseCategory.CUSTOM
            stateIcon.setImageResource(if (custom) R.drawable.ic_edit else R.drawable.ic_favorite_border)
            stateIcon.tintTile(R.color.sc_tertiary_container, R.color.sc_on_tertiary_container)
            stateTitle.setText(if (custom) R.string.comm_custom_empty_title else R.string.comm_fav_empty_title)
            stateBody.setText(if (custom) R.string.comm_custom_empty_body else R.string.comm_fav_empty_body)
            stateAction.visibility = if (custom) View.VISIBLE else View.GONE
            stateAction.setText(R.string.add_phrase)
            stateAction.setIconResource(R.drawable.ic_add)
            stateAction.setOnClickListener { addPhrase() }
        }
    }

    private fun onPhraseTapped(phrase: Phrase, view: View) {
        Feedback.tap(view)
        selectedPhrase = phrase
        message = phrase.text
        renderMessage()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (container.settings.current.speakOnTap) speakCurrent()
    }

    private fun quickResponse(id: String) {
        val phrase = phrases.builtIn.first { it.id == id }
        selectedPhrase = phrase
        message = phrase.text
        renderMessage()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        speakCurrent() // quick responses always speak immediately
    }

    private fun useTypedMessage() {
        val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            binding.typeLayout.error = getString(R.string.comm_type_empty)
            return
        }
        binding.typeLayout.error = null
        selectedPhrase = null
        message = text
        binding.inputMessage.text = null
        renderMessage()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        speakCurrent()
    }

    private fun speakCurrent() {
        if (message.isBlank()) {
            snackbar(getString(R.string.comm_choose_first))
            return
        }
        if (speech.speak(message)) {
            Feedback.confirm(binding.btnSpeak)
            val phrase = selectedPhrase
            // Typed free text is private: only built-in / saved phrases are written to history.
            container.activityLog.log(
                ActivityType.COMMUNICATION,
                if (phrase != null) "Phrase spoken" else "Typed message spoken",
                phrase?.text ?: "${message.split(Regex("\\s+")).size} words",
            )
        } else {
            snackbar(getString(R.string.tts_not_ready))
        }
    }

    private fun renderMessage() = with(binding) {
        val hasMessage = message.isNotBlank()
        tvMessage.text = if (hasMessage) message else getString(R.string.comm_message_placeholder)
        tvMessage.alpha = if (hasMessage) 1f else 0.65f
        btnShow.isEnabled = hasMessage
        btnCopy.isEnabled = hasMessage
        val phrase = selectedPhrase
        btnFavorite.visibility = if (phrase != null) View.VISIBLE else View.GONE
        if (phrase != null) {
            val fav = phrases.isFavorite(phrase.id)
            btnFavorite.setIconResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            btnFavorite.contentDescription = getString(if (fav) R.string.remove_favourite else R.string.add_favourite)
            TooltipCompat.setTooltipText(btnFavorite, btnFavorite.contentDescription)
        }
    }

    private fun toggleFavorite(phrase: Phrase) {
        val nowFavorite = phrases.toggleFavorite(phrase.id)
        Feedback.tap(binding.root)
        snackbar(getString(if (nowFavorite) R.string.favourite_added else R.string.favourite_removed))
        renderMessage()
    }

    private fun addPhrase() {
        val dialog = DialogTextFieldsBinding.inflate(layoutInflater)
        dialog.layoutFirst.hint = getString(R.string.add_phrase_hint)
        dialog.inputFirst.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        dialog.layoutSecond.visibility = View.GONE
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_phrase)
            .setMessage(R.string.add_phrase_body)
            .setView(dialog.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = dialog.inputFirst.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    phrases.addCustom(text)
                    selectCategory(PhraseCategory.CUSTOM)
                    snackbar(getString(R.string.phrase_saved))
                }
            }
            .show()
    }

    private fun confirmDelete(phrase: Phrase) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_phrase_title)
            .setMessage(phrase.text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                phrases.removeCustom(phrase.id)
                if (selectedPhrase?.id == phrase.id) {
                    selectedPhrase = null
                    renderMessage()
                }
            }
            .show()
    }

    private fun selectCategory(cat: PhraseCategory) {
        val index = PhraseCategory.entries.indexOf(cat)
        (binding.categoryChips.getChildAt(index) as? Chip)?.isChecked = true
    }

    /** Large high-contrast display so the message can be shown to someone in a noisy place. */
    private fun showFullScreen(text: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(color(R.color.white))
            setBackgroundColor(color(R.color.black))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val pad = (32 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = "$text. ${getString(R.string.tap_to_close)}"
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(view)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show()
    }

    override fun onStop() {
        speech.stop()
        super.onStop()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).setAnchorView(binding.bottomPanel).show()
    }
}

private class PhraseAdapter(
    private val isSelected: (Phrase) -> Boolean,
    private val isFavorite: (Phrase) -> Boolean,
    private val onClick: (Phrase, View) -> Unit,
    private val onToggleFavorite: (Phrase) -> Unit,
    private val onDelete: (Phrase) -> Unit,
) : ListAdapter<Phrase, PhraseAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPhraseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPhraseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val phrase = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val visual = categoryColors(phrase.category)
        b.icon.setImageResource(phrase.iconRes)
        b.icon.tintTile(visual.first, visual.second)
        b.text.text = phrase.text
        val fav = isFavorite(phrase)
        b.favorite.visibility = if (fav) View.VISIBLE else View.GONE
        val selected = isSelected(phrase)
        b.card.strokeWidth = (ctx.resources.displayMetrics.density * if (selected) 2.5f else 1f).toInt()
        b.card.strokeColor = ctx.getColor(if (selected) R.color.sc_tertiary else R.color.sc_outline_variant)
        b.card.isSelected = selected
        b.card.contentDescription = buildString {
            append(phrase.text)
            if (fav) append(". ").append(ctx.getString(R.string.favourite))
            if (selected) append(". ").append(ctx.getString(R.string.selected))
        }
        b.card.setOnClickListener { onClick(phrase, it) }
        b.card.setOnLongClickListener {
            Feedback.longPress(it)
            if (phrase.isCustom) onDelete(phrase) else onToggleFavorite(phrase)
            true
        }
        // TalkBack custom actions mirror the long-press gestures.
        ViewCompat.replaceAccessibilityAction(
            b.card, AccessibilityActionCompat.ACTION_LONG_CLICK,
            ctx.getString(if (phrase.isCustom) R.string.delete else if (fav) R.string.remove_favourite else R.string.add_favourite),
            null,
        )
    }

    private fun categoryColors(category: PhraseCategory): Pair<Int, Int> = when (category) {
        PhraseCategory.EMERGENCY -> R.color.sc_emergency_container to R.color.sc_on_emergency_container
        PhraseCategory.MEDICAL -> R.color.sc_secondary_container to R.color.sc_on_secondary_container
        PhraseCategory.FOOD_WATER, PhraseCategory.BASIC_NEEDS -> R.color.sc_primary_container to R.color.sc_on_primary_container
        PhraseCategory.RESPONSES -> R.color.sc_success_container to R.color.sc_on_success_container
        else -> R.color.sc_tertiary_container to R.color.sc_on_tertiary_container
    }

    private object Diff : DiffUtil.ItemCallback<Phrase>() {
        override fun areItemsTheSame(a: Phrase, b: Phrase) = a.id == b.id
        override fun areContentsTheSame(a: Phrase, b: Phrase) = a == b
    }
}
