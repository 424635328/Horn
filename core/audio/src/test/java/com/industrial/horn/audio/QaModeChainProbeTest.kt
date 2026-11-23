package com.industrial.horn.audio

import com.industrial.horn.audio.dsp.OutputModeChain
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Throwaway probe 3: clean (leakage-controlled) calibration of the VBS QA expectations
 * (C7/C8), wet-tail block correlation (B4) and band-averaged notch depth (B5). Deleted after use.
 */
class QaModeChainProbeTest {
    @Test
    fun probeTailBlockCorrelation() {
        for (type in listOf(HornType.CAR, HornType.TRAIN)) {
            for (space in AcousticSpace.entries) {
                val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, 128, type)
                renderer.setAcousticSpace(space)
                renderer.start()
                repeat(80) { renderer.renderBlock() }
                renderer.stop()

                var worst = -2f
                var best = 2f
                var sum = 0.0
                var count = 0
                while (count < 400) {
                    val frames = renderer.renderBlock()
                    if (frames == 0) break
                    val left = FloatArray(frames)
                    val right = FloatArray(frames)
                    for (frame in 0 until frames) {
                        left[frame] = renderer.outputBuffer[2 * frame]
                        right[frame] = renderer.outputBuffer[2 * frame + 1]
                    }
                    val energy = QaMeasurement.rmsOf(left, 0, frames) + QaMeasurement.rmsOf(right, 0, frames)
                    if (energy < 1e-6) continue
                    val corr = AudioTelemetryBuffer.stereoCorrelation(left, right, frames)
                    worst = maxOf(worst, corr)
                    best = minOf(best, corr)
                    sum += corr
                    count++
                }
                renderer.panic()
                println("PROBE3_TAILCORR $type $space blocks=$count worst=$worst best=$best mean=${sum / count}")
            }
        }
    }

    @Test
    fun probeCleanBands() {
        for (type in listOf(HornType.TRAIN, HornType.EBIKE)) {
            for (mode in OutputMode.entries) {
                val fold =
                    QaMeasurement.fold(
                        QaMeasurement.renderPipeline(type, AcousticSpace.STREET, mode = mode, warmupBlocks = 80, measureBlocks = 240),
                        240 * QaMeasurement.BLOCK_FRAMES,
                    )
                val low = QaMeasurement.bandEnergyWindowed(fold, 30.0, 85.0, 0.2)
                val mid = QaMeasurement.bandEnergyWindowed(fold, 250.0, 900.0, 0.2)
                println("PROBE3_BAND $type $mode low30_85=$low mid250_900=$mid")
            }
        }
    }

    @Test
    fun probeChainWitnesses() {
        // (a) Pure low tones: the classic missing-fundamental VBS case.
        for (mode in OutputMode.entries) {
            for (injection in floatArrayOf(0f, 1f)) {
                val fold = runChain(sineMix(doubleArrayOf(123.47, 155.56), doubleArrayOf(0.10, 0.10)), mode, injection)
                val mid = QaMeasurement.bandEnergyWindowed(fold, 250.0, 900.0, 0.3)
                val low = QaMeasurement.bandEnergyWindowed(fold, 30.0, 85.0, 0.3)
                println("PROBE3_LOW $mode inj=$injection mid250_900=$mid low30_85=$low")
            }
        }

        // (b) Sub-90 Hz rich input (train intermod difference tones): protective high-pass case.
        for (mode in OutputMode.entries) {
            for (injection in floatArrayOf(0f, 1f)) {
                val fold = runChain(sineMix(doubleArrayOf(32.09, 58.86), doubleArrayOf(0.10, 0.10)), mode, injection)
                val low = QaMeasurement.bandEnergyWindowed(fold, 30.0, 85.0, 0.3)
                println("PROBE3_SUB $mode inj=$injection low30_85=$low")
            }
        }

        // (c) Train-like chord with its own endogenous 250-900 Hz content.
        for (mode in OutputMode.entries) {
            for (injection in floatArrayOf(0f, 1f)) {
                val fold =
                    runChain(
                        sineMix(
                            doubleArrayOf(123.47, 155.56, 311.13, 369.99, 493.88),
                            doubleArrayOf(0.06, 0.10, 0.18, 0.16, 0.14),
                        ),
                        mode,
                        injection,
                    )
                val mid = QaMeasurement.bandEnergyWindowed(fold, 250.0, 900.0, 0.3)
                val low = QaMeasurement.bandEnergyWindowed(fold, 30.0, 85.0, 0.3)
                println("PROBE3_CHORD $mode inj=$injection mid250_900=$mid low30_85=$low")
            }
        }
    }

    @Test
    fun probeBandNotch() {
        for (space in AcousticSpace.entries) {
            val pointNotch = QaMeasurement.monoNotchDepthDb(space)
            val bandNotch = QaMeasurement.monoBandNotchDepthDb(space)
            println("PROBE3_NOTCH $space pointNotchDb=$pointNotch bandNotchDb=$bandNotch")
        }
    }

    private fun runChain(
        source: FloatArray,
        mode: OutputMode,
        injection: Float,
    ): FloatArray {
        val chain = OutputModeChain(DspMeasurement.SAMPLE_RATE)
        chain.setMode(mode)
        chain.setInjection(injection)
        val frames = source.size / 2
        val warmup = FloatArray(2 * frames)
        for (frame in 0 until frames) {
            warmup[2 * frame] = source[2 * (frame % frames)]
            warmup[2 * frame + 1] = source[2 * (frame % frames) + 1]
        }
        chain.process(warmup, frames)
        val measured = source.copyOf()
        chain.process(measured, frames)
        return DspMeasurement.monoFold(measured, frames)
    }

    private fun sineMix(
        frequencies: DoubleArray,
        amplitudes: DoubleArray,
    ): FloatArray {
        val frames = DspMeasurement.SAMPLE_RATE
        val stereo = FloatArray(2 * frames)
        for (frame in 0 until frames) {
            var sample = 0.0
            for (index in frequencies.indices) {
                sample += amplitudes[index] * sin(2.0 * PI * frequencies[index] * frame / DspMeasurement.SAMPLE_RATE)
            }
            stereo[2 * frame] = sample.toFloat()
            stereo[2 * frame + 1] = sample.toFloat()
        }
        return stereo
    }
}
