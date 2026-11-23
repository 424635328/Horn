package com.industrial.horn.audio

import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Throwaway calibration probe: prints the measured values the objective acoustic QA suites
 * assert on, so thresholds can be frozen against real engine behaviour. Deleted after use.
 */
class QaCalibrationProbeTest {
    @Test
    fun probeExpressionSpectrum() {
        for (type in listOf(HornType.CAR, HornType.TRAIN)) {
            val neutral =
                DspMeasurement.monoFold(
                    QaMeasurement.renderPipeline(type, AcousticSpace.STREET, expression = 0f, warmupBlocks = 60, measureBlocks = 180),
                    180 * QaMeasurement.BLOCK_FRAMES,
                )
            val bright =
                DspMeasurement.monoFold(
                    QaMeasurement.renderPipeline(type, AcousticSpace.STREET, expression = 1f, warmupBlocks = 60, measureBlocks = 180),
                    180 * QaMeasurement.BLOCK_FRAMES,
                )
            val c0 = DspMeasurement.spectralCentroid(neutral)
            val c1 = DspMeasurement.spectralCentroid(bright)
            val h0 = QaMeasurement.bandRatioDb(neutral, 2_000.0, 8_000.0, 200.0, 8_000.0)
            val h1 = QaMeasurement.bandRatioDb(bright, 2_000.0, 8_000.0, 200.0, 8_000.0)
            println("PROBE_EXPR $type centroid0=$c0 centroid1=$c1 ratio=${c1 / c0} high0=$h0 high1=$h1")
        }

        val car0 =
            DspMeasurement.monoFold(
                QaMeasurement.renderPipeline(HornType.CAR, AcousticSpace.STREET, expression = 0f, warmupBlocks = 60, measureBlocks = 180),
                180 * QaMeasurement.BLOCK_FRAMES,
            )
        val car1 =
            DspMeasurement.monoFold(
                QaMeasurement.renderPipeline(HornType.CAR, AcousticSpace.STREET, expression = 1f, warmupBlocks = 60, measureBlocks = 180),
                180 * QaMeasurement.BLOCK_FRAMES,
            )
        val thd0 = thd(car0, scale = 1.0)
        val thd1 = thd(car1, scale = 1.08)
        println("PROBE_EXPR carThd0=$thd0 carThd1=$thd1 ratio=${thd1 / thd0}")
    }

    @Test
    fun probeAttack() {
        for (type in listOf(HornType.TRAIN, HornType.EBIKE)) {
            val voice = QaMeasurement.fold(QaMeasurement.renderVoiceFromNoteOn(type, 24_000))
            val voiceIndex = QaMeasurement.attackIndexFrames(interleavedOf(voice), 0.9)
            val pipelineIndex = pipelineAttackIndex(type)
            println("PROBE_ATTACK $type voice90=$voiceIndex pipeline90=$pipelineIndex")
        }
    }

    @Test
    fun probeEbikeFormant() {
        val fold =
            QaMeasurement.fold(
                QaMeasurement.renderPipeline(HornType.EBIKE, AcousticSpace.STREET, warmupBlocks = 60, measureBlocks = 240),
                240 * QaMeasurement.BLOCK_FRAMES,
            )
        val a = DspMeasurement.magnitude(fold, 2_740.0)
        val b = DspMeasurement.magnitude(fold, 2_785.0)
        val formant = a * a + b * b
        val control = QaMeasurement.bandEnergy(fold, 3_500.0, 4_000.0)
        val below = QaMeasurement.bandEnergy(fold, 2_100.0, 2_500.0)
        println(
            "PROBE_FORMANT a=$a b=$b formant=$formant control35-40=$control below21-25=$below " +
                "ratioControl=${formant / control} ratioBelow=${formant / below}",
        )
    }

