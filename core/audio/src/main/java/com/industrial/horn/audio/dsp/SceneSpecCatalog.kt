package com.industrial.horn.audio.dsp

import com.industrial.horn.audio.AcousticSpace
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Immutable acoustic description of one [AcousticSpace], plus the catalog that derives it.
 *
 * docs/spec/research/02 §4 (the four scene tables) is the single source of truth for every
 * parameter below. Values that the tables fix literally — FDN prime delay tables A/B/C, g, d,
 * width W, wetMix, pre-delay, tail length — are frozen constants. Values the tables describe
 * geometrically (ISM image sources, ground/tunnel combs, atmospheric absorption) are derived
 * here at construction time from the documented formulas so both Kotlin and C++ can mirror the
 * same math instead of hand-transcribing approximations.
 */
internal class SceneSpec(
    /** Image sources with exactly one non-zero index, |n| <= [ismOrder] (02 §3.1). */
    val ismCount: Int,
    val ismDelaySamples: DoubleArray,
    val ismItdSamples: IntArray,
    val ismGainLeft: DoubleArray,
    val ismGainRight: DoubleArray,
    val ismLpAlpha: Double,
    /** Feed-forward comb: ground micro-comb, tunnel flutter or garage slab colouring (02 §3.4). */
    val combDelaySamples: Int,
    val combRho: Double,
    val combLpAlpha: Double,
    /** Orthogonal FDN (02 §3.2): prime delay table, active line count, g, damping d, width W. */
    val fdnDelays: IntArray,
    val fdnActiveLines: Int,
    val fdnGain: Double,
    val fdnDamping: Double,
    val fdnWidth: Double,
    /** ISO 9613-1 two-shelf fit: high-frequency gains of the 2 kHz / 8 kHz shelves (02 §3.3). */
    val atmosphereG1: Double,
    val atmosphereG2: Double,
    val wetMix: Double,
    val preDelaySamples: Int,
    val tailMillis: Int,
)

/**
 * Frozen prime delay tables of 02 §3.2 (samples at 48 kHz; pairwise coprime by construction).
 * Table A: tunnel (M=8, mean T 45.35 ms). Table B: garage (M=8, mean T 28.88 ms).
 * Table C: street / open highway (M=4, mean T 31.24 ms).
 */
internal object SceneSpecCatalog {
    private val FDN_TABLE_A = intArrayOf(1901, 1973, 2039, 2099, 2161, 2221, 2281, 2341)
    private val FDN_TABLE_B = intArrayOf(1103, 1181, 1259, 1361, 1433, 1511, 1583, 1657)
    private val FDN_TABLE_C = intArrayOf(1301, 1433, 1567, 1697, 0, 0, 0, 0)

    /** Longest delay each FDN line ever needs; the shared ring bank is sized from this. */
    val FDN_RING_SIZES =
        IntArray(8) { line ->
            maxOf(FDN_TABLE_A[line], FDN_TABLE_B[line], FDN_TABLE_C[line])
        }

    private const val SOUND_SPEED = 343.0

    /** Frozen tunnel flutter period of 02 §4.2: 2W/c = 46.6 ms -> 2237 samples round-half-up. */
    private const val TUNNEL_FLUTTER_PERIOD_MILLIS = 46.6

    /** ISO 9613-1 shelf corners of 02 §3.3: the 2 kHz / 8 kHz fitting points. */
    private const val SHELF1_FC = 2_000.0
    private const val SHELF2_FC = 8_000.0

    /** Max ITD of 0.66 ms = 31.68 samples at 48 kHz (02 §3.1, Woodworth 90 deg cap). */
    private const val MAX_ITD_SAMPLES = 31.68

    private const val MAX_REFLECTION_GAIN = 0.316
    private const val MAX_REFLECTION_SUM = 0.333

    /** History ring covers the longest sparse echo (open highway, 184 ms) plus margin. */
    const val HISTORY_SAMPLES = 11_520

    /** Pre-delay ring covers the longest scene pre-delay (open highway, 34 ms) plus margin. */
    const val PRE_DELAY_SAMPLES = 2_048

