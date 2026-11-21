package com.industrial.autohorn.audio

/**
 * Haptic seam owned by the audio/session layer. The Android vibrator implementation lives in
 * :core:haptic, which depends on this module; :core:audio never depends on the vibrator module.
 */
interface HornHapticFeedback {
    fun startHonk(type: HornType)

    fun stop()

    fun release()
}
