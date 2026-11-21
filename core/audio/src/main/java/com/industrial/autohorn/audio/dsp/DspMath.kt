package com.industrial.autohorn.audio.dsp

import kotlin.math.PI
import kotlin.math.tanh

internal const val TWO_PI = 2.0 * PI

internal fun smoothStep(value: Double): Double {
    val clamped = value.coerceIn(0.0, 1.0)
    return clamped * clamped * (3.0 - 2.0 * clamped)
}

/** Shared soft saturator. Asymptotically bounded, so it can never overflow. */
internal fun softSaturate(
    value: Double,
    drive: Double,
): Double = tanh(value * drive) / tanh(drive)
