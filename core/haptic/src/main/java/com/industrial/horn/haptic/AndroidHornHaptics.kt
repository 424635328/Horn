package com.industrial.horn.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.industrial.horn.audio.HornHapticFeedback
import com.industrial.horn.audio.HornType

/**
 * Version-safe vibrator wrapper with per-voice haptic signatures.
 *
 * Car = steady mid-strong, Train = maximum amplitude low-frequency heaviness, E-bike = light
 * fast ticks, Motorcycle = compact mechanical grain. Devices without amplitude control get a
 * rhythm-only fallback; devices without a vibrator are a no-op.
 */
class AndroidHornHaptics(
    context: Context,
) : HornHapticFeedback {
    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)
    private val canVibrate: Boolean = vibrator?.hasVibrator() == true

    override fun startHonk(type: HornType) {
        val device = vibrator ?: return
        if (!canVibrate) return

        val pattern = HapticSignatures.forType(type)
        val effect =
            if (device.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(
                    pattern.timingsMs,
                    pattern.amplitudes,
                    REPEAT_FROM_START,
                )
            } else {
                VibrationEffect.createWaveform(pattern.fallbackTimingsMs, REPEAT_FROM_START)
            }
        runCatching { device.vibrate(effect) }
    }

    override fun stop() {
        val device = vibrator ?: return
        runCatching { device.cancel() }
    }

    override fun release() {
        stop()
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: RuntimeException) {
            null
        }

    private companion object {
        const val REPEAT_FROM_START = 0
    }
}
