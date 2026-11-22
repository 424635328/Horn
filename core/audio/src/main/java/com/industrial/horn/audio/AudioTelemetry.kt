package com.industrial.horn.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Psychoacoustic output stage currently engaged on the render path.
 * Speaker mode runs the Virtual Bass System (VBS) for micro-speakers; headphone mode keeps the
 * pure full-range signal with binaural width optimisation.
 */
enum class OutputMode {
    SPEAKER_VBS,
    HEADPHONE_FULL_RANGE,
    ;

    val displayName: String
        get() =
            when (this) {
                SPEAKER_VBS -> "Speaker VBS"
                HEADPHONE_FULL_RANGE -> "Headphone Full-Range"
            }

    companion object {
        fun fromOrdinal(ordinal: Int): OutputMode = entries.getOrElse(ordinal) { SPEAKER_VBS }
    }
}

data class AudioTelemetry(
    val engineName: String = "",
    val rms: Float = 0f,
    val rmsLeft: Float = 0f,
    val rmsRight: Float = 0f,
    /** Pearson correlation between L and R blocks, in [-1, +1]; 1 for mono/identical channels. */
    val stereoCorrelation: Float = 1f,
    val outputMode: OutputMode = OutputMode.SPEAKER_VBS,
    val latencyMs: Float = 0f,
    val xrunCount: Long = 0,
    val renderTimeMicros: Int = 0,
    val isPlaying: Boolean = false,
)

/**
 * Lock-free hand-off from the audio thread to any observer. The audio thread performs plain
 * stores/atomic array writes; only the observer allocates a [AudioTelemetry] value object.
 *
 * Stereo contract: left and right waveform rings are independent ([readWaveformStereo]); the
 * legacy mono ring API reads the left ring so pre-stereo callers keep compiling and behaving.
 */