    /** Comb ring covers the longest colouring delay (tunnel flutter, 46.6 ms). */
    const val COMB_SAMPLES = 2_304

    private val specs = Array(AcousticSpace.entries.size) { build(AcousticSpace.entries[it]) }

    fun spec(space: AcousticSpace): SceneSpec = specs[space.ordinal]

    private fun build(space: AcousticSpace): SceneSpec =
        when (space) {
            AcousticSpace.STREET -> {
                roomScene(
                    roomLx = 12.0,
                    roomLy = 40.0,
                    roomLz = 15.0,
                    listenerX = 6.5,
                    listenerY = 20.0,
                    listenerZ = 1.2,
                    sourceX = 6.5,
                    sourceY = 12.0,
                    sourceZ = 0.75,
                    rhoNegX = 0.62,
                    rhoPosX = 0.70,
                    rhoNegY = 0.65,
                    rhoPosY = 0.65,
                    rhoNegZ = 0.40,
                    rhoPosZ = 0.70,
                    ismOrder = 2,
                    ismLpHz = 3_200.0,
                    combDelaySamples = parallelPlateDelay(0.75),
                    combRho = 0.40,
                    combLpHz = 3_200.0,
                    fdnTable = FDN_TABLE_C,
                    fdnActiveLines = 4,
                    fdnGain = 0.567,
                    fdnDamping = 0.204,
                    fdnWidth = 0.5,
                    atmosphereDistanceM = 35.0,
                    wetMix = 0.10,
                    preDelayMillis = 8.0,
                    tailMillis = 60,
                )
            }

            AcousticSpace.TUNNEL -> {
                roomScene(
                    roomLx = 8.0,
                    roomLy = 40.0,
                    roomLz = 5.5,
                    listenerX = 3.4,
                    listenerY = 20.0,
                    listenerZ = 1.2,
                    sourceX = 3.4,
                    sourceY = 10.0,
                    sourceZ = 0.75,
                    rhoNegX = 0.90,
                    rhoPosX = 0.90,
                    rhoNegY = 0.90,
                    rhoPosY = 0.90,
                    rhoNegZ = 0.88,
                    rhoPosZ = 0.94,
                    ismOrder = 2,
                    ismLpHz = 5_000.0,
                    // Side-wall flutter: comb period 2W/c = 46.6 ms (21.4 Hz spacing), 02 §4.2.
                    combDelaySamples = tunnelFlutterDelay(),
                    combRho = 0.30,
                    combLpHz = 5_000.0,
                    fdnTable = FDN_TABLE_A,
                    fdnActiveLines = 8,
                    fdnGain = 0.914,
                    fdnDamping = 0.262,
                    fdnWidth = 0.7,
                    atmosphereDistanceM = 70.0,
                    wetMix = 0.38,
                    preDelayMillis = 18.0,
                    tailMillis = 1_200,
                )
            }

            AcousticSpace.OPEN_ROAD -> {
                openHighway()
            }

            AcousticSpace.GARAGE -> {
                roomScene(
                    roomLx = 12.6,
                    roomLy = 20.0,
                    roomLz = 2.4,
                    listenerX = 6.3,
                    listenerY = 10.0,
                    listenerZ = 1.2,
                    sourceX = 6.3,
                    sourceY = 4.0,
                    sourceZ = 0.75,
                    rhoNegX = 0.90,
                    rhoPosX = 0.90,
                    rhoNegY = 0.90,
                    rhoPosY = 0.90,
                    rhoNegZ = 0.88,
                    rhoPosZ = 0.92,
                    ismOrder = 2,
                    ismLpHz = 6_000.0,
                    // Floor/ceiling slab colouring: c/(2h) = 66-78 Hz modes, 02 §4.4.
                    combDelaySamples = parallelPlateDelay(2.4),
                    combRho = 0.50,
                    combLpHz = 6_000.0,
                    fdnTable = FDN_TABLE_B,
                    fdnActiveLines = 8,
                    fdnGain = 0.853,
                    fdnDamping = 0.141,
                    fdnWidth = 0.6,
                    atmosphereDistanceM = 25.0,
                    wetMix = 0.30,
                    preDelayMillis = 12.0,
                    tailMillis = 800,
                )
            }
        }

