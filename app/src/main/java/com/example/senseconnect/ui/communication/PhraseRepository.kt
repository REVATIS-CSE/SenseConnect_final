package com.example.senseconnect.ui.communication

import android.content.Context
import androidx.core.content.edit
import com.example.senseconnect.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class PhraseCategory(val label: String, val iconRes: Int) {
    FAVORITES("Favourites", R.drawable.ic_favorite),
    EMERGENCY("Emergency", R.drawable.ic_emergency),
    MEDICAL("Medical", R.drawable.ic_medical_services),
    BASIC_NEEDS("Basic needs", R.drawable.ic_accessibility_new),
    FOOD_WATER("Food & water", R.drawable.ic_restaurant),
    RESPONSES("Yes / No", R.drawable.ic_thumb_up),
    GENERAL("General", R.drawable.ic_chat),
    CUSTOM("My phrases", R.drawable.ic_edit),
}

data class Phrase(
    val id: String,
    val text: String,
    val category: PhraseCategory,
    val iconRes: Int,
    val isCustom: Boolean = false,
)

/**
 * Communication board content. Built-in phrases are bundled in the app (works offline);
 * favourites and the user's own phrases are stored on-device.
 */
class PhraseRepository(context: Context) {

    private val prefs = context.getSharedPreferences("senseconnect_phrases", Context.MODE_PRIVATE)

    val builtIn: List<Phrase> = listOf(
        // Emergency
        Phrase("em_help", "I need help.", PhraseCategory.EMERGENCY, R.drawable.ic_front_hand),
        Phrase("em_ambulance", "Please call an ambulance.", PhraseCategory.EMERGENCY, R.drawable.ic_local_hospital),
        Phrase("em_danger", "I am in danger.", PhraseCategory.EMERGENCY, R.drawable.ic_warning_amber),
        Phrase("em_contact", "Please call my emergency contact.", PhraseCategory.EMERGENCY, R.drawable.ic_phone_in_talk),
        // Medical
        Phrase("md_doctor", "I need a doctor.", PhraseCategory.MEDICAL, R.drawable.ic_medical_services),
        Phrase("md_pain", "I am in pain.", PhraseCategory.MEDICAL, R.drawable.ic_healing),
        Phrase("md_medication", "I need my medication.", PhraseCategory.MEDICAL, R.drawable.ic_medication),
        Phrase("md_dizzy", "I feel dizzy.", PhraseCategory.MEDICAL, R.drawable.ic_error_outline),
        // Basic needs
        Phrase("bn_assist", "I need assistance.", PhraseCategory.BASIC_NEEDS, R.drawable.ic_accessible),
        Phrase("bn_restroom", "I need to use the restroom.", PhraseCategory.BASIC_NEEDS, R.drawable.ic_wc),
        Phrase("bn_rest", "I need to rest.", PhraseCategory.BASIC_NEEDS, R.drawable.ic_bed),
        Phrase("bn_cold", "I am feeling cold.", PhraseCategory.BASIC_NEEDS, R.drawable.ic_bedtime),
        // Food & water
        Phrase("fw_water", "I need water.", PhraseCategory.FOOD_WATER, R.drawable.ic_water_drop),
        Phrase("fw_food", "I need food.", PhraseCategory.FOOD_WATER, R.drawable.ic_restaurant),
        Phrase("fw_hungry", "I am hungry.", PhraseCategory.FOOD_WATER, R.drawable.ic_restaurant),
        Phrase("fw_enough", "No more, thank you.", PhraseCategory.FOOD_WATER, R.drawable.ic_done),
        // Responses
        Phrase("rs_yes", "Yes.", PhraseCategory.RESPONSES, R.drawable.ic_thumb_up),
        Phrase("rs_no", "No.", PhraseCategory.RESPONSES, R.drawable.ic_thumb_down),
        Phrase("rs_wait", "Please wait.", PhraseCategory.RESPONSES, R.drawable.ic_hourglass_empty),
        Phrase("rs_unsure", "I don't know.", PhraseCategory.RESPONSES, R.drawable.ic_help_outline),
        // General
        Phrase("gn_hello", "Hello, nice to meet you.", PhraseCategory.GENERAL, R.drawable.ic_waving_hand),
        Phrase("gn_thanks", "Thank you.", PhraseCategory.GENERAL, R.drawable.ic_sentiment_satisfied),
        Phrase("gn_slow", "Please speak slowly.", PhraseCategory.GENERAL, R.drawable.ic_speed),
        Phrase("gn_write", "Please write it down for me.", PhraseCategory.GENERAL, R.drawable.ic_edit),
        Phrase("gn_aac", "I cannot speak. I use this app to communicate.", PhraseCategory.GENERAL, R.drawable.ic_record_voice_over),
    )

    private val _favorites = MutableStateFlow(prefs.getStringSet(KEY_FAVORITES, DEFAULT_FAVORITES)!!.toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _custom = MutableStateFlow(readCustom())
    val custom: StateFlow<List<Phrase>> = _custom.asStateFlow()

    fun phrasesFor(category: PhraseCategory): List<Phrase> = when (category) {
        PhraseCategory.FAVORITES -> (builtIn + _custom.value).filter { it.id in _favorites.value }
        PhraseCategory.CUSTOM -> _custom.value
        else -> builtIn.filter { it.category == category }
    }

    fun isFavorite(id: String) = id in _favorites.value

    fun toggleFavorite(id: String): Boolean {
        val updated = _favorites.value.toMutableSet().apply { if (!add(id)) remove(id) }
        _favorites.value = updated
        prefs.edit { putStringSet(KEY_FAVORITES, updated) }
        return id in updated
    }

    fun addCustom(text: String): Phrase {
        val phrase = Phrase(
            id = "custom_${System.currentTimeMillis()}",
            text = text.trim(),
            category = PhraseCategory.CUSTOM,
            iconRes = R.drawable.ic_chat,
            isCustom = true,
        )
        _custom.value = _custom.value + phrase
        writeCustom()
        return phrase
    }

    fun removeCustom(id: String) {
        _custom.value = _custom.value.filterNot { it.id == id }
        if (id in _favorites.value) toggleFavorite(id)
        writeCustom()
    }

    private fun readCustom(): List<Phrase> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Phrase(o.getString("id"), o.getString("text"), PhraseCategory.CUSTOM, R.drawable.ic_chat, isCustom = true)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCustom() {
        val array = JSONArray()
        _custom.value.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
        prefs.edit { putString(KEY_CUSTOM, array.toString()) }
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_CUSTOM = "custom_phrases"
        val DEFAULT_FAVORITES = setOf("em_help", "md_doctor", "fw_water", "rs_yes", "rs_no", "gn_thanks")
    }
}
