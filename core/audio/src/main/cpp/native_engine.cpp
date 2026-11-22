#include "native_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#if defined(__x86_64__)
#include <immintrin.h>
#endif

namespace {
constexpr int kTypeCar = 0;
constexpr int kTypeTrain = 1;
constexpr int kTypeEbike = 2;
constexpr int kTypeMotorcycle = 3;
constexpr int kTypeCustom = 4;

float smoothStep(float value) {
    const float clamped = std::clamp(value, 0.0f, 1.0f);
    return clamped * clamped * (3.0f - 2.0f * clamped);
}

int32_t floatBits(float value) {
    int32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

float bitsFloat(int32_t bits) {
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

float whiteNoise(uint32_t& state) {
    state = state * 1664525u + 1013904223u;
    return static_cast<float>((state >> 8) & 0xFFFFu) / 32768.0f - 1.0f;
}

// 8-point Hadamard butterfly, exact SceneEngine.kt ordering (section 7 i), scaled 1/sqrt(8).
void hadamard8(const float* input, float* output) {
    constexpr float kScale = 0.35355339059327373f;  // 1/sqrt(8)
    const float t0 = input[0] + input[2];
    const float t1 = input[0] - input[2];
    const float t2 = input[1] + input[3];
    const float t3 = input[1] - input[3];
    const float h0 = t0 + t2;
    const float h1 = t1 + t3;
    const float h2 = t0 - t2;
    const float h3 = t1 - t3;
    const float u0 = input[4] + input[6];
    const float u1 = input[4] - input[6];
    const float u2 = input[5] + input[7];
    const float u3 = input[5] - input[7];
    const float k0 = u0 + u2;
    const float k1 = u1 + u3;
    const float k2 = u0 - u2;
    const float k3 = u1 - u3;
    output[0] = (h0 + k0) * kScale;
    output[1] = (h1 + k1) * kScale;
    output[2] = (h2 + k2) * kScale;
    output[3] = (h3 + k3) * kScale;
    output[4] = (h0 - k0) * kScale;
    output[5] = (h1 - k1) * kScale;
    output[6] = (h2 - k2) * kScale;
    output[7] = (h3 - k3) * kScale;
}

// Fractional-delay read from a ring whose head holds the newest sample.
template <size_t N>
float ringRead(const std::array<float, N>& ring, int head, float delay) {
    float q = static_cast<float>(head) - delay;
    int index = static_cast<int>(std::floor(q));
    const float frac = q - static_cast<float>(index);
    index %= static_cast<int>(N);
    if (index < 0) index += static_cast<int>(N);
    int next = index + 1;
    if (next >= static_cast<int>(N)) next = 0;
    return ring[index] * (1.0f - frac) + ring[next] * frac;
}

constexpr double kDoublePi = 3.14159265358979323846;

// ISO 9613-1:1993 Eq.(3)-(5) pure-tone absorption [dB/km], 20 C / 50 % RH / 101.325 kPa.
// Reproduces Table 1 rows to printed precision (validated offline); identical model to
// dsp/Iso9613Absorption.kt. Construction-time only.
double isoAbsorptionDbPerKm(double frequencyHz) {
    const double tKelvin = 293.15;
    const double t01 = 273.16;
    const double log10Psat = -6.8346 * std::pow(t01 / tKelvin, 1.261) + 4.6151;
    const double h = 50.0 * std::pow(10.0, log10Psat);  // molar water-vapour concentration [%]
    const double fRO = 24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h);
    const double fRN = 9.0 + 280.0 * h;  // T = T0 -> the relaxation exponent vanishes
    const double f2 = frequencyHz * frequencyHz;
    const double classical = 1.84e-11;
    const double oxygen = 0.01275 * std::exp(-2239.1 / tKelvin) * fRO / (fRO * fRO + f2);
    const double nitrogen = 0.1068 * std::exp(-3352.0 / tKelvin) * fRN / (fRN * fRN + f2);
    return 8.686 * f2 * (classical + (oxygen + nitrogen)) * 1000.0;
}

// Magnitude of the implemented first-order bilinear shelf (02 section 3.3 recipe).
double shelfMagnitude(double gain, double cornerHz, double frequencyHz) {
    const double fs = dsp::SAMPLE_RATE;
    const double w0p = 2.0 * fs * std::tan(kDoublePi * cornerHz / fs);
    const double k = 2.0 * fs;
    const double b0 = (gain * k + w0p) / (k + w0p);
    const double b1 = (w0p - gain * k) / (k + w0p);
    const double a1 = (w0p - k) / (k + w0p);
    const double w = 2.0 * kDoublePi * frequencyHz / fs;
    return std::hypot(b0 + b1 * std::cos(w), -b1 * std::sin(w)) /
        std::hypot(1.0 + a1 * std::cos(w), -a1 * std::sin(w));
}

double solveSecondShelf(double g1, double target8) {
    double low = 1e-4;
    double high = 1.0;
    for (int i = 0; i < 40; ++i) {
        const double g2 = 0.5 * (low + high);
        const double reached = shelfMagnitude(g1, dsp::AIR_SHELF_F1, 8000.0) *
            shelfMagnitude(g2, dsp::AIR_SHELF_F2, 8000.0);
        if (reached < target8) {
            low = g2;
        } else {
            high = g2;
        }
    }
    return 0.5 * (low + high);
}

// Two-shelf fit of 02 section 3.3, mirroring dsp/SceneSpecCatalog.kt's bisection so both
// ends converge on the same G1/G2. Construction-time only.
void fitShelfPair(double distanceM, float& g1Out, float& g2Out) {
    const double target2 =
        std::pow(10.0, -(isoAbsorptionDbPerKm(2000.0) * distanceM / 1000.0) / 20.0);
    const double target8 =
        std::pow(10.0, -(isoAbsorptionDbPerKm(8000.0) * distanceM / 1000.0) / 20.0);
    double low = 1e-3;
    double high = 1.0;
    for (int i = 0; i < 40; ++i) {
        const double g1 = 0.5 * (low + high);
        const double g2 = solveSecondShelf(g1, target8);
        const double reached = shelfMagnitude(g1, dsp::AIR_SHELF_F1, 2000.0) *
            shelfMagnitude(g2, dsp::AIR_SHELF_F2, 2000.0);
        if (reached < target2) {
            low = g1;
        } else {
            high = g1;
        }
    }
    const double g1 = 0.5 * (low + high);
    g1Out = static_cast<float>(g1);
    g2Out = static_cast<float>(solveSecondShelf(g1, target8));
}

// Flush-to-zero for recursive DSP state (contract section 2). Runs at stream start and
// once on the render thread; never per block.
void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr = 0;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ull << 24) | (1ull << 19);  // FZ | FZ16
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr = 0;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24);  // FZ
    __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(__x86_64__)
    _mm_setcsr(_mm_getcsr() | 0x8040u);  // FTZ | DAZ
#endif
}
}  // namespace

// ---------------------------------------------------------------------------------------
// Envelope (unchanged smooth attack/release semantics, contract C5)
// ---------------------------------------------------------------------------------------
void NativeHornEngine::Envelope::setTimes(float attack, float release) {
    attackMs = std::clamp(attack, 1.0f, 250.0f);
    releaseMs = std::clamp(release, 1.0f, 250.0f);
    attackSamples = std::max(1, static_cast<int>(kSampleRate * attackMs / 1000.0f));
    releaseSamples = std::max(1, static_cast<int>(kSampleRate * releaseMs / 1000.0f));
}

void NativeHornEngine::Envelope::noteOn() {
    if (stage == 1 || stage == 2) return;
    startLevel = level;
    elapsed = 0;
    stage = 1;
}

void NativeHornEngine::Envelope::noteOff() {
    if (stage == 0 || stage == 3) return;
    startLevel = level;
    elapsed = 0;
    stage = 3;
}

void NativeHornEngine::Envelope::reset() {
    stage = 0;
    level = 0.0f;
    startLevel = 0.0f;
    elapsed = 0;
}

float NativeHornEngine::Envelope::next() {
    if (stage == 0) return 0.0f;
    if (stage == 2) return 1.0f;
    if (stage == 1) {
        const float progress = static_cast<float>(elapsed) / attackSamples;
        level = startLevel + (1.0f - startLevel) * smoothStep(progress);
        ++elapsed;
        if (elapsed >= attackSamples) {
            level = 1.0f;
            stage = 2;
        }
        return level;
    }
    const float progress = static_cast<float>(elapsed) / releaseSamples;
    level = startLevel * (1.0f - smoothStep(progress));
    ++elapsed;
    if (elapsed >= releaseSamples) {
        level = 0.0f;
        stage = 0;
    }
    return level;
}

// ---------------------------------------------------------------------------------------
// DSP primitives
// ---------------------------------------------------------------------------------------
void NativeHornEngine::Biquad::setLowPass(float fc, float q) {
    const float w0 = 2.0f * static_cast<float>(kPi) * fc / kSampleRate;
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0 = (1.0f - cw) / 2.0f / a0;
    b1 = (1.0f - cw) / a0;
    b2 = b0;
    a1 = -2.0f * cw / a0;
    a2 = (1.0f - alpha) / a0;
}

void NativeHornEngine::Biquad::setHighPass(float fc, float q) {
    const float w0 = 2.0f * static_cast<float>(kPi) * fc / kSampleRate;
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0 = (1.0f + cw) / 2.0f / a0;
    b1 = -(1.0f + cw) / a0;
    b2 = b0;
    a1 = -2.0f * cw / a0;
    a2 = (1.0f - alpha) / a0;
}

void NativeHornEngine::Biquad::setBandPass(float fc, float q) {
    const float w0 = 2.0f * static_cast<float>(kPi) * fc / kSampleRate;
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0 = alpha / a0;
    b1 = 0.0f;
    b2 = -alpha / a0;
    a1 = -2.0f * cw / a0;
    a2 = (1.0f - alpha) / a0;
}

void NativeHornEngine::Biquad::setPeaking(float fc, float q, float gainDb) {
    const float a = std::pow(10.0f, gainDb / 40.0f);
    const float w0 = 2.0f * static_cast<float>(kPi) * fc / kSampleRate;
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha / a;
    b0 = (1.0f + alpha * a) / a0;
    b1 = -2.0f * cw / a0;
    b2 = (1.0f - alpha * a) / a0;
    a1 = -2.0f * cw / a0;
    a2 = (1.0f - alpha / a) / a0;
}

void NativeHornEngine::OnePoleShelf::set(float gain, float fc) {
    // 02 section 3.3: prewarped bilinear shelf, DC gain 1, high-frequency gain `gain`.
    const float w0 = 2.0f * kSampleRate * std::tan(static_cast<float>(kPi) * fc / kSampleRate);
    const float k = 2.0f * kSampleRate;
    b0 = (gain * k + w0) / (k + w0);
    b1 = (w0 - gain * k) / (k + w0);
    a1 = (w0 - k) / (k + w0);
}