    @Test
    fun probeSceneCorrelation() {
        for (type in listOf(HornType.CAR, HornType.TRAIN)) {
            for (space in AcousticSpace.entries) {
                val stereo =
                    QaMeasurement.renderPipeline(type, space, warmupBlocks = 80, measureBlocks = 240, framesPerBlock = 128)
                var worst = -2f
                var sum = 0.0
                var count = 0
                var above = 0
                var sideEnergy = 0.0
                for (block in 0 until 240) {
                    val left = FloatArray(128)
                    val right = FloatArray(128)
                    for (frame in 0 until 128) {
                        left[frame] = stereo[(block * 128 + frame) * 2]
                        right[frame] = stereo[(block * 128 + frame) * 2 + 1]
                    }
                    val energy = QaMeasurement.rmsOf(left, 0, 128) + QaMeasurement.rmsOf(right, 0, 128)
                    if (energy < 1e-6) continue
                    val corr = AudioTelemetryBuffer.stereoCorrelation(left, right, 128)
                    worst = maxOf(worst, corr)
                    sum += corr
                    count++
                    if (corr >= 0.995f) above++
                    for (frame in 0 until 128) {
                        val diff = left[frame] - right[frame]
                        sideEnergy += diff * diff
                    }
                }
                println("PROBE_CORR $type $space worst=$worst mean=${sum / count} above0995=$above/$count sideEnergy=$sideEnergy")
            }
        }
    }

    @Test
    fun probeFoldLoss() {
        for (space in AcousticSpace.entries) {
            for (mode in OutputMode.entries) {
                val stereo =
                    QaMeasurement.renderPipeline(HornType.CAR, space, mode = mode, warmupBlocks = 80, measureBlocks = 240)
                val frames = 240 * QaMeasurement.BLOCK_FRAMES
                val fold = QaMeasurement.fold(stereo, frames)
                var stereoEnergy = 0.0
                var foldEnergy = 0.0
                for (index in 0 until frames) {
                    val l = stereo[2 * index].toDouble()
                    val r = stereo[2 * index + 1].toDouble()
                    stereoEnergy += 0.5 * (l * l + r * r)
                    foldEnergy += fold[index].toDouble() * fold[index]
                }
                val lossDb = 10.0 * log10(stereoEnergy / foldEnergy)
                val foldRms = sqrt(foldEnergy / frames)
                val lRms = QaMeasurement.rmsOf(stereo, 0, frames)
                println("PROBE_FOLD $space $mode lossDb=$lossDb foldRms=$foldRms stereoRms=$lRms ratio=${foldRms / lRms}")
            }
        }
    }

    @Test
    fun probeNotch() {
        for (space in AcousticSpace.entries) {
            val notch = QaMeasurement.monoNotchDepthDb(space)
            println("PROBE_NOTCH $space notchDb=$notch")
        }
    }

    @Test
    fun probeTail() {
        val street = QaMeasurement.sceneIrFold(AcousticSpace.STREET)
        val tunnel = QaMeasurement.sceneIrFold(AcousticSpace.TUNNEL)
        val open = QaMeasurement.sceneIrFold(AcousticSpace.OPEN_ROAD)
        val garage = QaMeasurement.sceneIrFold(AcousticSpace.GARAGE)

        val streetLate = QaMeasurement.rmsOf(street, from = QaMeasurement.SAMPLE_RATE / 4, to = QaMeasurement.SAMPLE_RATE * 3 / 5)
        val tunnelLate = QaMeasurement.rmsOf(tunnel, from = QaMeasurement.SAMPLE_RATE / 4, to = QaMeasurement.SAMPLE_RATE * 3 / 5)
        println("PROBE_TAIL streetLate=$streetLate tunnelLate=$tunnelLate ratio=${tunnelLate / streetLate}")

        for ((name, ir) in listOf("street" to street, "tunnel" to tunnel, "open" to open, "garage" to garage)) {
            val tail = ir.copyOfRange(QaMeasurement.SAMPLE_RATE / 40, ir.size)
            val high = QaMeasurement.bandEnergy(tail, 2_000.0, 8_000.0, 0.0)
            val full = QaMeasurement.bandEnergy(tail, 50.0, 16_000.0, 0.0)
            println("PROBE_TAIL_HF $name high2_8=$high full=$full ratio=${high / full}")
        }
    }

    @Test
    fun probeVbs() {
        for (type in listOf(HornType.TRAIN, HornType.EBIKE)) {
            for (mode in OutputMode.entries) {
                val fold =
                    QaMeasurement.fold(
                        QaMeasurement.renderPipeline(type, AcousticSpace.STREET, mode = mode, warmupBlocks = 80, measureBlocks = 240),
                        240 * QaMeasurement.BLOCK_FRAMES,
                    )
                val mid = QaMeasurement.bandEnergy(fold, 250.0, 900.0)
                val low = QaMeasurement.bandEnergy(fold, 30.0, 85.0)
                val low90 = QaMeasurement.bandEnergy(fold, 20.0, 90.0)
                println("PROBE_VBS $type $mode mid250_900=$mid low30_85=$low low20_90=$low90")
            }
        }
    }

