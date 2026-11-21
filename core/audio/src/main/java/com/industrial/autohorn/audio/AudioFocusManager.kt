package com.industrial.autohorn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Owns a transient-gain [AudioFocusRequest] for the horn.
 *
 * Requesting transient gain pauses or ducks other media. The request is always abandoned when
 * playback stops, which lets the previous app resume automatically. Focus loss is reported so
 * the session can immediately silence the engine, even during a phone call or navigation prompt.
 */
class AudioFocusManager(
    context: Context,
    private val onFocusLost: (Int) -> Unit,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    private val listener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> {
                    focusRequest = null
                    onFocusLost(change)
                }
            }
        }

    fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val manager = audioManager ?: return false

        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener)
                .build()

        return try {
            val result = manager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                true
            } else {
                false
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (_: RuntimeException) {
            // The system may already have revoked focus; there is nothing else to release.
        }
    }
}