    /**
     * Open highway keeps no room: two sparse far echoes (ground + distant hill line, 80-220 ms,
     * -18..-30 dB) replace the rectangular image sources (02 §4.3), then the shared ground
     * micro-comb (h = 0.75 m) and the shortest FDN tail of all scenes.
     */
    private fun openHighway(): SceneSpec {
        val delays = doubleArrayOf(96.0e-3 * DspConstants.SAMPLE_RATE, 184.0e-3 * DspConstants.SAMPLE_RATE)
        val itd = intArrayOf(0, 0)
        val gainL = doubleArrayOf(dbToLinear(-21.0) * 1.16, dbToLinear(-28.0) * 0.84)
        val gainR = doubleArrayOf(dbToLinear(-21.0) * 0.84, dbToLinear(-28.0) * 1.16)
        val shelves = fitShelfPair(150.0)
        return SceneSpec(
            ismCount = 2,
            ismDelaySamples = delays,
            ismItdSamples = itd,
            ismGainLeft = gainL,
            ismGainRight = gainR,
            ismLpAlpha = lowPassPole(2_500.0),
            combDelaySamples = parallelPlateDelay(0.75),
            combRho = 0.35,
            combLpAlpha = lowPassPole(3_000.0),
            fdnDelays = FDN_TABLE_C,
            fdnActiveLines = 4,
            fdnGain = 0.375,
            fdnDamping = 0.225,
            fdnWidth = 0.4,
            atmosphereG1 = shelves[0],
            atmosphereG2 = shelves[1],
            wetMix = 0.22,
            preDelaySamples = (34.0e-3 * DspConstants.SAMPLE_RATE).toInt(),
            tailMillis = 400,
        )
    }

    /**
     * Rectangular-room image sources (Allen-Berkley, 02 §3.1): only image sources with exactly
     * one non-zero index and |n| <= N are kept (6N sources). Each source contributes
     * `g = rho^k * (1 m / R)` and is binauralised with a Woodworth ITD (<= 0.66 ms) plus a mild
     * ILD; gains are clamped to the mono-fold budget of 02 §5 C2 afterwards.
     */
    private fun roomScene(
        roomLx: Double,
        roomLy: Double,
        roomLz: Double,
        listenerX: Double,
        listenerY: Double,
        listenerZ: Double,
        sourceX: Double,
        sourceY: Double,
        sourceZ: Double,
        rhoNegX: Double,
        rhoPosX: Double,
        rhoNegY: Double,
        rhoPosY: Double,
        rhoNegZ: Double,
        rhoPosZ: Double,
        ismOrder: Int,
        ismLpHz: Double,
        combDelaySamples: Int,
        combRho: Double,
        combLpHz: Double,
        fdnTable: IntArray,
        fdnActiveLines: Int,
        fdnGain: Double,
        fdnDamping: Double,
        fdnWidth: Double,
        atmosphereDistanceM: Double,
        wetMix: Double,
        preDelayMillis: Double,
        tailMillis: Int,
    ): SceneSpec {
        val maxSources = 6 * ismOrder
        val delays = DoubleArray(maxSources)
        val itd = IntArray(maxSources)
        val gainL = DoubleArray(maxSources)
        val gainR = DoubleArray(maxSources)
        val lengths = doubleArrayOf(roomLx, roomLy, roomLz)
        val sources = doubleArrayOf(sourceX, sourceY, sourceZ)
        var index = 0
        for (axis in 0..2) {
            for (n in -ismOrder..ismOrder) {
                if (n == 0) continue
                val image = DoubleArray(3)
                image[0] = sourceX
                image[1] = sourceY
                image[2] = sourceZ
                image[axis] = 2.0 * n * lengths[axis] + if (n % 2 == 0) sources[axis] else -sources[axis]
                val dx = image[0] - listenerX
                val dy = image[1] - listenerY
                val dz = image[2] - listenerZ
                val distance = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.5)
                val rho =
                    when (axis) {
                        0 -> if (image[0] < listenerX) rhoNegX else rhoPosX
                        1 -> if (image[1] < listenerY) rhoNegY else rhoPosY
                        else -> if (image[2] < listenerZ) rhoNegZ else rhoPosZ
                    }
                val order = if (n < 0) -n else n
                val gain = rho.pow(order) / distance

                // Binauralisation: azimuth from the horizontal offset, Woodworth ITD (02 §3.1).
                val azimuth = atan2(dx, if (dy >= 0.0) dy else -dy)
                val clampedAzimuth = azimuth.coerceIn(-PI / 2.0, PI / 2.0)
                val itdSeconds =
                    (0.0875 / SOUND_SPEED) * (clampedAzimuth + sin(clampedAzimuth))
                val itdSamples = (itdSeconds * DspConstants.SAMPLE_RATE).toInt().coerceIn(0, MAX_ITD_SAMPLES.toInt())
                val ildDb = 6.0 * kotlin.math.abs(sin(clampedAzimuth))
                val nearGain = gain
                val farGain = gain * dbToLinear(-ildDb)

                val toLeft = dx < 0.0
                delays[index] = distance / SOUND_SPEED * DspConstants.SAMPLE_RATE
                itd[index] = itdSamples
                gainL[index] = if (toLeft) nearGain else farGain
                gainR[index] = if (toLeft) farGain else nearGain
                index++
            }
        }