    @Test
    fun probePeakAndTiming() {
        var maxAbs = 0f
        var nonFinite = 0
        for (type in HornType.entries) {
            val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, QaMeasurement.BLOCK_FRAMES, type)
            renderer.start()
            for (space in AcousticSpace.entries) {
                renderer.setAcousticSpace(space)
                repeat(20) { renderer.renderBlock() }
                repeat(80) {
                    val frames = renderer.renderBlock()
                    for (index in 0 until 2 * frames) {
                        val sample = renderer.outputBuffer[index]
                        if (!sample.isFinite()) nonFinite++
                        maxAbs = maxOf(maxAbs, abs(sample))
                    }
                }
            }
            renderer.panic()
        }
        println("PROBE_PEAK maxAbs=$maxAbs nonFinite=$nonFinite")

        repeat(256) { timedBlock() }
        var totalNanos = 0L
        var blocks = 0
        for (type in HornType.entries) {
            val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, 128, type)
            renderer.start()
            for (space in AcousticSpace.entries) {
                renderer.setAcousticSpace(space)
                repeat(32) {
                    val started = System.nanoTime()
                    renderer.renderBlock()
                    totalNanos += System.nanoTime() - started
                    blocks++
                }
            }
            renderer.panic()
        }
        println("PROBE_TIMING meanMillis=${totalNanos / 1e6 / blocks} blocks=$blocks")

        val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, 128, HornType.CAR)
        renderer.start()
        repeat(500) { renderer.renderBlock() }
        var spaceIndex = 0
        val allocated =
            measureAllocatedBytes {
                repeat(2_400) {
                    if (it % 8 == 0) {
                        renderer.setAcousticSpace(AcousticSpace.entries[spaceIndex % AcousticSpace.entries.size])
                        spaceIndex++
                    }
                    renderer.renderBlock()
                }
            }
        println("PROBE_GC allocatedBytes=$allocated")
    }

    private fun timedBlock() {
        val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, 128, HornType.CAR)
        renderer.start()
        renderer.renderBlock()
        renderer.panic()
    }

    private fun pipelineAttackIndex(type: HornType): Int {
        val renderer = HornRenderer(QaMeasurement.SAMPLE_RATE, 128, type)
        renderer.start()
        repeat(40) { renderer.renderBlock() }
        renderer.stop()
        while (renderer.renderBlock() > 0) {
            // drain the release + tail so the second note-on starts from silence
        }
        renderer.start()
        val frames = 12_000
        val stereo = FloatArray(2 * frames)
        var written = 0
        while (written < frames) {
            val count = renderer.renderBlock()
            for (index in 0 until 2 * count) {
                if (written * 2 + index < stereo.size) stereo[written * 2 + index] = renderer.outputBuffer[index]
            }
            written += count
        }
        renderer.panic()
        return QaMeasurement.attackIndexFrames(stereo, 0.9)
    }

    private fun interleavedOf(mono: FloatArray): FloatArray {
        val stereo = FloatArray(2 * mono.size)
        for (index in mono.indices) {
            stereo[2 * index] = mono[index]
            stereo[2 * index + 1] = mono[index]
        }
        return stereo
    }

    private fun thd(
        samples: FloatArray,
        scale: Double,
    ): Double {
        var fundamental = 0.0
        for (frequency in doubleArrayOf(400.0, 500.0)) {
            val magnitude = DspMeasurement.magnitude(samples, frequency * scale)
            fundamental += magnitude * magnitude
        }
        var harmonics = 0.0
        for (frequency in doubleArrayOf(800.0, 1_000.0, 1_200.0, 1_500.0, 1_600.0, 2_000.0, 2_400.0, 3_000.0, 3_200.0, 4_000.0)) {
            val magnitude = DspMeasurement.magnitude(samples, frequency * scale)
            harmonics += magnitude * magnitude
        }
        return sqrt(harmonics / fundamental.coerceAtLeast(1e-30))
    }

    private fun measureAllocatedBytes(block: () -> Unit): Long {
        val managementFactory = Class.forName("java.lang.management.ManagementFactory")
        val bean = managementFactory.getMethod("getThreadMXBean").invoke(null)
        val allocationBean = Class.forName("com.sun.management.ThreadMXBean")
        val allocatedBytes = allocationBean.getMethod("getCurrentThreadAllocatedBytes")
        val before = allocatedBytes.invoke(bean) as Long
        block()
        val after = allocatedBytes.invoke(bean) as Long
        return after - before
    }
}
