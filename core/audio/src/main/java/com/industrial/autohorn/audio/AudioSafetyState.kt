package com.industrial.autohorn.audio

import kotlin.math.roundToInt

data class AudioSafetyState(
    val mediaVolume: Int = 0,
    val maxMediaVolume: Int = 1,
    val headphonesConnected: Boolean = false,
    val earProtectionActive: Boolean = false,
) {
    val volumeFraction: Float
        get() =
            if (maxMediaVolume > 0) {
                (mediaVolume.toFloat() / maxMediaVolume).coerceIn(0f, 1f)
            } else {
                0f
            }

    val isZeroVolume: Boolean
        get() = mediaVolume <= 0
}

/** Pure policy, testable without AudioManager. */
object AudioSafetyPolicy {
    fun evaluate(
        mediaVolume: Int,
        maxMediaVolume: Int,
        headphonesConnected: Boolean,
    ): AudioSafetyState {
        val boundedMax = maxMediaVolume.coerceAtLeast(1)
        val boundedVolume = mediaVolume.coerceIn(0, boundedMax)
        val fraction = boundedVolume.toFloat() / boundedMax
        return AudioSafetyState(
            mediaVolume = boundedVolume,
            maxMediaVolume = boundedMax,
            headphonesConnected = headphonesConnected,
            earProtectionActive = headphonesConnected && fraction > 0.7f,
        )
    }

    fun sixtyPercentVolume(maxMediaVolume: Int): Int = (maxMediaVolume.coerceAtLeast(1) * 0.6f).roundToInt()
}