        // 02 §5 C2 budget: each reflection <= -10 dB, coherent sum <= 0.333 of the direct sound.
        var maxGain = 0.0
        var sum = 0.0
        for (i in 0 until maxSources) {
            val peak = maxOf(gainL[i], gainR[i])
            maxGain = maxOf(maxGain, peak)
            sum += peak
        }
        var scale = 1.0
        if (maxGain > MAX_REFLECTION_GAIN) scale = MAX_REFLECTION_GAIN / maxGain
        if (sum * scale > MAX_REFLECTION_SUM) scale = MAX_REFLECTION_SUM / sum
        if (scale != 1.0) {
            for (i in 0 until maxSources) {
                gainL[i] *= scale
                gainR[i] *= scale
            }
        }

        val shelves = fitShelfPair(atmosphereDistanceM)

        return SceneSpec(
            ismCount = maxSources,
            ismDelaySamples = delays,
            ismItdSamples = itd,
            ismGainLeft = gainL,
            ismGainRight = gainR,
            ismLpAlpha = lowPassPole(ismLpHz),
            combDelaySamples = combDelaySamples,
            combRho = combRho,
            combLpAlpha = lowPassPole(combLpHz),
            fdnDelays = fdnTable,
            fdnActiveLines = fdnActiveLines,
            fdnGain = fdnGain,
            fdnDamping = fdnDamping,
            fdnWidth = fdnWidth,
            atmosphereG1 = shelves[0],
            atmosphereG2 = shelves[1],
            wetMix = wetMix,
            preDelaySamples = (preDelayMillis * 1e-3 * DspConstants.SAMPLE_RATE).toInt(),
            tailMillis = tailMillis,
        )
    }

    /**
     * Comb spacing of a parallel-plate path (ground/slab): delay = 2h/c in samples. Rounding is
     * round-half-up per 02 §6.5 (NOT `toInt()` truncation) so the Kotlin and C++ delay literals
     * match sample-by-sample: 2*0.75/343*48000 = 209.9 -> 210, 2*2.4/343*48000 = 671.7 -> 672.
     */
    private fun parallelPlateDelay(metres: Double): Int =
        roundHalfUp(2.0 * metres / SOUND_SPEED * DspConstants.SAMPLE_RATE)
            .coerceIn(1, COMB_SAMPLES - 1)

    /**
     * Tunnel side-wall flutter comb: the frozen period of 02 §4.2 is 2W/c = 46.6 ms, i.e.
     * 2236.8 -> 2237 samples round-half-up, identical to `dsp_constants.h` SCENE_TUNNEL.
     * (The exact 2*8/343*48000 evaluates to 2239.1; 02 §4.2's rounded 46.6 ms figure is the
     * authoritative frozen value for both ends.)
     */
    private fun tunnelFlutterDelay(): Int =
        roundHalfUp(TUNNEL_FLUTTER_PERIOD_MILLIS * 1e-3 * DspConstants.SAMPLE_RATE)
            .coerceIn(1, COMB_SAMPLES - 1)

    /** Round-half-up (02 §6.5): `toInt()` truncation is forbidden for frozen delay literals. */
    private fun roundHalfUp(exactSamples: Double): Int = Math.floor(exactSamples + 0.5).toInt()

    /** ISO 9613-1 attenuation [dB] over the scene distance at 20 degC / 50 % RH. */
    private fun atmosphereDb(
        frequencyHz: Double,
        distanceM: Double,
    ): Double = Iso9613Absorption.absorptionDbPerKm(frequencyHz) * distanceM / 1_000.0

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Two-shelf fit of 02 §3.3: stage 1 (corner 2 kHz) realises the 2 kHz attenuation and stage 2
     * (corner 8 kHz) the 8 kHz attenuation. G1/G2 solve the coupled monotone 2x2 system against
     * the *discrete* shelf response that the scene engine implements, so no approximation is
     * hand-copied into the runtime. Each scene keeps one representative propagation distance from
     * its 02 §4 distance band; scene switches glide the gains with a one-pole smoother.
     */
    private fun fitShelfPair(distanceM: Double): DoubleArray {
        val target2 = dbToLinear(-atmosphereDb(SHELF1_FC, distanceM))
        val target8 = dbToLinear(-atmosphereDb(SHELF2_FC, distanceM))
        var low = 1e-3
        var high = 1.0
        var g2 = 1.0
        repeat(40) {
            val g1 = 0.5 * (low + high)
            g2 = solveSecondShelf(g1, target8)
            val reached = shelfMagnitude(g1, SHELF1_FC, 2_000.0) * shelfMagnitude(g2, SHELF2_FC, 2_000.0)
            if (reached < target2) low = g1 else high = g1
        }
        val g1 = 0.5 * (low + high)
        return doubleArrayOf(g1, solveSecondShelf(g1, target8))
    }

    private fun solveSecondShelf(
        g1: Double,
        target8: Double,
    ): Double {
        var low = 1e-4
        var high = 1.0
        repeat(40) {
            val g2 = 0.5 * (low + high)
            val reached = shelfMagnitude(g1, SHELF1_FC, 8_000.0) * shelfMagnitude(g2, SHELF2_FC, 8_000.0)
            if (reached < target8) low = g2 else high = g2
        }
        return 0.5 * (low + high)
    }

    /** Magnitude of the implemented first-order bilinear shelf (02 §3.3 coefficient recipe). */
    private fun shelfMagnitude(
        gain: Double,
        cornerHz: Double,
        frequencyHz: Double,
    ): Double {
        val fs = DspConstants.SAMPLE_RATE.toDouble()
        val w0p = 2.0 * fs * tan(PI * cornerHz / fs)
        val k = 2.0 * fs
        val b0 = (gain * k + w0p) / (k + w0p)
        val b1 = (w0p - gain * k) / (k + w0p)
        val a1 = (w0p - k) / (k + w0p)
        val w = 2.0 * PI * frequencyHz / fs
        val numRe = b0 + b1 * cos(w)
        val numIm = -b1 * sin(w)
        val denRe = 1.0 + a1 * cos(w)
        val denIm = -a1 * sin(w)
        return hypot(numRe, numIm) / hypot(denRe, denIm)
    }

    /**
     * Pole of the first-order low-pass of 02 §3.1 (`y[m] = (1-a)*x[m] + a*y[m-1]`,
     * `a = exp(-2*pi*fc/fs)`), used for reflection high-frequency absorption and comb colouring.
     */
    internal fun lowPassPole(cutoffHz: Double): Double = exp(-2.0 * PI * cutoffHz / DspConstants.SAMPLE_RATE).coerceIn(1e-4, 0.9999)
}
