package com.industrial.horn.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Objective acoustic QA measurement helpers shared by the v3.6 acceptance suites
 * (AcousticQaTest / StereoSceneQaTest / VbsQaTest / RuntimeQaTest).
 *
 * Measurement conventions deliberately reuse the existing `DspMeasurement` vocabulary
 * (`magnitude`, `monoFold`, `rms`, `spectralCentroid`) so QA numbers stay comparable with the
 * calibration tests; this object only adds the band/impulse-response helpers those suites need.
 */
internal object QaMeasurement {
    const val SAMPLE_RATE = 48_000

    /** Block size used for scene/full-pipeline rendering (matches DspMeasurement.FRAMES_PER_BLOCK). */
    const val BLOCK_FRAMES = 240

    /** Renders [measureBlocks] blocks through the full [HornRenderer] pipeline after [warmupBlocks]. */
    fun renderPipeline(
        type: HornType,
        space: AcousticSpace,
        mode: OutputMode = OutputMode.SPEAKER_VBS,
        expression: Float = 0f,
        warmupBlocks: Int = 40,
        measureBlocks: Int = 120,
        framesPerBlock: Int = BLOCK_FRAMES,
    ): FloatArray {
        val renderer = HornRenderer(SAMPLE_RATE, framesPerBlock, type)
        renderer.setAcousticSpace(space)
        renderer.setOutputMode(mode)
        renderer.setExpression(expression)
        renderer.start()
        repeat(warmupBlocks) { renderer.renderBlock() }
        val stereo = FloatArray(2 * measureBlocks * framesPerBlock)
        for (block in 0 until measureBlocks) {
            val frames = renderer.renderBlock()
            for (index in 0 until 2 * frames) {
                stereo[block * 2 * framesPerBlock + index] = renderer.outputBuffer[index]
            }
        }
        renderer.panic()
        return stereo
    }

    /**
     * Renders the voice layer exactly as `HornRenderer` drives it (synthesizer + shared master
     * envelope, double-envelope ruling C5) starting at the note-on sample. The loudness gain
     * smoother and the scene stage are intentionally excluded so attack transients are measured
     * at the layer where the frozen attack/release values live (65 ms train vs 4 ms e-bike).
     */
    fun renderVoiceFromNoteOn(
        type: HornType,
        frames: Int,
    ): FloatArray {
        val voice = HornVoiceCatalog.createVoice(type, SAMPLE_RATE)
        voice.synthesizer.reset()
        voice.envelope.reset()
        voice.envelope.noteOn()
        (voice.synthesizer as? NoteAwareSynthesizer)?.setNoteActive(true)

        val scratch = FloatArray(2 * BLOCK_FRAMES)
        val stereo = FloatArray(2 * frames)
        var written = 0
        while (written < frames) {
            val count = minOf(BLOCK_FRAMES, frames - written)
            voice.synthesizer.render(scratch, count)
            for (index in 0 until count) {
                val gain = voice.envelope.nextSample()
                stereo[2 * (written + index)] = scratch[2 * index] * gain
                stereo[2 * (written + index) + 1] = scratch[2 * index + 1] * gain
            }
            written += count
        }
        return stereo
    }

    /** Mono fold `(L+R)/2` of an interleaved stereo buffer (02 §5). */
    fun fold(
        stereo: FloatArray,
        frames: Int = stereo.size / 2,
    ): FloatArray = DspMeasurement.monoFold(stereo, frames)