void NativeHornEngine::Wavetable::build(const float* gains, int count, int maxHarmonic) {
    const int harmonics = std::clamp(maxHarmonic, 1, count);
    for (int i = 0; i <= dsp::WAVETABLE_SIZE; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(dsp::WAVETABLE_SIZE);
        float sum = 0.0f;
        for (int h = 1; h <= harmonics; ++h) {
            sum += gains[h - 1] * std::sin(2.0f * static_cast<float>(kPi) * h * t);
        }
        table[i] = sum;
    }
    float peak = 0.0f;
    for (int i = 0; i < dsp::WAVETABLE_SIZE; ++i) {
        peak = std::max(peak, std::fabs(table[i]));
    }
    if (peak > 0.0f) {
        const float scale = 1.0f / peak;
        for (int i = 0; i <= dsp::WAVETABLE_SIZE; ++i) table[i] *= scale;
    }
    table[dsp::WAVETABLE_SIZE] = table[0];
}

void NativeHornEngine::Brassiness::configure(float d0Ms) {
    d0 = d0Ms * kSampleRate / 1000.0f;
    envAlpha = 1.0f - std::exp(-1.0f / (dsp::BRASS_TAU_S_MS * 0.001f * kSampleRate));
    reset();
}

void NativeHornEngine::Brassiness::reset() {
    line.fill(0.0f);
    index = 0;
    env = 0.0f;
}

float NativeHornEngine::Brassiness::process(float x, float beta) {
    // 01 section 2.3.1: y[n] = x[n - d0*(1 + beta*s[n])], s = onePole(|x|, tau_s).
    env += envAlpha * (std::fabs(x) - env);
    float d = d0 * (1.0f + beta * env);
    if (d < 0.25f) d = 0.25f;
    if (d > 128.0f) d = 128.0f;
    line[index] = x;
    const float q = static_cast<float>(index) - d;
    int i = static_cast<int>(std::floor(q));
    const float frac = q - static_cast<float>(i);
    i %= static_cast<int>(line.size());
    if (i < 0) i += static_cast<int>(line.size());
    int j = i + 1;
    if (j >= static_cast<int>(line.size())) j = 0;
    const float value = line[i] * (1.0f - frac) + line[j] * frac;
    index = (index + 1) % static_cast<int>(line.size());
    return value;
}

void NativeHornEngine::Comb::reset() {
    buffer.fill(0.0f);
    index = 0;
    lp.reset();
}

float NativeHornEngine::Comb::process(float x) {
    if (rho == 0.0f) return x;
    const float delayed = lp.process(ringRead(buffer, index, delay));
    const float y = x + rho * delayed;
    buffer[index] = x;
    ++index;
    if (index >= static_cast<int>(buffer.size())) index = 0;
    return y;
}

void NativeHornEngine::FdnBank::configure(const int* table, int lines, float gain,
                                          float dampingAmount) {
    activeLines = std::clamp(lines, 2, 8);
    g = std::clamp(gain, 0.0f, 0.999f);
    d = std::clamp(dampingAmount, 0.0f, 0.95f);
    // section 7 i: M=4 keeps four zero-weight lines; the 8-point butterfly would leak half of
    // the subsystem energy, so the loop gain is compensated by sqrt(2).
    compensation = activeLines == 8 ? 1.0f : std::sqrt(2.0f);
    const float tap = activeLines == 8 ? 0.35355339059327373f : 0.5f;
    const int half = activeLines / 2;
    for (int i = 0; i < 8; ++i) {
        length[i] = (i < activeLines) ? table[i] : dsp::FDN_MAX_DELAY;
        tapL[i] = i < activeLines ? tap : 0.0f;
        tapR[i] = i < activeLines ? (i < half ? tap : -tap) : 0.0f;
    }
}

void NativeHornEngine::FdnBank::reset() {
    for (auto& line : lines) line.fill(0.0f);
    index.fill(0);
    damping.fill(0.0f);
}

void NativeHornEngine::FdnBank::process(float sendMono, float& lateL, float& lateR) {
    float damped[8];
    float mixed[8];
    for (int i = 0; i < 8; ++i) {
        const float delayed = lines[i][index[i]];
        const float value = delayed * (1.0f - d) + damping[i] * d;
        damping[i] = value;
        damped[i] = value;
        mixed[i] = value;
    }
    hadamard8(mixed, mixed);
    const float sendGain = 4.0f * sendMono;  // FDN_SEND_GAIN = 4 (section 7 i)
    lateL = 0.0f;
    lateR = 0.0f;
    for (int i = 0; i < 8; ++i) {
        const float weight = i < activeLines ? 1.0f : 0.0f;
        float w = sendGain + mixed[i] * g * compensation * weight;
        if (w > 4.0f) w = 4.0f;
        if (w < -4.0f) w = -4.0f;
        lines[i][index[i]] = w;
        ++index[i];
        if (index[i] >= length[i]) index[i] = 0;
        lateL += tapL[i] * damped[i];
        lateR += tapR[i] * damped[i];
    }
}

// ---------------------------------------------------------------------------------------
// Scene path / stage (02 sections 2-4)
// ---------------------------------------------------------------------------------------
void NativeHornEngine::ScenePath::configure(int spaceIndex, const SceneTaps& spec) {
    space = spaceIndex;
    const dsp::SceneSpec& scene = dsp::SCENES[spaceIndex];
    tapCount = std::min(spec.count, dsp::ISM_MAX_TAPS);
    for (int i = 0; i < tapCount; ++i) taps[i] = spec.taps[i];

    const int* table = scene.fdnTable == 1 ? dsp::FDN_TABLE_A
                      : scene.fdnTable == 2 ? dsp::FDN_TABLE_B
                                            : dsp::FDN_TABLE_C;
    fdn.configure(table, scene.fdnLines, scene.fdnG, scene.fdnDamping);

    for (int c = 0; c < 2; ++c) {
        // One common wet-colouring comb per channel (ground / flutter / slab), Kotlin mirror.
        Comb& comb = colour[c];
        const bool flutter = scene.flutterSamples > 0.0f;
        comb.delay = std::max(flutter ? scene.flutterSamples : scene.groundDelaySamples, 1.0f);
        const float rho = flutter ? scene.flutterRho : scene.groundRho;
        comb.rho = rho > 0.0f ? std::min(rho, dsp::COMB_RHO_MAX) : 0.0f;
        const float lpHz = flutter ? scene.wallLpHz : scene.groundLpHz;
        comb.lp.a = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * lpHz / kSampleRate);
    }

    preDelaySamples = std::clamp(static_cast<int>(scene.preDelayMs * kSampleRate / 1000.0f),
                                 0, static_cast<int>(preDelay.size()) - 1);
    widthW = scene.widthW;
    wetMix = scene.wetMix;
}

void NativeHornEngine::ScenePath::reset() {
    tapLpL.fill(0.0f);
    tapLpR.fill(0.0f);
    fdn.reset();
    for (Comb& c : colour) c.reset();
    preDelay.fill(0.0f);
    preDelayIndex = 0;
    airL1.reset();
    airL2.reset();
    airR1.reset();
    airR2.reset();
}

void NativeHornEngine::SceneStage::reset() {
    historyL.fill(0.0f);
    historyR.fill(0.0f);
    historyIndex = 0;
    for (ScenePath& path : paths) path.reset();
    pendingSpace = -1;
    xfadePos = 1.0f;
    airG1 = airByScene[activeSpace][0];
    airG2 = airByScene[activeSpace][1];
    airSmoother.alpha = 1.0f - std::exp(-1.0f / (dsp::TAU_PARAM_MS * 0.001f * kSampleRate));
}

void NativeHornEngine::SceneStage::requestSpace(int space, const SceneTaps& taps) {
    if (space == activeSpace && pendingSpace < 0) return;
    if (space == pendingSpace) return;
    pendingSpace = space;
    paths[1 - activePath].configure(space, taps);
    xfadePos = 0.0f;
    xfadeInc = 1.0f / (dsp::TAU_SPACE_MS * 0.001f * kSampleRate);
}

void NativeHornEngine::SceneStage::process(float dryL, float dryR, float& outL, float& outR) {
    // Kotlin SceneEngine stores the mono mid in its ISM history; both rings mirror it.
    const float mid = 0.5f * (dryL + dryR);
    historyL[historyIndex] = mid;
    historyR[historyIndex] = mid;

    const int targetSpace = pendingSpace >= 0 ? pendingSpace : activeSpace;
    airG1 += airSmoother.alpha * (airByScene[targetSpace][0] - airG1);
    airG2 += airSmoother.alpha * (airByScene[targetSpace][1] - airG2);

    float aL = 0.0f;
    float aR = 0.0f;
    paths[activePath].process(dryL, dryR, historyL, historyR, historyIndex, aL, aR);
    float wa = 1.0f;
    float wb = 0.0f;
    if (pendingSpace >= 0) {
        // 02 section 6.3: equal-power crossfade, delay-line content preserved on both paths.
        const float angle = 0.5f * static_cast<float>(kPi) * xfadePos;
        wa = std::cos(angle);
        wb = std::sin(angle);
        float bL = 0.0f;
        float bR = 0.0f;
        paths[1 - activePath].process(dryL, dryR, historyL, historyR, historyIndex, bL, bR);
        xfadePos += xfadeInc;
        if (xfadePos >= 1.0f) {
            xfadePos = 1.0f;
            activeSpace = pendingSpace;
            pendingSpace = -1;
            activePath = 1 - activePath;
            wa = 1.0f;
            wb = 0.0f;
        }
        outL = wa * aL + wb * bL;
        outR = wa * aR + wb * bR;
    } else {
        outL = aL;
        outR = aR;
    }

    ++historyIndex;
    if (historyIndex >= dsp::ISM_HISTORY) historyIndex = 0;
}

void NativeHornEngine::SceneStage::endBlock() {
    for (ScenePath& path : paths) {
        path.airL1.set(airG1, dsp::AIR_SHELF_F1);
        path.airL2.set(airG2, dsp::AIR_SHELF_F2);
        path.airR1.set(airG1, dsp::AIR_SHELF_F1);
        path.airR2.set(airG2, dsp::AIR_SHELF_F2);
        for (int i = 0; i < 8; ++i) {
            if (std::fabs(path.fdn.damping[i]) < dsp::DENORMAL_EPS) path.fdn.damping[i] = 0.0f;
        }
        for (Comb& c : path.colour) {
            if (std::fabs(c.lp.state) < dsp::DENORMAL_EPS) c.lp.state = 0.0f;
        }
    }
}

