package com.industrial.autohorn.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class AudioTelemetry(
    val engineName: String = "",
    val rms: Float = 0f,
    val latencyMs: Float = 0f,
    val xrunCount: Long = 0,
    val renderTimeMicros: Int = 0,
    val isPlaying: Boolean = false,
)

/**
 * Lock-free hand-off from the audio thread to any observer. The audio thread performs plain
 * stores/atomic array writes; only the observer allocates a [AudioTelemetry] value object.
 */
class AudioTelemetryBuffer(
    private val engineName: String,
    waveformCapacity: Int = WAVEFORM_SIZE,
) {
    val waveformCapacity = waveformCapacity

    private val rmsBits = AtomicInteger(0)
    private val latencyBits = AtomicInteger(0)
    private val xruns = AtomicLong(0)
    private val renderMicros = AtomicInteger(0)
    private val playing = AtomicInteger(0)
    private val waveform = AtomicIntegerArray(waveformCapacity)
    private val writeIndex = AtomicInteger(0)

    fun update(
        rms: Float,
        latencyMs: Float,
        xrunCount: Long,
        renderMicros: Int,
        isPlaying: Boolean,
    ) {
        rmsBits.set(java.lang.Float.floatToRawIntBits(rms.coerceIn(0f, 1f)))
        latencyBits.set(java.lang.Float.floatToRawIntBits(latencyMs.coerceAtLeast(0f)))
        xruns.set(xrunCount)
        this.renderMicros.set(renderMicros.coerceAtLeast(0))
        playing.set(if (isPlaying) 1 else 0)
    }

    fun pushWaveform(
        pcm: ShortArray,
        frames: Int,
        stride: Int = 1,
    ) {
        if (frames <= 0) return
        var index = writeIndex.get()
        var source = 0
        while (source < frames) {
            waveform.set(index % waveformCapacity, pcm[source].toInt())
            index++
            source += stride
        }
        writeIndex.set(index)
    }

    fun snapshot(): AudioTelemetry =
        AudioTelemetry(
            engineName = engineName,
            rms = java.lang.Float.intBitsToFloat(rmsBits.get()),
            latencyMs = java.lang.Float.intBitsToFloat(latencyBits.get()),
            xrunCount = xruns.get(),
            renderTimeMicros = renderMicros.get(),
            isPlaying = playing.get() == 1,
        )

    /**
     * Copies the newest [destination.size] samples in chronological order and normalizes them to
     * [-1, 1]. Returns the number of samples written.
     */
    fun readWaveform(destination: FloatArray): Int {
        if (destination.isEmpty()) return 0
        val count = minOf(destination.size, waveformCapacity)
        val end = writeIndex.get()
        val start = end - count
        for (index in 0 until count) {
            destination[index] = waveform.get(Math.floorMod(start + index, waveformCapacity)) / 32_768f
        }
        return count
    }

    fun clearWaveform() {
        for (index in 0 until waveformCapacity) waveform.set(index, 0)
        writeIndex.set(0)
    }

    /** Computes block RMS without allocating; useful for both Kotlin and JNI telemetry. */
    fun calculateRms(
        pcm: ShortArray,
        frames: Int,
    ): Float {
        if (frames <= 0) return 0f
        var sum = 0.0
        for (index in 0 until frames) {
            val normalized = pcm[index] / 32_768.0
            sum += normalized * normalized
        }
        return sqrt(sum / frames).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val WAVEFORM_SIZE = 256
    }
}