class AudioTelemetryBuffer(
    private val engineName: String,
    waveformCapacity: Int = WAVEFORM_SIZE,
) {
    val waveformCapacity = waveformCapacity

    private val rmsBits = AtomicInteger(0)
    private val rmsLeftBits = AtomicInteger(0)
    private val rmsRightBits = AtomicInteger(0)
    private val correlationBits = AtomicInteger(java.lang.Float.floatToRawIntBits(1f))
    private val modeOrdinal = AtomicInteger(OutputMode.SPEAKER_VBS.ordinal)
    private val latencyBits = AtomicInteger(0)
    private val xruns = AtomicLong(0)
    private val renderMicros = AtomicInteger(0)
    private val playing = AtomicInteger(0)
    private val waveformLeft = AtomicIntegerArray(waveformCapacity)
    private val waveformRight = AtomicIntegerArray(waveformCapacity)
    private val writeIndex = AtomicInteger(0)

    /** Legacy mono update: publishes the same level to both channels with correlation 1. */
    fun update(
        rms: Float,
        latencyMs: Float,
        xrunCount: Long,
        renderMicros: Int,
        isPlaying: Boolean,
    ) {
        updateStereo(
            rmsLeft = rms,
            rmsRight = rms,
            correlation = 1f,
            outputMode = OutputMode.SPEAKER_VBS,
            latencyMs = latencyMs,
            xrunCount = xrunCount,
            renderMicros = renderMicros,
            isPlaying = isPlaying,
        )
    }

    fun updateStereo(
        rmsLeft: Float,
        rmsRight: Float,
        correlation: Float,
        outputMode: OutputMode,
        latencyMs: Float,
        xrunCount: Long,
        renderMicros: Int,
        isPlaying: Boolean,
    ) {
        val left = rmsLeft.coerceIn(0f, 1f)
        val right = rmsRight.coerceIn(0f, 1f)
        rmsLeftBits.set(java.lang.Float.floatToRawIntBits(left))
        rmsRightBits.set(java.lang.Float.floatToRawIntBits(right))
        rmsBits.set(java.lang.Float.floatToRawIntBits(maxOf(left, right)))
        correlationBits.set(
            java.lang.Float.floatToRawIntBits(correlation.coerceIn(-1f, 1f)),
        )
        modeOrdinal.set(outputMode.ordinal)
        latencyBits.set(java.lang.Float.floatToRawIntBits(latencyMs.coerceAtLeast(0f)))
        xruns.set(xrunCount)
        this.renderMicros.set(renderMicros.coerceAtLeast(0))
        playing.set(if (isPlaying) 1 else 0)
    }

    /** Legacy mono waveform push: mirrors the same samples into both rings. */
    fun pushWaveform(
        pcm: ShortArray,
        frames: Int,
        stride: Int = 1,
    ) {
        pushWaveformStereo(pcm, pcm, frames, stride)
    }

    fun pushWaveformStereo(
        left: ShortArray,
        right: ShortArray,
        frames: Int,
        stride: Int = 1,
    ) {
        if (frames <= 0) return
        var index = writeIndex.get()
        var source = 0
        while (source < frames) {
            val slot = index % waveformCapacity
            waveformLeft.set(slot, left[source].toInt())
            waveformRight.set(slot, right[source].toInt())
            index++
            source += stride
        }
        writeIndex.set(index)
    }

    fun snapshot(): AudioTelemetry =
        AudioTelemetry(
            engineName = engineName,
            rms = java.lang.Float.intBitsToFloat(rmsBits.get()),
            rmsLeft = java.lang.Float.intBitsToFloat(rmsLeftBits.get()),
            rmsRight = java.lang.Float.intBitsToFloat(rmsRightBits.get()),
            stereoCorrelation = java.lang.Float.intBitsToFloat(correlationBits.get()),
            outputMode = OutputMode.fromOrdinal(modeOrdinal.get()),
            latencyMs = java.lang.Float.intBitsToFloat(latencyBits.get()),
            xrunCount = xruns.get(),
            renderTimeMicros = renderMicros.get(),
            isPlaying = playing.get() == 1,
        )

    /**
     * Copies the newest [destination.size] left-channel samples in chronological order and
     * normalizes them to [-1, 1]. Returns the number of samples written.
     */
    fun readWaveform(destination: FloatArray): Int = readRing(waveformLeft, destination)

    /** Copies the newest stereo pair into [left]/[right]; returns frames written per channel. */
    fun readWaveformStereo(
        left: FloatArray,
        right: FloatArray,
    ): Int {
        val count = readRing(waveformLeft, left)
        readRing(waveformRight, right)
        return count
    }

    private fun readRing(
        ring: AtomicIntegerArray,
        destination: FloatArray,
    ): Int {
        if (destination.isEmpty()) return 0
        val count = minOf(destination.size, waveformCapacity)
        val end = writeIndex.get()
        val start = end - count
        for (index in 0 until count) {
            destination[index] = ring.get(Math.floorMod(start + index, waveformCapacity)) / 32_768f
        }
        return count
    }

    fun clearWaveform() {
        for (index in 0 until waveformCapacity) {
            waveformLeft.set(index, 0)
            waveformRight.set(index, 0)
        }
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

    companion object {
        const val WAVEFORM_SIZE = 256

        /**
         * Pearson correlation between interleaved-free [left] and [right] blocks, in [-1, +1].
         * Silence (zero energy on both sides) reports 1: a silent block carries no spatial
         * information and must never look like anti-phase content.
         */
        fun stereoCorrelation(
            left: FloatArray,
            right: FloatArray,
            frames: Int,
        ): Float {
            if (frames <= 0) return 1f
            var sumLr = 0.0
            var sumL2 = 0.0
            var sumR2 = 0.0
            for (index in 0 until frames) {
                val l = left[index].toDouble()
                val r = right[index].toDouble()
                sumLr += l * r
                sumL2 += l * l
                sumR2 += r * r
            }
            val energy = sqrt(sumL2 * sumR2)
            if (energy < 1e-12) return 1f
            return (sumLr / energy).toFloat().coerceIn(-1f, 1f)
        }
    }
}