void NativeHornEngine::ScenePath::process(float dryL, float dryR,
                                          const std::array<float, dsp::ISM_HISTORY>& histL,
                                          const std::array<float, dsp::ISM_HISTORY>& histR,
                                          int histIdx, float& outL, float& outR) {
    float earlyL = 0.0f;
    float earlyR = 0.0f;
    for (int t = 0; t < tapCount; ++t) {
        const IsmTapSpec& tap = taps[t];
        tapLpL[t] += tap.lpAlpha * (ringRead(histL, histIdx, tap.delayL) - tapLpL[t]);
        tapLpR[t] += tap.lpAlpha * (ringRead(histR, histIdx, tap.delayR) - tapLpR[t]);
        earlyL += tap.gainL * tapLpL[t];
        earlyR += tap.gainR * tapLpR[t];
    }

    // Late tail: pre-delayed mono mid send (SceneEngine mirror), section 7 i topology.
    const float mid = 0.5f * (dryL + dryR);
    preDelay[preDelayIndex] = mid;
    int sendIndex = preDelayIndex - preDelaySamples;
    if (sendIndex < 0) sendIndex += static_cast<int>(preDelay.size());
    const float send = preDelay[sendIndex];
    ++preDelayIndex;
    if (preDelayIndex >= static_cast<int>(preDelay.size())) preDelayIndex = 0;

    float lateL = 0.0f;
    float lateR = 0.0f;
    fdn.process(send, lateL, lateR);
    const float lateMid = (lateL + lateR) * 0.5f;
    const float lateSide = (lateL - lateR) * 0.5f * widthW;

    float wetL = earlyL + lateMid + lateSide;
    float wetR = earlyR + lateMid - lateSide;

    // Common wet colouring comb (ground micro-comb / flutter / slab), identical on L/R (C4).
    wetL = colour[0].process(wetL);
    wetR = colour[1].process(wetR);

    wetL = airL2.process(airL1.process(wetL));
    wetR = airR2.process(airR1.process(wetR));

    outL = dryL * (1.0f - wetMix * 0.5f) + wetL * wetMix;
    outR = dryR * (1.0f - wetMix * 0.5f) + wetR * wetMix;
}

// ---------------------------------------------------------------------------------------
// Output mode chains (03)
// ---------------------------------------------------------------------------------------
void NativeHornEngine::VbsChain::configure() {
    hpReal.a = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * dsp::VBS_HP_FC / kSampleRate);
    hpBiquad.setHighPass(dsp::VBS_HP_FC, 0.5f);
    bpHp.setHighPass(dsp::VBS_BPF_LO_HZ, 0.707f);
    bpLp.setLowPass(dsp::VBS_BPF_HI_HZ, 0.707f);
    const float dcA = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * 20.0f / kSampleRate);
    nldDc1.a = dcA;
    nldDc2.a = dcA;
    meanAlpha = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * dsp::VBS_NLD_MEAN_HZ / kSampleRate);

    // 63-tap linear-phase band-pass (250..900 Hz), Blackman window, band-centre gain 1.
    const int taps = dsp::VBS_FIR_TAPS;
    const float lo = dsp::VBS_FIR_LO_HZ / kSampleRate;
    const float hi = dsp::VBS_FIR_HI_HZ / kSampleRate;
    for (int k = 0; k < taps; ++k) {
        const float m = static_cast<float>(k - (taps - 1) / 2);
        float sinc;
        if (m == 0.0f) {
            sinc = 2.0f * (hi - lo);
        } else {
            sinc = (std::sin(2.0f * static_cast<float>(kPi) * hi * m) -
                    std::sin(2.0f * static_cast<float>(kPi) * lo * m)) /
                (static_cast<float>(kPi) * m);
        }
        const float w = 0.42f - 0.5f * std::cos(2.0f * static_cast<float>(kPi) * k / (taps - 1)) +
            0.08f * std::cos(4.0f * static_cast<float>(kPi) * k / (taps - 1));
        fir[k] = sinc * w;
    }
    const float f0 = std::sqrt(dsp::VBS_FIR_LO_HZ * dsp::VBS_FIR_HI_HZ);
    float gain = 0.0f;
    for (int k = 0; k < taps; ++k) {
        const float m = static_cast<float>(k - (taps - 1) / 2);
        gain += fir[k] * std::cos(2.0f * static_cast<float>(kPi) * f0 * m / kSampleRate);
    }
    if (std::fabs(gain) > 1e-9f) {
        for (float& c : fir) c /= gain;
    }
    reset();
}

void NativeHornEngine::VbsChain::reset() {
    hpBiquad.reset();
    hpReal.reset();
    bpHp.reset();
    bpLp.reset();
    nldDc1.reset();
    nldDc2.reset();
    firHistory.fill(0.0f);
    firIndex = 0;
    alignM.fill(0.0f);
    alignS.fill(0.0f);
    alignIndex = 0;
    nldMean = 0.0f;
}

void NativeHornEngine::VbsChain::process(float l, float r, float inject, float& outL,
                                         float& outR) {
    const float m = 0.5f * (l + r);
    const float s = 0.5f * (l - r);
    const float m90 = hpBiquad.process(hpReal.process(m));

    // low-frequency extraction 80..280 Hz -> composite NLD -> 250..900 Hz harmonic limit
    float x = bpLp.process(bpHp.process(m90));
    x = std::clamp(x, -dsp::VBS_NLD_INPUT_LIMIT, dsp::VBS_NLD_INPUT_LIMIT);
    nldMean += meanAlpha * (std::fabs(x) - nldMean);
    float y = dsp::VBS_WE * (std::fabs(x) - nldMean) + dsp::VBS_WO * x * std::fabs(x);
    y = nldDc2.process(nldDc1.process(y));

    firHistory[firIndex] = y;
    float aligned = 0.0f;
    int tap = firIndex;
    for (int k = 0; k < dsp::VBS_FIR_TAPS; ++k) {
        aligned += fir[k] * firHistory[tap];
        if (--tap < 0) tap = dsp::VBS_FIR_TAPS - 1;
    }
    firIndex = (firIndex + 1) % dsp::VBS_FIR_TAPS;

    // group-delay compensation: dry branch delayed by (N-1)/2 samples
    alignM[alignIndex] = m90;
    alignS[alignIndex] = s;
    int read = alignIndex - dsp::VBS_ALIGN_DELAY;
    if (read < 0) read += static_cast<int>(alignM.size());
    const float mDelayed = alignM[read];
    const float sDelayed = alignS[read];
    alignIndex = (alignIndex + 1) % static_cast<int>(alignM.size());

    const float mono = mDelayed + dsp::VBS_GMIX * inject * aligned;
    outL = mono + dsp::VBS_STEREO_SPREAD * sDelayed;
    outR = mono - dsp::VBS_STEREO_SPREAD * sDelayed;
}

void NativeHornEngine::HeadphoneChain::configure() {
    ild.set(std::pow(10.0f, dsp::HP_ILD_DB / 20.0f), dsp::HP_ILD_FC);
    reset();
}

void NativeHornEngine::HeadphoneChain::reset() {
    ild.reset();
    sDelay.fill(0.0f);
    sIndex = 0;
}

void NativeHornEngine::HeadphoneChain::process(float l, float r, float& outL, float& outR) {
    const float m = 0.5f * (l + r);
    float s = 0.5f * (l - r) * dsp::HP_WIDTH;
    s = ild.process(s);
    sDelay[sIndex] = s;
    int read = sIndex - static_cast<int>(dsp::HP_ITD_SAMPLES);
    if (read < 0) read += static_cast<int>(sDelay.size());
    const float sDelayed = sDelay[read];
    sIndex = (sIndex + 1) % static_cast<int>(sDelay.size());
    outL = m + sDelayed;
    outR = m - sDelayed;
}

void NativeHornEngine::OutputStage::configure() {
    aAtt = std::exp(-1.0f / (kSampleRate * dsp::LIMITER_TAU_ATT_MS * 0.001f));
    aRel = std::exp(-1.0f / (kSampleRate * dsp::LIMITER_TAU_REL_MS * 0.001f));
    reset();
}

void NativeHornEngine::OutputStage::reset() {
    tpHistory.fill(0.0f);
    tpIndex = 0;
    lookahead.fill(0.0f);
    lookIndex = 0;
    gain = 1.0f;
}

float NativeHornEngine::OutputStage::process(float x) {
    // 03 section 2.4.1 true-peak estimate: 4x polyphase interpolation.
    tpHistory[tpIndex] = x;
    float tp = std::fabs(x);
    for (int p = 0; p < dsp::TP_PHASES; ++p) {
        float acc = 0.0f;
        int tap = tpIndex;
        for (int k = 0; k < dsp::TP_TAPS; ++k) {
            acc += dsp::TP_COEFFS[p][k] * tpHistory[tap];
            if (--tap < 0) tap = dsp::TP_TAPS - 1;
        }
        tp = std::max(tp, std::fabs(acc));
    }
    tpIndex = (tpIndex + 1) % dsp::TP_TAPS;

    // 03 section 2.4.2 look-ahead smoothed gain, exactly the frozen formula.
    const float target = tp > 0.0f ? std::min(1.0f, dsp::LIMITER_THRESHOLD / tp) : 1.0f;
    gain = target < gain ? aAtt * gain + (1.0f - aAtt) * target
                         : aRel * gain + (1.0f - aRel) * target;

    lookahead[lookIndex] = x;
    int read = lookIndex - dsp::LIMITER_LOOKAHEAD;
    if (read < 0) read += static_cast<int>(lookahead.size());
    const float delayed = lookahead[read];
    lookIndex = (lookIndex + 1) % static_cast<int>(lookahead.size());

    float y = gain * delayed;
    y = std::tanh(y * dsp::LIMITER_DRIVE) / std::tanh(dsp::LIMITER_DRIVE);
    return std::clamp(y, -dsp::HARD_CEILING, dsp::HARD_CEILING);
}

// ---------------------------------------------------------------------------------------
// Engine lifecycle
// ---------------------------------------------------------------------------------------
float NativeHornEngine::shapeAsym(float x, float k, float alpha) {
    // 01 section 2.3.2: bounded asymmetric wave steepening, normalised by W_bound.
    if (x > 1.0f) x = 1.0f;
    if (x < -1.0f) x = -1.0f;
    k = std::clamp(k, 0.25f, 6.0f);
    const float shaped = std::tanh(k * (x + alpha * (x * x - 1.0f / 3.0f)));
    const float bound = std::tanh(k * (1.0f + alpha * 2.0f / 3.0f));
    return bound > 0.0f ? shaped / bound : shaped;
}

int NativeHornEngine::bandLimit(float fMaxHz) {
    if (fMaxHz <= 1.0f) return 1;
    const int n = static_cast<int>(std::floor(0.45f * kSampleRate / fMaxHz));
    return std::max(1, n);
}

