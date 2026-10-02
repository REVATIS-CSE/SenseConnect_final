package com.example.senseconnect.core.activitylog

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ActivityType { VISION, HEARING, COMMUNICATION, SOS, LOCATION }

/**
 * One entry in the on-device activity history.
 *
 * Privacy: entries hold short summaries only (e.g. "42 words recognised"). Raw audio, camera
 * images, transcripts and scanned text are never written here.
 */
data class ActivityEvent(
    val id: Long,
    val type: ActivityType,
    val title: String,
    val detail: String,
    val timestamp: Long,
)

class ActivityRepository(context: Context) {

    private val prefs = context.getSharedPreferences("senseconnect_activity", Context.MODE_PRIVATE)

    private val _events = MutableStateFlow(read())
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    fun log(type: ActivityType, title: String, detail: String) {
        val now = System.currentTimeMillis()
        val event = ActivityEvent(
            id = maxOf(now, (_events.value.firstOrNull()?.id ?: 0L) + 1),
            type = type,
            title = title,
            detail = detail,
            timestamp = now,
        )
        val updated = (listOf(event) + _events.value).take(MAX_EVENTS)
        _events.value = updated
        write(updated)
    }

    fun clear() {
        _events.value = emptyList()
        prefs.edit { remove(KEY_EVENTS) }
    }

    fun latest(type: ActivityType): ActivityEvent? = _events.value.firstOrNull { it.type == type }

    private fun read(): List<ActivityEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                ActivityEvent(
                    id = o.getLong("id"),
                    type = ActivityType.valueOf(o.getString("type")),
                    title = o.getString("title"),
                    detail = o.optString("detail"),
                    timestamp = o.getLong("timestamp"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(events: List<ActivityEvent>) {
        val array = JSONArray()
        events.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("type", e.type.name)
                    .put("title", e.title)
                    .put("detail", e.detail)
                    .put("timestamp", e.timestamp)
            )
        }
        prefs.edit { putString(KEY_EVENTS, array.toString()) }
    }

    private companion object {
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 200
    }
}