    fun rmsOf(
        samples: FloatArray,
        from: Int,
        to: Int,
    ): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (index in from until to) {
            sum += samples[index].toDouble() * samples[index]
        }
        return sqrt(sum / (to - from))
    }

    /** 1/6-octave probe grid from [lowHz] to [highHz] inclusive (02 §5 sweep convention). */
    fun sixthOctaveGrid(
        lowHz: Double,
        highHz: Double,
    ): DoubleArray {
        val probes = mutableListOf<Double>()
        var frequency = lowHz
        while (frequency <= highHz * 1.0001) {
            probes.add(frequency)
            frequency *= 2.0.pow(1.0 / 6.0)
        }
        return probes.toDoubleArray()
    }

    /**
     * Band-energy proxy: summed squared `DspMeasurement.magnitude` probes on a 1/6-octave grid.
     * Identical probe grids make ratios between two renders directly comparable in dB.
     */
    fun bandEnergy(
        samples: FloatArray,
        lowHz: Double,
        highHz: Double,
        startFraction: Double = 0.2,
    ): Double {
        var energy = 0.0
        for (frequency in sixthOctaveGrid(lowHz, highHz)) {
            val magnitude = DspMeasurement.magnitude(samples, frequency, startFraction)
            energy += magnitude * magnitude
        }
        return energy
    }

    fun bandEnergyDb(
        samples: FloatArray,
        lowHz: Double,
        highHz: Double,
        startFraction: Double = 0.2,
    ): Double = 10.0 * log10(bandEnergy(samples, lowHz, highHz, startFraction).coerceAtLeast(1e-30))

    /** Ratio in dB of two band energies measured on the same probe grid. */
    fun bandRatioDb(
        samples: FloatArray,
        lowHz: Double,
        highHz: Double,
        referenceLowHz: Double,
        referenceHighHz: Double,
        startFraction: Double = 0.2,
    ): Double {
        val band = bandEnergy(samples, lowHz, highHz, startFraction)
        val reference = bandEnergy(samples, referenceLowHz, referenceHighHz, startFraction)
        return 10.0 * log10((band / reference.coerceAtLeast(1e-30)).coerceAtLeast(1e-30))
    }

    /**
     * Blackman-windowed correlated-sum magnitude at [frequencyHz]. Unlike the rectangular
     * `DspMeasurement.magnitude` probe this suppresses leakage from nearby strong tones
     * (sidelobes <= -58 dB), which matters for the sub-90 Hz measurements next to the
     * 123/155 Hz train sub voices.
     */
    fun windowedMagnitude(
        samples: FloatArray,
        frequencyHz: Double,
        startFraction: Double = 0.0,
        endFraction: Double = 1.0,
    ): Double {
        val start = (samples.size * startFraction).toInt().coerceIn(0, samples.size - 2)
        val end = (samples.size * endFraction).toInt().coerceIn(start + 2, samples.size)
        val count = end - start
        val denominator = (count - 1).toDouble().coerceAtLeast(1.0)
        var real = 0.0
        var imaginary = 0.0
        var windowSum = 0.0
        for (offset in 0 until count) {
            val window =
                0.42 - 0.5 * cos(2.0 * PI * offset / denominator) +
                    0.08 * cos(4.0 * PI * offset / denominator)
            val angle = 2.0 * PI * frequencyHz * (start + offset) / SAMPLE_RATE
            val sample = samples[start + offset]
            real += sample * window * cos(angle)
            imaginary -= sample * window * sin(angle)
            windowSum += window
        }
        return 2.0 * kotlin.math.hypot(real, imaginary) / windowSum.coerceAtLeast(1e-12)
    }

    /** Leakage-controlled band energy: squared Blackman-windowed probes on a 1/6-octave grid. */
    fun bandEnergyWindowed(
        samples: FloatArray,
        lowHz: Double,
        highHz: Double,
        startFraction: Double = 0.0,
        endFraction: Double = 1.0,
    ): Double {
        var energy = 0.0
        for (frequency in sixthOctaveGrid(lowHz, highHz)) {
            val magnitude = windowedMagnitude(samples, frequency, startFraction, endFraction)
            energy += magnitude * magnitude
        }
        return energy
    }

    /**
     * First sample where a [windowMs] sliding-window RMS reaches [fraction] of the steady-state
     * RMS (windowed over the final third of the capture). Returns `samples.size / 2` frames.
     */
    fun attackIndexFrames(
        samples: FloatArray,
        fraction: Double = 0.9,
        windowMs: Double = 2.0,
    ): Int {
        val frames = samples.size / 2
        val window = (SAMPLE_RATE * windowMs / 1_000.0).toInt().coerceAtLeast(1)
        val steadyFrom = frames * 2 / 3
        val steady = windowRms(samples, steadyFrom, steadyFrom + window * 4)
        val threshold = fraction * steady
        if (steady <= 0.0) return frames
        var frame = 0
        while (frame + window <= frames) {
            if (windowRms(samples, frame, frame + window) >= threshold) return frame
            frame += window / 2
        }
        return frames
    }

    /** RMS of one channel-symmetric window over interleaved stereo [samples]. */
    fun windowRms(
        samples: FloatArray,
        fromFrame: Int,
        toFrame: Int,
    ): Double {
        if (toFrame <= fromFrame) return 0.0
        var sum = 0.0
        var count = 0
        for (frame in fromFrame until toFrame) {
            val left = samples[2 * frame].toDouble()
            val right = samples[2 * frame + 1].toDouble()
            sum += 0.5 * (left * left + right * right)
            count++
        }
        return if (count > 0) sqrt(sum / count) else 0.0
    }

    /** Folded impulse response of the scene stage alone (SceneEngineTest's harness style). */
    fun sceneIrFold(space: AcousticSpace): FloatArray {
        val scene = SceneEngine(SAMPLE_RATE)
        scene.hardSetSpace(space)
        val frames = SAMPLE_RATE
        val stereo = FloatArray(2 * frames)
        stereo[0] = 1f
        stereo[1] = 1f
        scene.process(stereo, frames)
        return DspMeasurement.monoFold(stereo, frames)
    }

    /** Steady-state magnitude response of a folded impulse response at [frequencyHz]. */
    fun irResponse(
        impulse: FloatArray,
        frequencyHz: Double,
    ): Double = DspMeasurement.magnitude(impulse, frequencyHz, startFraction = 0.0)

    /**
     * Folded mono notch depth of the scene stage over 200 Hz-2 kHz, 1/6-octave sweep,
     * measured against the passband median (02 §5 acceptance row 2).
     */
    fun monoNotchDepthDb(space: AcousticSpace): Double {
        val impulse = sceneIrFold(space)
        val responses =
            sixthOctaveGrid(200.0, 2_000.0).map {
                20.0 * log10(irResponse(impulse, it).coerceAtLeast(1e-12))
            }
        val sorted = responses.sorted()
        val median = sorted[sorted.size / 2]
        return median - responses.min()
    }

    /**
     * Band-averaged variant of [monoNotchDepthDb]: each 1/6-octave step integrates |H(f)|^2
     * over seven sub-probes inside the band, matching acoustic 1/6-octave *band* analysis
     * (a fine comb such as the 21.4 Hz tunnel flutter is averaged inside its band instead of
     * being point-sampled). Notch depth is the band-median minus the weakest band.
     */
    fun monoBandNotchDepthDb(space: AcousticSpace): Double {
        val impulse = sceneIrFold(space)
        val bandEnergies =
            sixthOctaveGrid(200.0, 2_000.0).map { centerHz ->
                val ratio = 2.0.pow(1.0 / 12.0)
                var energy = 0.0
                for (sub in -3..3) {
                    val frequency = centerHz * ratio.pow(sub / 3.0)
                    val response = irResponse(impulse, frequency).coerceAtLeast(1e-12)
                    energy += response * response
                }
                10.0 * log10(energy)
            }
        val sorted = bandEnergies.sorted()
        val median = sorted[sorted.size / 2]
        return median - bandEnergies.min()
    }

    fun db10(ratio: Double): Double = 10.0 * log10(ratio.coerceAtLeast(1e-30))
}