NativeHornEngine::NativeHornEngine() {
    // Wavetables are built band-limited up front (01 section 2.1.1, conflict C2 fix).
    const float trainFMax = dsp::TRAIN_F[4] * (1.0f + dsp::TRAIN_DETUNE) *
        (1.0f - dsp::TRAIN_PITCH_SAG_MIN) * (1.0f + dsp::EXPR_PITCH_BEND);

    tableTrainChord_.build(dsp::TRAIN_CHORD_HARMONICS, 8, bandLimit(trainFMax));
    tableTrainSub_.build(dsp::TRAIN_SUB_HARMONICS, 4,
                         bandLimit(dsp::TRAIN_F[1] * (1.0f + dsp::TRAIN_DETUNE) *
                                   (1.0f - dsp::TRAIN_PITCH_SAG_MIN) *
                                   (1.0f + dsp::EXPR_PITCH_BEND)));
    // EBIKE pulse table: N_max = 7 -> the 8th harmonic is dropped (conflict C2).
    tableEbikePulse_.build(dsp::EBIKE_PULSE_HARMONICS, 8,
                           bandLimit(dsp::EBIKE_F_B * 1.08f));
    tableEbikeBody_.build(dsp::EBIKE_BODY_HARMONICS, 3,
                          bandLimit(dsp::EBIKE_F_BODY * 1.08f));
    tableMoto_.build(dsp::MOTO_DIAPHRAGM_HARMONICS, 8, bandLimit(dsp::MOTO_F * 1.08f));

    envelope_.setTimes(12.0f, 14.0f);

    VoiceState& v = voices_;
    v.brassCarLow.configure(dsp::BRASS_D0_CAR_MS);
    v.brassCarHigh.configure(dsp::BRASS_D0_CAR_MS);
    v.brassTrainL.configure(dsp::BRASS_D0_TRAIN_MS);
    v.brassTrainR.configure(dsp::BRASS_D0_TRAIN_MS);
    v.brassCustomL.configure(dsp::BRASS_D0_CUSTOM_MS);
    v.brassCustomR.configure(dsp::BRASS_D0_CUSTOM_MS);

    for (int i = 0; i < 3; ++i) {
        v.carFormantL[i].setPeaking(dsp::FORMANT_F[i], dsp::FORMANT_Q[i], dsp::FORMANT_DB[i]);
        v.carFormantR[i].setPeaking(dsp::FORMANT_F[i], dsp::FORMANT_Q[i], dsp::FORMANT_DB[i]);
    }
    v.carLpL.setLowPass(dsp::CAR_LOW_PASS_HZ, 0.707f);
    v.carLpR.setLowPass(dsp::CAR_LOW_PASS_HZ, 0.707f);
    v.carThroatLow.a = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) *
                                       dsp::CAR_THROAT_HP_LOW / kSampleRate);
    v.carThroatHigh.a = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) *
                                        dsp::CAR_THROAT_HP_HIGH / kSampleRate);
    v.carNoiseLp.a = dsp::CAR_NOISE_SMOOTH;
    v.carRelayBpf.setBandPass(dsp::CAR_RELAY_BPF_HZ, dsp::CAR_RELAY_BPF_Q);
    const float ringR = std::exp(-dsp::CAR_RING_ZETA * 2.0f * static_cast<float>(kPi) *
                                 dsp::CAR_RING_F / kSampleRate);
    const float ringTheta = 2.0f * static_cast<float>(kPi) * dsp::CAR_RING_F / kSampleRate *
        std::sqrt(1.0f - dsp::CAR_RING_ZETA * dsp::CAR_RING_ZETA);
    v.ringA1 = -2.0f * ringR * std::cos(ringTheta);
    v.ringA2 = ringR * ringR;

    v.trainAirBpf1.setBandPass(dsp::TRAIN_AIR_BPF1_HZ, dsp::TRAIN_AIR_BPF1_Q);
    v.trainAirBpf2.setBandPass(dsp::TRAIN_AIR_BPF2_HZ, dsp::TRAIN_AIR_BPF2_Q);
    v.trainHpL.setHighPass(80.0f, 0.707f);
    v.trainHpR.setHighPass(80.0f, 0.707f);
    v.trainLpL.setLowPass(5600.0f, 0.707f);
    v.trainLpR.setLowPass(5600.0f, 0.707f);
    for (int i = 0; i < dsp::TRAIN_VOICES; ++i) {
        v.tauOpenA[i] = 1.0f - std::exp(-1.0f / (dsp::TRAIN_TAU_OPEN_MS[i] * 0.001f * kSampleRate));
        v.tauCloseA[i] = 1.0f - std::exp(-1.0f / (dsp::TRAIN_TAU_CLOSE_MS[i] * 0.001f * kSampleRate));
        v.trainPanL[i] = dsp::TRAIN_PAN_L[i];
        v.trainPanR[i] = dsp::TRAIN_PAN_R[i];
    }

    v.ebikeHp.setHighPass(dsp::EBIKE_HP_HZ, 0.707f);
    v.ebikeLp1.setLowPass(dsp::EBIKE_LP_HZ, dsp::EBIKE_LP_Q);
    v.ebikeLp2.setLowPass(dsp::EBIKE_LP_HZ, dsp::EBIKE_LP_Q);
    v.ebikeNotch.setPeaking(dsp::EBIKE_NOTCH_HZ, dsp::EBIKE_NOTCH_Q, dsp::EBIKE_NOTCH_DB);
    v.ebikeDcHp.setHighPass(dsp::EBIKE_CLICK_HP_HZ, 0.707f);

    v.motoFormant[0].setPeaking(dsp::MOTO_FORMANT_F[0], dsp::MOTO_FORMANT_Q[0],
                                dsp::MOTO_FORMANT_DB[0]);
    v.motoFormant[1].setPeaking(dsp::MOTO_FORMANT_F[1], dsp::MOTO_FORMANT_Q[1],
                                dsp::MOTO_FORMANT_DB[1]);
    v.motoHp.setHighPass(dsp::MOTO_HP_HZ, 0.707f);
    v.motoLp.setLowPass(dsp::MOTO_LP_HZ, 0.707f);

    v.customRelayBpf.setBandPass(dsp::CAR_RELAY_BPF_HZ, dsp::CAR_RELAY_BPF_Q);
    buildCustomTable();

    buildSceneTaps();
    for (int s = 0; s < dsp::SCENE_COUNT; ++s) {
        fitShelfPair(dsp::SCENES[s].airDistanceM, scene_.airByScene[s][0],
                     scene_.airByScene[s][1]);
    }
    scene_.reset();
    scene_.paths[0].configure(0, sceneTaps_[0]);
    scene_.activeSpace = 0;
    scene_.pendingSpace = -1;
    scene_.xfadePos = 1.0f;
    scene_.endBlock();  // publish initial air shelf coefficients

    vbs_.configure();
    headphone_.configure();
    output_[0].configure();
    output_[1].configure();

    for (auto& value : waveformL_) value.store(0);
    for (auto& value : waveformR_) value.store(0);
}

NativeHornEngine::~NativeHornEngine() {
    release();
}

bool NativeHornEngine::prepare() {
    enableFlushToZero();
    return openStreamLocked();
}

bool NativeHornEngine::openStreamLocked() {
    if (stream_) return true;
    if (released_.load()) return false;

    auto build = [this](oboe::SharingMode mode) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(mode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(kSampleRate)
            ->setFramesPerDataCallback(kFramesPerCallback)
            ->setBufferCapacityInFrames(kFramesPerCallback * 4)
            ->setDataCallback(this);
        return builder.openStream(stream_);
    };

    oboe::Result result = build(oboe::SharingMode::Exclusive);
    if (result != oboe::Result::OK) {
        stream_.reset();
        result = build(oboe::SharingMode::Shared);
    }
    return result == oboe::Result::OK && stream_ != nullptr;
}

