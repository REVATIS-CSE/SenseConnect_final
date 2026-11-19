package com.example.senseconnect.core.emergency

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator

/**
 * Loud looping alarm to attract nearby attention — useful for users who cannot shout for help.
 * Uses the device alarm sound; falls back to a generated tone if none is configured.
 */
class AlarmPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    val isPlaying: Boolean get() = player?.isPlaying == true || tone != null

    fun start(): Boolean {
        if (isPlaying) return true
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri!!)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
        }
        return isPlaying
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        tone?.runCatching { stopTone(); release() }
        tone = null
    }
}