void NativeHornEngine::closeStream() {
    if (!stream_) return;
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

void NativeHornEngine::release() {
    if (released_.exchange(true)) return;
    playing_.store(false);
    envelope_.reset();
    closeStream();
}

bool NativeHornEngine::start() {
    if (released_.load()) return false;
    if (!openStreamLocked()) return false;

    playing_.store(true);
    command_.store(1);
    return stream_->requestStart() == oboe::Result::OK;
}

void NativeHornEngine::stop() {
    playing_.store(false);
    command_.store(2);
}

void NativeHornEngine::panic() {
    playing_.store(false);
    command_.store(3);
    workL_.fill(0.0f);
    workR_.fill(0.0f);
}

void NativeHornEngine::setType(int type) {
    requestedType_.store(std::clamp(type, kTypeCar, kTypeCustom));
}

void NativeHornEngine::setExpression(float expression) {
    expression_.store(std::clamp(expression, -1.0f, 1.0f));
}

void NativeHornEngine::setSpace(int space) {
    requestedSpace_.store(std::clamp(space, 0, dsp::SCENE_COUNT - 1));
}

void NativeHornEngine::setProtection(bool enabled) {
    protection_.store(enabled ? 1 : 0);
}

void NativeHornEngine::setOutputMode(int mode) {
    outputMode_.store(std::clamp(mode, dsp::MODE_SPEAKER_VBS, dsp::MODE_HEADPHONE_FULL_RANGE));
}

void NativeHornEngine::setCustomProfile(float primary, float ratio, bool dual, float richness,
                                        float attackMs, float releaseMs, float saturation) {
    customPrimary_.store(std::clamp(primary, dsp::CUSTOM_F_MIN, dsp::CUSTOM_F_MAX));
    customRatio_.store(std::clamp(ratio, 0.5f, 2.0f));
    customDual_.store(dual ? 1 : 0);
    customRichness_.store(std::clamp(richness, 0.0f, 1.0f));
    customAttack_.store(std::clamp(attackMs, 3.0f, 120.0f));
    customRelease_.store(std::clamp(releaseMs, 5.0f, 250.0f));
    customSaturation_.store(std::clamp(saturation, 0.0f, 1.0f));
    customTableDirty_.store(true, std::memory_order_relaxed);
}

void NativeHornEngine::switchType(int type) {
    activeType_ = type;
    resetOscillators();
    setEnvelopeTimes(type);
    envelope_.reset();
    envelope_.noteOn();
}

void NativeHornEngine::setEnvelopeTimes(int type) {
    switch (type) {
        case kTypeCar: envelope_.setTimes(12.0f, 14.0f); break;
        case kTypeTrain: envelope_.setTimes(65.0f, 185.0f); break;
        case kTypeEbike: envelope_.setTimes(4.0f, 6.0f); break;
        case kTypeMotorcycle: envelope_.setTimes(8.0f, 10.0f); break;
        default: envelope_.setTimes(customAttack_.load(), customRelease_.load()); break;
    }
}

void NativeHornEngine::resetOscillators() {
    VoiceState& v = voices_;
    v.phCarLow = 0.0f;
    v.phCarHigh = 0.0f;
    for (int i = 0; i < dsp::TRAIN_VOICES; ++i) {
        v.phTrain[i][0] = dsp::PHASE_TRAIN_PAIR[0];
        v.phTrain[i][1] = dsp::PHASE_TRAIN_PAIR[1];
    }
    v.phEbikeA = dsp::PHASE_EBIKE[0];
    v.phEbikeB = dsp::PHASE_EBIKE[1];
    v.phEbikeBody = dsp::PHASE_EBIKE[2];
    v.phMoto = dsp::PHASE_MOTO;
    v.phMotoMod = 0.0f;
    v.phCustomA = dsp::PHASE_CUSTOM[0];
    v.phCustomB = dsp::PHASE_CUSTOM[1];

    v.carThroatLow.reset();
    v.carThroatHigh.reset();
    for (auto& b : v.carFormantL) b.reset();
    for (auto& b : v.carFormantR) b.reset();
    v.carLpL.reset();
    v.carLpR.reset();
    v.carNoiseLp.reset();
    v.carSwayPhase = 0.0f;
    v.carBeatPhase = 0.0f;
    v.carRelayBpf.reset();
    v.carRelayEnv = 0.0f;
    v.carRelayDelay = 0.0f;
    v.ringY1 = 0.0f;
    v.ringY2 = 0.0f;
    v.ringEnv = 0.0f;

    v.pressure.fill(0.0f);
    v.sag.fill(0.0f);
    v.valveDelay.fill(0);
    v.valveTarget.fill(0.0f);
    v.trainAirBpf1.reset();
    v.trainAirBpf2.reset();
    v.trainHpL.reset();
    v.trainHpR.reset();
    v.trainLpL.reset();
    v.trainLpR.reset();

    v.ebikeHp.reset();
    v.ebikeLp1.reset();
    v.ebikeLp2.reset();
    v.ebikeNotch.reset();
    v.ebikeDcHp.reset();
    v.ebikeClickTimer = 0;

    for (auto& b : v.motoFormant) b.reset();
    v.motoHp.reset();
    v.motoLp.reset();

    v.customThroatYL = 0.0f;
    v.customThroatXL = 0.0f;
    v.customThroatYR = 0.0f;
    v.customThroatXR = 0.0f;
    v.customRadYL = 0.0f;
    v.customRadXL = 0.0f;
    v.customRadYR = 0.0f;
    v.customRadXR = 0.0f;
    v.customLpL.reset();
    v.customLpR.reset();
    v.customRelayBpf.reset();
    v.customClickEnv = 0.0f;
    v.customRingY1 = 0.0f;
    v.customRingY2 = 0.0f;
    v.customRingEnv = 0.0f;

    v.brassCarLow.reset();
    v.brassCarHigh.reset();
    v.brassTrainL.reset();
    v.brassTrainR.reset();
    v.brassCustomL.reset();
    v.brassCustomR.reset();
    switch (activeType_) {
        case kTypeTrain:
            v.noiseState = dsp::NOISE_SEED_TRAIN;
            break;
        case kTypeCustom:
            v.noiseState = dsp::NOISE_SEED_CUSTOM;
            break;
        default:
            v.noiseState = dsp::NOISE_SEED_CAR;
            break;
    }
}

void NativeHornEngine::voiceNoteOn() {
    VoiceState& v = voices_;
    v.carRelayEnv = dsp::CAR_RELAY_AMP;
    v.carRelayDelay = dsp::CAR_RELAY_BURST2;
    v.ringEnv = 0.0f;
    v.ebikeClickTimer = 48;  // 1 ms unipolar DC switch click
    for (int i = 0; i < dsp::TRAIN_VOICES; ++i) {
        v.valveTarget[i] = 1.0f;
        v.valveDelay[i] = static_cast<int>(dsp::TRAIN_T_ON_MS[i] * kSampleRate / 1000.0f);
    }
    v.customClickEnv = std::clamp(0.05f * (20.0f / customAttack_.load()), 0.0f, 0.05f);
    v.active = true;
}

void NativeHornEngine::voiceNoteOff() {
    VoiceState& v = voices_;
    for (int i = 0; i < dsp::TRAIN_VOICES; ++i) {
        v.valveTarget[i] = 0.0f;
        v.valveDelay[i] = static_cast<int>(dsp::TRAIN_T_OFF_MS[i] * kSampleRate / 1000.0f);
    }
    v.ringY1 += dsp::CAR_RING_EXCITE;
    v.ringEnv = 1.0f;
    v.customRingY1 += dsp::CUSTOM_RING_EXCITE;
    v.customRingEnv = 1.0f;
    v.active = false;
}
void NativeHornEngine::renderVoices(float pitchScale, float driveScale, float noiseScale,
                                    float brassBeta, float brassAsym, float& outL, float& outR) {
    switch (activeType_) {
        case kTypeCar:
            renderCar(pitchScale, driveScale, brassBeta, brassAsym, noiseScale, outL, outR);
            break;
        case kTypeTrain:
            renderTrain(pitchScale, driveScale, brassBeta, brassAsym, noiseScale, outL, outR);
            break;
        case kTypeEbike:
            renderEbike(pitchScale, driveScale, outL, outR);
            break;
        case kTypeMotorcycle:
            renderMotorcycle(pitchScale, driveScale, outL, outR);
            break;
        default:
            renderCustom(pitchScale, driveScale, brassBeta, brassAsym, outL, outR);
            break;
    }
}

void NativeHornEngine::renderCar(float pitchScale, float driveScale, float brassBeta,
                                 float brassAsym, float noiseScale, float& outL, float& outR) {
    static const float kRelayDecay = std::exp(-1.0f / (kSampleRate * dsp::CAR_RELAY_TAU_S));
    static const float kRingDecay = std::exp(-1.0f / (kSampleRate * dsp::CAR_RING_TAU_OFF_S));
    VoiceState& v = voices_;
    // Kotlin ToneGenerator mirror: additive harmonic sum x 0.43, exact 400/500 Hz lines (7 h).
    double lowPhase = static_cast<double>(v.phCarLow) * 2.0 * kPi;
    double highPhase = static_cast<double>(v.phCarHigh) * 2.0 * kPi;
    double rawLow = 0.0;
    double rawHigh = 0.0;
    for (int h = 1; h <= 8; ++h) {
        rawLow += dsp::CAR_LOW_HARMONICS[h - 1] * std::sin(lowPhase * h);
    }
    for (int h = 1; h <= 7; ++h) {
        rawHigh += dsp::CAR_HIGH_HARMONICS[h - 1] * std::sin(highPhase * h);
    }
    float low = static_cast<float>(rawLow * dsp::CAR_HARMONIC_NORM);
    float high = static_cast<float>(rawHigh * dsp::CAR_HARMONIC_NORM);
    v.phCarLow += dsp::CAR_F_LOW * pitchScale / kSampleRate;
    if (v.phCarLow >= 1.0f) v.phCarLow -= 1.0f;
    v.phCarHigh += dsp::CAR_F_HIGH * pitchScale / kSampleRate;
    if (v.phCarHigh >= 1.0f) v.phCarHigh -= 1.0f;

    low = v.carThroatLow.process(low);
    high = v.carThroatHigh.process(high);
    low = v.brassCarLow.process(low, brassBeta);
    high = v.brassCarHigh.process(high, brassBeta);

    const float white = whiteNoise(v.noiseState);
    v.carNoiseLp.state += dsp::CAR_NOISE_SMOOTH * (white - v.carNoiseLp.state);
    v.carSwayPhase += 2.0f * static_cast<float>(kPi) * dsp::CAR_SWAY_HZ / kSampleRate;
    if (v.carSwayPhase >= 2.0f * static_cast<float>(kPi)) v.carSwayPhase -= 2.0f * static_cast<float>(kPi);
    v.carBeatPhase += 2.0f * static_cast<float>(kPi) * dsp::CAR_BEAT_HZ / kSampleRate;
    if (v.carBeatPhase >= 2.0f * static_cast<float>(kPi)) v.carBeatPhase -= 2.0f * static_cast<float>(kPi);
    const float sway = (1.0f + dsp::CAR_SWAY_DEPTH * std::sin(v.carSwayPhase)) *
        (1.0f + dsp::CAR_BEAT_DEPTH * std::sin(v.carBeatPhase));

    if (v.carRelayDelay > 0.0f) {
        v.carRelayDelay -= 1.0f;
        if (v.carRelayDelay <= 0.0f) v.carRelayEnv = dsp::CAR_RELAY_AMP;
    }
    const float relay = v.carRelayBpf.process(whiteNoise(v.noiseState)) * v.carRelayEnv;
    v.carRelayEnv *= kRelayDecay;

    const float ring = (-v.ringA1 * v.ringY1 - v.ringA2 * v.ringY2) * v.ringEnv;
    v.ringY2 = v.ringY1;
    v.ringY1 = ring;
    v.ringEnv *= kRingDecay;

    const float centre =
        (v.carNoiseLp.state * dsp::CAR_NOISE_LEVEL * noiseScale + relay + ring) * sway;
    const float lowPart = low * dsp::CAR_WEIGHT_LOW;
    const float highPart = high * dsp::CAR_WEIGHT_HIGH;
    const float mixedL = (lowPart * dsp::CAR_PAN_LOW[0] + highPart * dsp::CAR_PAN_HIGH[0]) * sway + centre;
    const float mixedR = (lowPart * dsp::CAR_PAN_LOW[1] + highPart * dsp::CAR_PAN_HIGH[1]) * sway + centre;

    const float k = dsp::CAR_SHAPE_K * driveScale;
    outL = shapeAsym(mixedL, k, brassAsym);
    outR = shapeAsym(mixedR, k, brassAsym);
    for (int i = 0; i < 3; ++i) {
        outL = v.carFormantL[i].process(outL);
        outR = v.carFormantR[i].process(outR);
    }
    outL = std::clamp(v.carLpL.process(outL) * dsp::CAR_OUTPUT_GAIN,
                      -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
    outR = std::clamp(v.carLpR.process(outR) * dsp::CAR_OUTPUT_GAIN,
                      -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
}

void NativeHornEngine::renderTrain(float pitchScale, float driveScale, float brassBeta,
                                   float brassAsym, float noiseScale, float& outL, float& outR) {
    static const float kSagAlpha =
        1.0f - std::exp(-1.0f / (kSampleRate * dsp::TRAIN_PITCH_SAG_TAU_MS * 0.001f));
    VoiceState& v = voices_;
    float chordL = 0.0f;
    float chordR = 0.0f;
    float pressureSum = 0.0f;

    for (int i = 0; i < dsp::TRAIN_VOICES; ++i) {
        // Kotlin ValveVoice: delay countdown precedes pressure movement.
        if (v.valveDelay[i] > 0) {
            --v.valveDelay[i];
            if (v.valveDelay[i] == 0 && v.valveTarget[i] > 0.5f) {
                v.sag[i] = dsp::TRAIN_PITCH_SAG_ONSET;  // C4 ruling
            }
        } else {
            const float coefficient =
                v.valveTarget[i] > v.pressure[i] ? v.tauOpenA[i] : v.tauCloseA[i];
            v.pressure[i] += (v.valveTarget[i] - v.pressure[i]) * coefficient;
        }
        v.sag[i] += (0.0f - v.sag[i]) * kSagAlpha;
        pressureSum += v.pressure[i];

        float offset = v.sag[i] + dsp::TRAIN_PITCH_SAG_KAPPA * (v.pressure[i] - 1.0f);
        if (offset < dsp::TRAIN_PITCH_SAG_MIN) offset = dsp::TRAIN_PITCH_SAG_MIN;
        if (offset > dsp::TRAIN_PITCH_SAG_MAX) offset = dsp::TRAIN_PITCH_SAG_MAX;
        const float scale = (1.0f + offset) * pitchScale;

        const bool sub = i < 2;
        const Wavetable& table = sub ? tableTrainSub_ : tableTrainChord_;
        const float f = dsp::TRAIN_F[i];
        float tone = table.read(v.phTrain[i][0],
                                f * (1.0f - dsp::TRAIN_DETUNE) * scale / kSampleRate) *
            dsp::TRAIN_GAIN_A[i];
        if (dsp::TRAIN_GAIN_B[i] > 0.0f) {
            tone += table.read(v.phTrain[i][1],
                               f * (1.0f + dsp::TRAIN_DETUNE) * scale / kSampleRate) *
                dsp::TRAIN_GAIN_B[i];
        }
        chordL += tone * v.trainPanL[i];
        chordR += tone * v.trainPanR[i];
    }

    const float pressureMean = pressureSum / static_cast<float>(dsp::TRAIN_VOICES);
    const float steepenedL = v.brassTrainL.process(chordL, brassBeta * pressureMean);
    const float steepenedR = v.brassTrainR.process(chordR, brassBeta * pressureMean);

    float air = v.trainAirBpf2.process(v.trainAirBpf1.process(whiteNoise(v.noiseState)));
    air *= dsp::TRAIN_AIR_LEVEL * (0.5f + 0.5f * pressureMean) * noiseScale;

    const float drive = dsp::TRAIN_SHAPE_K * driveScale * (0.6f + 0.4f * pressureMean);
    outL = v.trainLpL.process(v.trainHpL.process(
        shapeAsym(steepenedL + air, drive, brassAsym))) * dsp::TRAIN_OUTPUT_GAIN;
    outR = v.trainLpR.process(v.trainHpR.process(
        shapeAsym(steepenedR + air, drive, brassAsym))) * dsp::TRAIN_OUTPUT_GAIN;
    outL = std::clamp(outL, -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
    outR = std::clamp(outR, -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
}

void NativeHornEngine::renderEbike(float pitchScale, float driveScale, float& outL, float& outR) {
    VoiceState& v = voices_;
    float mixed = tableEbikePulse_.read(v.phEbikeA, dsp::EBIKE_F_A * pitchScale / kSampleRate) *
        dsp::EBIKE_GAIN_A;
    mixed += tableEbikePulse_.read(v.phEbikeB, dsp::EBIKE_F_B * pitchScale / kSampleRate) *
        dsp::EBIKE_GAIN_B;
    mixed += tableEbikeBody_.read(v.phEbikeBody, dsp::EBIKE_F_BODY * pitchScale / kSampleRate) *
        dsp::EBIKE_GAIN_BODY;

    float dc = 0.0f;
    if (v.ebikeClickTimer > 0) {
        --v.ebikeClickTimer;
        dc = dsp::EBIKE_CLICK_AMP;
    }
    const float click = v.ebikeDcHp.process(dc);

    const float saturated =
        shapeAsym(mixed + click, dsp::EBIKE_SHAPE_K * driveScale, dsp::EBIKE_SHAPE_ASYM);
    float filtered = v.ebikeHp.process(saturated);
    filtered = v.ebikeLp2.process(v.ebikeLp1.process(filtered));
    filtered = v.ebikeNotch.process(filtered);
    const float sample = std::clamp(filtered * dsp::EBIKE_OUTPUT_GAIN,
                                    -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
    outL = sample;
    outR = sample;
}

void NativeHornEngine::renderMotorcycle(float pitchScale, float driveScale, float& outL,
                                        float& outR) {
    VoiceState& v = voices_;
    v.phMotoMod += 2.0f * static_cast<float>(kPi) * dsp::MOTO_FLUTTER_HZ / kSampleRate;
    if (v.phMotoMod >= 2.0f * static_cast<float>(kPi)) {
        v.phMotoMod -= 2.0f * static_cast<float>(kPi);
    }
    const float lfo = std::sin(v.phMotoMod);
    const float flutter = 1.0f + dsp::MOTO_AM_DEPTH * lfo;
    const float fmScale = 1.0f + dsp::MOTO_FM_DEPTH * lfo;
    const float mixed = tableMoto_.read(v.phMoto, dsp::MOTO_F * pitchScale * fmScale / kSampleRate);
    const float saturated =
        shapeAsym(mixed, dsp::MOTO_SHAPE_K * driveScale, dsp::MOTO_SHAPE_ASYM);
    float formed = v.motoFormant[0].process(saturated);
    formed = v.motoFormant[1].process(formed);
    // section 7 note: flutter AM rides after saturation and formants.
    const float focused = v.motoLp.process(v.motoHp.process(formed * flutter));
    const float sample = std::clamp(focused * dsp::MOTO_OUTPUT_GAIN,
                                    -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
    outL = sample;
    outR = sample;
}

void NativeHornEngine::renderCustom(float pitchScale, float driveScale, float brassBeta,
                                    float brassAsym, float& outL, float& outR) {
    VoiceState& v = voices_;
    const bool dual = customDual_.load() == 1;
    const float primarySample =
        v.customTable.read(v.phCustomA, customPrimarySm_ * pitchScale / kSampleRate);
    float mixedL;
    float mixedR;
    if (dual) {
        const float secondarySample = v.customTable.read(
            v.phCustomB, customPrimarySm_ * customRatioSm_ * pitchScale / kSampleRate);
        const float lowBody = primarySample * dsp::CUSTOM_PRIMARY_WEIGHT;
        const float highBody = secondarySample * dsp::CUSTOM_SECONDARY_WEIGHT;
        mixedL = lowBody * dsp::CAR_PAN_LOW[0] + highBody * dsp::CAR_PAN_HIGH[0];
        mixedR = lowBody * dsp::CAR_PAN_LOW[1] + highBody * dsp::CAR_PAN_HIGH[1];
    } else {
        mixedL = primarySample;
        mixedR = primarySample;
    }

    const float relay = v.customRelayBpf.process(whiteNoise(v.noiseState)) * v.customClickEnv;
    v.customClickEnv *= v.customRelayDecay;
    const float ring =
        (-v.customRingA1 * v.customRingY1 - v.customRingA2 * v.customRingY2) * v.customRingEnv;
    v.customRingY2 = v.customRingY1;
    v.customRingY1 = ring;
    v.customRingEnv *= v.customRingDecay;
    mixedL += relay + ring;
    mixedR += relay + ring;

    const float throatL = v.customThroatC * (v.customThroatYL + mixedL - v.customThroatXL);
    const float throatR = v.customThroatC * (v.customThroatYR + mixedR - v.customThroatXR);
    v.customThroatXL = mixedL;
    v.customThroatXR = mixedR;
    v.customThroatYL = throatL;
    v.customThroatYR = throatR;
    const float radL = v.customRadC * (v.customRadYL + throatL - v.customRadXL);
    const float radR = v.customRadC * (v.customRadYR + throatR - v.customRadXR);
    v.customRadXL = throatL;
    v.customRadXR = throatR;
    v.customRadYL = radL;
    v.customRadYR = radR;

    const float sat = customSaturationSm_;
    const float steepenedL = v.brassCustomL.process(radL, brassBeta + dsp::EXPR_BRASS_BETA_RANGE * sat);
    const float steepenedR = v.brassCustomR.process(radR, brassBeta + dsp::EXPR_BRASS_BETA_RANGE * sat);

    const float k = std::clamp(
        (dsp::CUSTOM_SHAPE_K_BASE + dsp::CUSTOM_SHAPE_K_RANGE * sat) * driveScale, 0.25f, 6.0f);
    float alpha = brassAsym + dsp::EXPR_BRASS_ASYM_RANGE * sat;
    if (alpha > 0.25f) alpha = 0.25f;
    outL = std::clamp(v.customLpL.process(shapeAsym(steepenedL, k, alpha)) * dsp::CUSTOM_OUTPUT_GAIN,
                      -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
    outR = std::clamp(v.customLpR.process(shapeAsym(steepenedR, k, alpha)) * dsp::CUSTOM_OUTPUT_GAIN,
                      -dsp::VOICE_MAX_OUTPUT, dsp::VOICE_MAX_OUTPUT);
}

// ---------------------------------------------------------------------------------------
// ISM tap precomputation (02 sections 3.1 / 4)
// ---------------------------------------------------------------------------------------
void NativeHornEngine::buildSceneTaps() {
    struct Geom {
        float lx, ly, lz;
        float rx, ry, rz;
        float sx, sy, sz;
        float rhoNegX, rhoPosX, rhoNegY, rhoPosY, rhoNegZ, rhoPosZ;
        int order;
    };
    // 02 section 4 room tables; identical to dsp/SceneSpecCatalog.kt (section 7 j).
    const Geom geoms[dsp::SCENE_COUNT] = {
        {12.0f, 40.0f, 15.0f, 6.5f, 20.0f, 1.2f, 6.5f, 12.0f, 0.75f,
         0.62f, 0.70f, 0.65f, 0.65f, 0.40f, 0.70f, 2},
        {8.0f, 40.0f, 5.5f, 3.4f, 20.0f, 1.2f, 3.4f, 10.0f, 0.75f,
         0.90f, 0.90f, 0.90f, 0.90f, 0.88f, 0.94f, 2},
        {150.0f, 200.0f, 60.0f, 0.0f, 100.0f, 1.2f, 0.0f, 92.0f, 0.75f,
         0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 1},
        {12.6f, 20.0f, 2.4f, 6.3f, 10.0f, 1.2f, 6.3f, 4.0f, 0.75f,
         0.90f, 0.90f, 0.90f, 0.90f, 0.88f, 0.92f, 2},
    };

    for (int s = 0; s < dsp::SCENE_COUNT; ++s) {
        SceneTaps& out = sceneTaps_[s];
        out.count = 0;
        const float lpAlpha = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) *
                                              dsp::SCENES[s].wallLpHz / kSampleRate);
        if (s == 2) {
            // OPEN_HIGHWAY: sparse far echoes (96/184 ms) as ordinary taps on dry history.
            for (int t = 0; t < 2; ++t) {
                IsmTapSpec& tap = out.taps[out.count++];
                tap.delayL = dsp::FAR_ECHO_DELAY[t];
                tap.delayR = dsp::FAR_ECHO_DELAY[t];
                tap.gainL = dsp::FAR_ECHO_GAIN_L[t];
                tap.gainR = dsp::FAR_ECHO_GAIN_R[t];
                tap.lpAlpha = lpAlpha;
            }
            continue;
        }

        const Geom& geo = geoms[s];
        const float sizes[3] = {geo.lx, geo.ly, geo.lz};
        const float source[3] = {geo.sx, geo.sy, geo.sz};
        float gainSum = 0.0f;

        for (int axis = 0; axis < 3; ++axis) {
            for (int n = -geo.order; n <= geo.order; ++n) {
                if (n == 0) continue;
                float image[3] = {geo.sx, geo.sy, geo.sz};
                image[axis] = 2.0f * n * sizes[axis] +
                    ((n % 2 == 0) ? source[axis] : -source[axis]);
                const float dx = image[0] - geo.rx;
                const float dy = image[1] - geo.ry;
                const float dz = image[2] - geo.rz;
                float distance = std::sqrt(dx * dx + dy * dy + dz * dz);
                if (distance < 0.5f) distance = 0.5f;
                const float rho = axis == 0 ? (image[0] < geo.rx ? geo.rhoNegX : geo.rhoPosX)
                                  : axis == 1 ? (image[1] < geo.ry ? geo.rhoNegY : geo.rhoPosY)
                                              : (image[2] < geo.rz ? geo.rhoNegZ : geo.rhoPosZ);
                const float gain = std::pow(rho, static_cast<float>(std::abs(n))) / distance;

                // Binauralisation (02 section 3.1; section 7 b/k): Woodworth ITD with toInt()
                // truncation clamped to 31 samples, plus a mild 6*|sin(az)| dB far-ear ILD.
                float azimuth = std::atan2(dx, std::fabs(dy));
                const float kHalfPi = static_cast<float>(kPi) * 0.5f;
                if (azimuth > kHalfPi) azimuth = kHalfPi;
                if (azimuth < -kHalfPi) azimuth = -kHalfPi;
                const float itdSeconds = (dsp::ISM_HEAD_RADIUS / dsp::ISM_SOUND_SPEED) *
                    (azimuth + std::sin(azimuth));
                int itdSamples = static_cast<int>(itdSeconds * kSampleRate);
                if (itdSamples > 31) itdSamples = 31;
                if (itdSamples < 0) itdSamples = 0;
                const float ildDb = 6.0f * std::fabs(std::sin(azimuth));
                const float farGain = gain * std::pow(10.0f, -ildDb / 20.0f);
                const bool toLeft = dx < 0.0f;

                if (out.count >= dsp::ISM_MAX_TAPS) break;
                IsmTapSpec& tap = out.taps[out.count++];
                const float base = distance / dsp::ISM_SOUND_SPEED * kSampleRate;
                tap.lpAlpha = lpAlpha;
                tap.gainL = toLeft ? gain : farGain;
                tap.gainR = toLeft ? farGain : gain;
                tap.delayL = toLeft ? base : base + static_cast<float>(itdSamples);
                tap.delayR = toLeft ? base + static_cast<float>(itdSamples) : base;
                gainSum += std::max(tap.gainL, tap.gainR);
            }
        }

        // 02 section 5 C2 budget, peak-based like SceneSpecCatalog.roomScene.
        float maxGain = 0.0f;
        for (int t = 0; t < out.count; ++t) {
            maxGain = std::max(maxGain, std::max(out.taps[t].gainL, out.taps[t].gainR));
        }
        float scale = 1.0f;
        if (maxGain > dsp::ISM_TAP_GAIN_MAX) scale = dsp::ISM_TAP_GAIN_MAX / maxGain;
        if (gainSum * scale > dsp::ISM_SUM_GAIN_MAX) scale = dsp::ISM_SUM_GAIN_MAX / gainSum;
        if (scale != 1.0f) {
            for (int t = 0; t < out.count; ++t) {
                out.taps[t].gainL *= scale;
                out.taps[t].gainR *= scale;
            }
        }
    }
}
void NativeHornEngine::buildCustomTable() {
    // Kotlin CustomHornSynth.harmonicWeights mirror (section 7 c): 12 terms, N_max truncated.
    const float richness = customRichness_.load();
    const float ratio = customRatio_.load();
    const float primary = customPrimary_.load();
    const float exponent = dsp::CUSTOM_DECAY_MAX - dsp::CUSTOM_DECAY_RANGE * richness;
    const float tilt = dsp::CUSTOM_TILT_BASE + dsp::CUSTOM_TILT_RANGE * richness;
    float gains[dsp::CUSTOM_HARMONIC_COUNT];
    for (int i = 0; i < dsp::CUSTOM_HARMONIC_COUNT; ++i) {
        gains[i] = i == 0 ? 1.0f : std::pow(static_cast<float>(i + 1), -exponent) * tilt;
    }
    const float fMax = std::max(primary, primary * ratio) * (1.0f + dsp::EXPR_PITCH_BEND);
    voices_.customTable.build(gains, dsp::CUSTOM_HARMONIC_COUNT, bandLimit(fMax));
}
void NativeHornEngine::updateLoudnessTarget() {
    float makeup = dsp::MAKEUP_CUSTOM;
    switch (activeType_) {
        case kTypeCar: makeup = dsp::MAKEUP_CAR; break;
        case kTypeTrain: makeup = dsp::MAKEUP_TRAIN; break;
        case kTypeEbike: makeup = dsp::MAKEUP_EBIKE; break;
        case kTypeMotorcycle: makeup = dsp::MAKEUP_MOTORCYCLE; break;
        default: makeup = dsp::MAKEUP_CUSTOM; break;
    }
    const int space = std::clamp(requestedSpace_.load(), 0, dsp::SCENE_COUNT - 1);
    float db = dsp::LOUD_SCENE_DB[space];
    if (modeTarget_ == dsp::MODE_SPEAKER_VBS) {
        db += dsp::LOUD_MODE_OFFSET_DB[std::clamp(activeType_, 0, 4)];
    }
    loudnessTarget_ = makeup * std::pow(10.0f, db / 20.0f) * dsp::MASTER_GAIN;
    protectionTarget_ = protection_.load() ? dsp::PROTECTION_GAIN : 1.0f;

    vbsInjectTarget_ = 0.0f;
    if (activeType_ == kTypeTrain) {
        vbsInjectTarget_ = 1.0f;
    } else if (activeType_ == kTypeCustom && customPrimarySm_ < dsp::VBS_INJECT_CUSTOM_FC) {
        vbsInjectTarget_ = 1.0f;
    }
}

// ---------------------------------------------------------------------------------------
// Render callback (contract sections 2 / 4)
// ---------------------------------------------------------------------------------------
oboe::DataCallbackResult NativeHornEngine::onAudioReady(oboe::AudioStream* stream,
                                                        void* audioData, int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    const auto startedAt = std::chrono::steady_clock::now();

    if (released_.load() || stream == nullptr) {
        std::memset(output, 0, sizeof(float) * 2 * static_cast<size_t>(numFrames));
        return oboe::DataCallbackResult::Continue;
    }
    if (!ftzDone_.exchange(true)) {
        enableFlushToZero();
    }

    const int frames = std::min(static_cast<int>(numFrames), kMaxFrames);

    const int command = command_.exchange(0);
    if (command == 3) {
        envelope_.reset();
        scene_.reset();
        tailRemaining_ = 0;
        pendingSwitch_ = false;
        pendingType_ = -1;
    } else if (command == 2) {
        envelope_.noteOff();
        const dsp::SceneSpec& scene = dsp::SCENES[std::clamp(requestedSpace_.load(), 0,
                                                             dsp::SCENE_COUNT - 1)];
        tailRemaining_ = (scene.tailMs * (kSampleRate / 1000) + kFramesPerCallback - 1) /
            kFramesPerCallback;
    } else if (command == 1) {
        tailRemaining_ = 0;
        switchType(requestedType_.load());
        if (!playing_.load()) envelope_.reset();
    }

    const int desiredType = requestedType_.load();
    if (desiredType != activeType_) {
        if (envelope_.isActive()) {
            pendingSwitch_ = true;
            pendingType_ = desiredType;
            envelope_.noteOff();
        } else {
            switchType(desiredType);
            if (!playing_.load()) envelope_.reset();
        }
    }
    if (pendingSwitch_ && envelope_.isSilent()) {
        switchType(pendingType_);
        pendingSwitch_ = false;
        pendingType_ = -1;
        if (!playing_.load()) envelope_.reset();
    }

    scene_.requestSpace(requestedSpace_.load(), sceneTaps_[requestedSpace_.load()]);

    const int requestedMode = std::clamp(outputMode_.load(), dsp::MODE_SPEAKER_VBS,
                                         dsp::MODE_HEADPHONE_FULL_RANGE);
    if (requestedMode != modeTarget_) {
        if (modeXfade_ < 1.0f && modeXfade_ >= 0.5f) modeCurrent_ = modeTarget_;
        modeTarget_ = requestedMode;
        modeXfade_ = 0.0f;
        modeXfadeInc_ = 1.0f / (dsp::TAU_MODE_MS * 0.001f * kSampleRate);
    }

    // block-rate parameter smoothing / coefficient recompute (01 section 2.1.2)
    const float aBlock = 1.0f - std::exp(-frames / (dsp::TAU_PARAM_MS * 0.001f * kSampleRate));
    customPrimarySm_ += aBlock * (customPrimary_.load() - customPrimarySm_);
    customRatioSm_ += aBlock * (customRatio_.load() - customRatioSm_);
    customRichnessSm_ += aBlock * (customRichness_.load() - customRichnessSm_);
    customSaturationSm_ += aBlock * (customSaturation_.load() - customSaturationSm_);
    const float releaseMs = customRelease_.load();
    voices_.customThroatC = std::exp(-2.0f * static_cast<float>(kPi) *
        std::clamp(dsp::CUSTOM_FC_SCALE * customPrimarySm_, dsp::CUSTOM_FC_MIN,
                   dsp::CUSTOM_FC_MAX) / kSampleRate);
    voices_.customRadC = std::exp(-2.0f * static_cast<float>(kPi) *
        (dsp::CUSTOM_MOUTH_HP_MAX -
         (dsp::CUSTOM_MOUTH_HP_MAX - dsp::CUSTOM_MOUTH_HP_MIN) * customRichnessSm_) / kSampleRate);
    const float customLpFc = dsp::CUSTOM_LP_BASE + dsp::CUSTOM_LP_RANGE * customRichnessSm_;
    voices_.customLpL.setLowPass(customLpFc, 0.707f);
    voices_.customLpR.setLowPass(customLpFc, 0.707f);
    voices_.customRelayDecay = std::exp(-1.0f / (kSampleRate * 0.003f));
    const float ringZeta = 0.01f + 0.04f * (releaseMs / 250.0f);
    const float ringTauOff = std::max(releaseMs / 2000.0f, 0.001f);
    const float ringPole = std::exp(-ringZeta * 2.0f * static_cast<float>(kPi) *
                                    dsp::CUSTOM_RING_F / kSampleRate);
    const float ringTheta = 2.0f * static_cast<float>(kPi) * dsp::CUSTOM_RING_F *
        std::sqrt(1.0f - ringZeta * ringZeta) / kSampleRate;
    voices_.customRingA1 = -2.0f * ringPole * std::cos(ringTheta);
    voices_.customRingA2 = ringPole * ringPole;
    voices_.customRingDecay = std::exp(-1.0f / (kSampleRate * ringTauOff));
    if (customTableDirty_.load(std::memory_order_relaxed) && envelope_.isSilent()) {
        buildCustomTable();
        customTableDirty_.store(false, std::memory_order_relaxed);
    }

    updateLoudnessTarget();

    const bool playing = playing_.load();
    if (playing && envelope_.isSilent() && tailRemaining_ == 0) {
        envelope_.noteOn();
    }

    const float targetExpression = expression_.load();
    const float aExpr = dsp::EXPR_SMOOTHING;
    const float aBrass = 1.0f - std::exp(-1.0f / (dsp::TAU_BRASS_MS * 0.001f * kSampleRate));
    const float aGain = 1.0f - std::exp(-1.0f / (dsp::TAU_GAIN_MS * 0.001f * kSampleRate));

    double sumL2 = 0.0;
    double sumR2 = 0.0;
    double sumLR = 0.0;
    int waveformIndex = waveformIndex_.load(std::memory_order_relaxed);

    for (int i = 0; i < frames; ++i) {
        // expression channels (five-channel mapping, 01 section 2.3.3)
        currentExpression_ += aExpr * (targetExpression - currentExpression_);
        const float brassTarget = dsp::EXPR_BRASS_BETA_BASE +
            dsp::EXPR_BRASS_BETA_RANGE * std::max(0.0f, currentExpression_);
        const float asymTarget = dsp::EXPR_BRASS_ASYM_BASE +
            dsp::EXPR_BRASS_ASYM_RANGE * std::max(0.0f, currentExpression_);
        brassBeta_ += aBrass * (brassTarget - brassBeta_);
        brassAsym_ += aBrass * (asymTarget - brassAsym_);
        const float pitchScale = 1.0f + currentExpression_ * dsp::EXPR_PITCH_BEND;
        const float driveScale = 1.0f + currentExpression_ * dsp::EXPR_DRIVE_RANGE;
        const float noiseScale = 1.0f + currentExpression_ * dsp::EXPR_NOISE_RANGE;

        // physical micro-transient triggers track envelope edges
        const int stage = envelope_.stage;
        if (stage == 1 && lastEnvelopeStage_ == 0) voiceNoteOn();
        if (stage == 3 && (lastEnvelopeStage_ == 1 || lastEnvelopeStage_ == 2)) voiceNoteOff();
        lastEnvelopeStage_ = stage;

        const float env = envelope_.next();

        float vL = 0.0f;
        float vR = 0.0f;
        renderVoices(pitchScale, driveScale, noiseScale, brassBeta_, brassAsym_, vL, vR);

        float sceneL = 0.0f;
        float sceneR = 0.0f;
        scene_.process(vL * env, vR * env, sceneL, sceneR);

        float aL = 0.0f;
        float aR = 0.0f;
        float bL = 0.0f;
        float bR = 0.0f;
        vbs_.process(sceneL, sceneR, vbsInject_, aL, aR);
        headphone_.process(sceneL, sceneR, bL, bR);

        float wSpeaker;
        float wHead;
        if (modeXfade_ >= 1.0f) {
            wSpeaker = modeCurrent_ == dsp::MODE_SPEAKER_VBS ? 1.0f : 0.0f;
            wHead = 1.0f - wSpeaker;
        } else {
            const float angle = 0.5f * static_cast<float>(kPi) * modeXfade_;
            const float gOld = std::cos(angle);
            const float gNew = std::sin(angle);
            if (modeCurrent_ == dsp::MODE_SPEAKER_VBS) {
                wSpeaker = gOld;
                wHead = gNew;
            } else {
                wSpeaker = gNew;
                wHead = gOld;
            }
            modeXfade_ += modeXfadeInc_;
            if (modeXfade_ >= 1.0f) {
                modeXfade_ = 1.0f;
                modeCurrent_ = modeTarget_;
            }
        }

        const float mixedL = wSpeaker * aL + wHead * bL;
        const float mixedR = wSpeaker * aR + wHead * bR;

        loudnessGain_ += aGain * (loudnessTarget_ - loudnessGain_);
        protectionGain_ += aGain * (protectionTarget_ - protectionGain_);
        vbsInject_ += aGain * (vbsInjectTarget_ - vbsInject_);

        const float staged = loudnessGain_ * protectionGain_;
        const float outL = output_[0].process(mixedL * staged);
        const float outR = output_[1].process(mixedR * staged);

        const float finalL =
            std::fabs(outL) < dsp::DENORMAL_EPS ? 0.0f : outL;
        const float finalR =
            std::fabs(outR) < dsp::DENORMAL_EPS ? 0.0f : outR;
        output[2 * i] = finalL;
        output[2 * i + 1] = finalR;

        sumL2 += static_cast<double>(finalL) * finalL;
        sumR2 += static_cast<double>(finalR) * finalR;
        sumLR += static_cast<double>(finalL) * finalR;
        waveformL_[waveformIndex % kWaveformSize].store(
            static_cast<int32_t>(finalL * 32767.0f), std::memory_order_relaxed);
        waveformR_[waveformIndex % kWaveformSize].store(
            static_cast<int32_t>(finalR * 32767.0f), std::memory_order_relaxed);
        ++waveformIndex;
    }

    if (frames < static_cast<int>(numFrames)) {
        std::memset(output + 2 * frames, 0,
                    sizeof(float) * 2 * static_cast<size_t>(numFrames - frames));
    }
    waveformIndex_.store(waveformIndex, std::memory_order_relaxed);

    if (!voices_.active && !playing && tailRemaining_ > 0) {
        --tailRemaining_;
    }

    scene_.endBlock();
    if (std::fabs(vbs_.nldMean) < dsp::DENORMAL_EPS) vbs_.nldMean = 0.0f;
    float* brassEnvs[] = {&voices_.brassCarLow.env, &voices_.brassCarHigh.env,
                          &voices_.brassTrainL.env, &voices_.brassTrainR.env,
                          &voices_.brassCustomL.env, &voices_.brassCustomR.env};
    for (float* env : brassEnvs) {
        if (std::fabs(*env) < dsp::DENORMAL_EPS) *env = 0.0f;
    }

    const float rmsL = frames > 0 ? static_cast<float>(std::sqrt(sumL2 / frames)) : 0.0f;
    const float rmsR = frames > 0 ? static_cast<float>(std::sqrt(sumR2 / frames)) : 0.0f;
    const double energy = std::sqrt(sumL2 * sumR2);
    float correlation = 1.0f;
    if (energy >= dsp::CORR_SILENCE_EPS) {
        correlation = std::clamp(static_cast<float>(sumLR / energy), -1.0f, 1.0f);
    }
    rmsBits_.store(floatBits(std::max(rmsL, rmsR)), std::memory_order_relaxed);
    rmsLeftBits_.store(floatBits(rmsL), std::memory_order_relaxed);
    rmsRightBits_.store(floatBits(rmsR), std::memory_order_relaxed);
    correlationBits_.store(floatBits(correlation), std::memory_order_relaxed);

    const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - startedAt);
    renderMicros_.store(static_cast<int>(elapsed.count()), std::memory_order_relaxed);
    playing_.store(playing, std::memory_order_relaxed);

    if (++telemetryCallbacks_ % 30 == 0) {
        const float latency = static_cast<float>(
            stream->getBufferSizeInFrames() * 1000.0 / kSampleRate +
            numFrames * 1000.0 / kSampleRate);
        latencyBits_.store(floatBits(latency), std::memory_order_relaxed);
        const auto xruns = stream->getXRunCount();
        if (xruns == oboe::Result::OK) {
            xrunCount_.store(xruns.value(), std::memory_order_relaxed);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------------------------------
// Telemetry readers
// ---------------------------------------------------------------------------------------
int NativeHornEngine::readTelemetry(float* destination, int capacity) {
    // Contract 00 section 5 slot layout, mirrored by OboeHornAudioEngine.kt:
    // [0] rms [1] latencyMs [2] xrun [3] renderMicros [4] isPlaying
    // [5] rmsL [6] rmsR [7] correlation [8] outputMode ordinal
    if (destination == nullptr || capacity < dsp::TELEMETRY_SLOTS) return 0;
    destination[0] = bitsFloat(rmsBits_.load(std::memory_order_relaxed));
    destination[1] = bitsFloat(latencyBits_.load(std::memory_order_relaxed));
    destination[2] = static_cast<float>(xrunCount_.load(std::memory_order_relaxed));
    destination[3] = static_cast<float>(renderMicros_.load(std::memory_order_relaxed));
    destination[4] = playing_.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    destination[5] = bitsFloat(rmsLeftBits_.load(std::memory_order_relaxed));
    destination[6] = bitsFloat(rmsRightBits_.load(std::memory_order_relaxed));
    destination[7] = bitsFloat(correlationBits_.load(std::memory_order_relaxed));
    destination[8] = static_cast<float>(outputMode_.load(std::memory_order_relaxed));
    return dsp::TELEMETRY_SLOTS;
}

int NativeHornEngine::readWaveform(float* destination, int capacity) {
    if (destination == nullptr || capacity <= 0) return 0;
    const int count = std::min(capacity, kWaveformSize);
    int index = waveformIndex_.load(std::memory_order_relaxed);
    for (int offset = 0; offset < count; ++offset) {
        const int source = ((index - count + offset) % kWaveformSize + kWaveformSize) %
            kWaveformSize;
        destination[offset] = static_cast<float>(
            waveformL_[source].load(std::memory_order_relaxed)) / 32768.0f;
    }
    return count;
}

int NativeHornEngine::readWaveformStereo(float* left, float* right, int capacity) {
    if (left == nullptr || right == nullptr || capacity <= 0) return 0;
    const int count = std::min(capacity, kWaveformSize);
    int index = waveformIndex_.load(std::memory_order_relaxed);
    for (int offset = 0; offset < count; ++offset) {
        const int source = ((index - count + offset) % kWaveformSize + kWaveformSize) %
            kWaveformSize;
        left[offset] = static_cast<float>(
            waveformL_[source].load(std::memory_order_relaxed)) / 32768.0f;
        right[offset] = static_cast<float>(
            waveformR_[source].load(std::memory_order_relaxed)) / 32768.0f;
    }
    return count;
}
