#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>

#include "dsp_constants.h"

// Stereo physical horn engine (frozen contract v3.6).
// Pipeline (contract section 4): physical exciter -> stereo placement -> master envelope
// -> scene processing (ISM + orthogonal FDN + ISO 9613-1 air) -> output mode
// (VBS speaker / headphone width) -> loudness feed-forward -> ear protection
// -> single StereoOutputStage -> interleaved float32 + telemetry split.
class NativeHornEngine : public oboe::AudioStreamDataCallback {
public:
    NativeHornEngine();
    ~NativeHornEngine() override;

    bool prepare();
    bool start();
    void stop();
    void panic();
    void release();

    void setType(int type);
    void setExpression(float expression);
    void setSpace(int space);
    void setProtection(bool enabled);
    void setOutputMode(int mode);
    void setCustomProfile(float primary, float ratio, bool dual, float richness,
                          float attackMs, float releaseMs, float saturation);

    int readTelemetry(float* destination, int capacity);
    int readWaveform(float* destination, int capacity);
    int readWaveformStereo(float* left, float* right, int capacity);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    static constexpr int kSampleRate = dsp::SAMPLE_RATE;
    static constexpr int kFramesPerCallback = dsp::FRAMES_PER_CALLBACK;
    static constexpr int kMaxFrames = dsp::MAX_CALLBACK_FRAMES;
    static constexpr int kWaveformSize = dsp::WAVEFORM_SIZE;
    static constexpr double kPi = 3.14159265358979323846;

    // Shared master envelope (contract C5: smooth attack/release semantics preserved).
    struct Envelope {
        float attackMs = 12.0f;
        float releaseMs = 14.0f;
        float level = 0.0f;
        float startLevel = 0.0f;
        int attackSamples = 1;
        int releaseSamples = 1;
        int elapsed = 0;
        int stage = 0;  // 0 idle, 1 attack, 2 sustain, 3 release

        void setTimes(float attack, float release);
        void noteOn();
        void noteOff();
        void reset();
        float next();
        bool isActive() const { return stage != 0; }
        bool isSilent() const { return stage == 0; }
    };

    // --- small DSP primitives -------------------------------------------------------------
    struct OnePole {
        float value = 0.0f;
        float alpha = 0.001f;
        float next(float target) {
            value += alpha * (target - value);
            return value;
        }
    };

    struct Biquad {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        float z1 = 0.0f, z2 = 0.0f;

        float process(float x) {
            const float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
        void reset() { z1 = z2 = 0.0f; }
        void setLowPass(float fc, float q);
        void setHighPass(float fc, float q);
        void setBandPass(float fc, float q);
        void setPeaking(float fc, float q, float gainDb);
    };

    struct OnePoleLp {
        float a = 0.1f;
        float state = 0.0f;
        float process(float x) {
            state += a * (x - state);
            return state;
        }
        void reset() { state = 0.0f; }
    };

    struct OnePoleHp {
        float a = 0.1f;
        float state = 0.0f;
        float process(float x) {
            state += a * (x - state);
            return x - state;
        }
        void reset() { state = 0.0f; }
    };

    // First-order shelf (02 section 3.3 bilinear construction, DC gain 1).
    struct OnePoleShelf {
        float b0 = 1.0f, b1 = 0.0f, a1 = 0.0f;
        float x1 = 0.0f, y1 = 0.0f;
        void set(float gain, float fc);
        float process(float x) {
            const float y = b0 * x + b1 * x1 - a1 * y1;
            x1 = x;
            y1 = y;
            return y;
        }
        void reset() { x1 = y1 = 0.0f; }
    };

    // 2048+1 point harmonic wavetable, built band-limited and peak-normalised.
    struct Wavetable {
        std::array<float, dsp::WAVETABLE_SIZE + 1> table{};
        void build(const float* gains, int count, int maxHarmonic);
        void clear() { table.fill(0.0f); }
        float read(float& phase, float inc) const {
            const float pos = phase * static_cast<float>(dsp::WAVETABLE_SIZE);
            int index = static_cast<int>(pos);
            const float frac = pos - static_cast<float>(index);
            index &= (dsp::WAVETABLE_SIZE - 1);
            const float value = table[index] + (table[index + 1] - table[index]) * frac;
            phase += inc;
            if (phase >= 1.0f) phase -= 1.0f;
            return value;
        }
    };

    // Amplitude-dependent fractional delay (01 section 2.3.1, main brassiness algorithm).
    struct Brassiness {
        std::array<float, 132> line{};
        int index = 0;
        float env = 0.0f;
        float envAlpha = 0.0042f;
        float d0 = 38.4f;

        void configure(float d0Ms);
        void reset();
        float process(float x, float beta);
    };

    // Feed-forward comb with one-pole colouring (02 sections 3.4 / 4.2).
    struct Comb {
        std::array<float, dsp::COMB_MAX_DELAY> buffer{};
        int index = 0;
        float delay = 1.0f;
        float rho = 0.0f;
        OnePoleLp lp;

        void reset();
        float process(float x);
    };

    // Orthogonal FDN bank (02 section 3.2): Hadamard feedback, per-line damping.
    struct FdnBank {
        std::array<std::array<float, dsp::FDN_MAX_DELAY>, 8> lines{};
        std::array<int, 8> index{};
        std::array<int, 8> length{};
        std::array<float, 8> damping{};
        std::array<float, 8> tapL{};   // normalised Hadamard output taps (SceneEngine mirror)
        std::array<float, 8> tapR{};
        float g = 0.5f;
        float d = 0.2f;
        float compensation = 1.0f;     // sqrt(2) for M=4 zero-weight lines (section 7 i)
        int activeLines = 4;

        void configure(const int* table, int lines, float gain, float dampingAmount);
        void reset();
        void process(float sendMono, float& lateL, float& lateR);
    };

    // Static ISM tap parameters, precomputed per scene at construction.
    struct IsmTapSpec {
        float delayL = 0.0f;
        float delayR = 0.0f;
        float gainL = 0.0f;
        float gainR = 0.0f;
        float lpAlpha = 0.5f;
    };

    struct SceneTaps {
        std::array<IsmTapSpec, dsp::ISM_MAX_TAPS> taps{};
        int count = 0;
    };

    // One complete scene path: ISM early reflections + ground/flutter comb + FDN + air + mix.
    struct ScenePath {
        std::array<IsmTapSpec, dsp::ISM_MAX_TAPS> taps{};
        std::array<float, dsp::ISM_MAX_TAPS> tapLpL{};
        std::array<float, dsp::ISM_MAX_TAPS> tapLpR{};
        int tapCount = 0;
        FdnBank fdn;
        std::array<Comb, 2> colour{};       // common wet colouring comb (C4)
        std::array<float, 2048> preDelay{}; // FDN send pre-delay ring (SceneEngine mirror)
        int preDelayIndex = 0;
        int preDelaySamples = 0;
        OnePoleShelf airL1, airL2, airR1, airR2;
        float widthW = 0.5f;
        float wetMix = 0.1f;
        int space = 0;

        void configure(int space, const SceneTaps& spec);
        void reset();
        void process(float dryL, float dryR,
                     const std::array<float, dsp::ISM_HISTORY>& histL,
                     const std::array<float, dsp::ISM_HISTORY>& histR, int histIdx,
                     float& outL, float& outR);
    };

    struct SceneStage {
        std::array<ScenePath, 2> paths{};
        int activePath = 0;
        int activeSpace = 0;
        int pendingSpace = -1;
        float xfadePos = 1.0f;
        float xfadeInc = 0.0f;
        std::array<float, dsp::ISM_HISTORY> historyL{};
        std::array<float, dsp::ISM_HISTORY> historyR{};
        int historyIndex = 0;
        float airG1 = 1.0f;
        float airG2 = 1.0f;
        OnePole airSmoother;
        // per-scene fitted shelf gains (02 section 3.3), filled at construction
        std::array<std::array<float, 2>, dsp::SCENE_COUNT> airByScene{};

        void reset();
        void requestSpace(int space, const SceneTaps& taps);
        void process(float dryL, float dryR, float& outL, float& outR);
        void endBlock();
    };

    // Speaker VBS chain (03 section 2.1): HP90 -> BPF 80-280 -> NLD -> 63-tap FIR -> mono mix.
    struct VbsChain {
        Biquad hpBiquad;
        OnePoleHp hpReal;
        Biquad bpHp, bpLp;
        OnePoleHp nldDc1, nldDc2;
        std::array<float, dsp::VBS_FIR_TAPS> fir{};
        std::array<float, dsp::VBS_FIR_TAPS> firHistory{};
        int firIndex = 0;
        std::array<float, 64> alignM{};
        std::array<float, 64> alignS{};
        int alignIndex = 0;
        float nldMean = 0.0f;
        float meanAlpha = 0.00105f;

        void configure();
        void reset();
        void process(float l, float r, float inject, float& outL, float& outR);
    };

    // Headphone full-range chain (03 section 1): M/S width + ILD tilt + S-only micro ITD.
    struct HeadphoneChain {
        OnePoleShelf ild;
        std::array<float, 32> sDelay{};
        int sIndex = 0;
        void configure();
        void reset();
        void process(float l, float r, float& outL, float& outR);
    };

    // Single StereoOutputStage (03 section 2.4): true peak -> look-ahead gain -> tanh -> +-0.98.
    struct OutputStage {
        std::array<float, dsp::TP_TAPS> tpHistory{};
        int tpIndex = 0;
        std::array<float, 512> lookahead{};
        int lookIndex = 0;
        float gain = 1.0f;
        float aAtt = 0.986f;
        float aRel = 0.9997f;

        void configure();
        void reset();
        float process(float x);
    };

    // --- voice bank ------------------------------------------------------------------------
    struct VoiceState {
        // oscillator phases (0..1); initial phases frozen per Kotlin mirrors
        float phCarLow = 0.0f;
        float phCarHigh = 0.0f;
        std::array<std::array<float, 2>, dsp::TRAIN_VOICES> phTrain{};
        float phEbikeA = dsp::PHASE_EBIKE[0];
        float phEbikeB = dsp::PHASE_EBIKE[1];
        float phEbikeBody = dsp::PHASE_EBIKE[2];
        float phMoto = dsp::PHASE_MOTO;
        float phMotoMod = 0.0f;

        // CAR: throat per body, then post-shape formant group + LP per channel
        OnePoleHp carThroatLow, carThroatHigh;
        std::array<Biquad, 3> carFormantL{};
        std::array<Biquad, 3> carFormantR{};
        Biquad carLpL, carLpR;
        OnePoleLp carNoiseLp;
        float carSwayPhase = 0.0f;
        float carBeatPhase = 0.0f;
        Biquad carRelayBpf;
        float carRelayEnv = 0.0f;
        float carRelayDelay = 0.0f;
        float ringY1 = 0.0f, ringY2 = 0.0f;
        float ringA1 = 0.0f, ringA2 = 0.0f;
        float ringEnv = 0.0f;

        // TRAIN valve state (delay countdown precedes pressure movement, Kotlin ValveVoice)
        std::array<float, dsp::TRAIN_VOICES> pressure{};
        std::array<float, dsp::TRAIN_VOICES> sag{};
        std::array<int, dsp::TRAIN_VOICES> valveDelay{};
        std::array<float, dsp::TRAIN_VOICES> valveTarget{};
        std::array<float, dsp::TRAIN_VOICES> tauOpenA{};
        std::array<float, dsp::TRAIN_VOICES> tauCloseA{};
        std::array<float, dsp::TRAIN_VOICES> trainPanL{};
        std::array<float, dsp::TRAIN_VOICES> trainPanR{};
        Biquad trainAirBpf1, trainAirBpf2;
        Biquad trainHpL, trainHpR, trainLpL, trainLpR;

        // EBIKE chain (shape sees mixed + DC click; glitch filters after shape)
        Biquad ebikeHp, ebikeLp1, ebikeLp2, ebikeNotch, ebikeDcHp;
        int ebikeClickTimer = 0;

        // MOTORCYCLE chain (flutter AM rides after saturation/formants)
        std::array<Biquad, 2> motoFormant{};
        Biquad motoHp, motoLp;

        // CUSTOM chain (wavetable rebuilt at silent boundaries on profile change)
        Wavetable customTable;
        float customTableKey = -1.0f;
        float phCustomA = dsp::PHASE_CUSTOM[0];
        float phCustomB = dsp::PHASE_CUSTOM[1];
        float customThroatYL = 0.0f, customThroatXL = 0.0f;
        float customThroatYR = 0.0f, customThroatXR = 0.0f;
        float customRadYL = 0.0f, customRadXL = 0.0f;
        float customRadYR = 0.0f, customRadXR = 0.0f;
        Biquad customLpL, customLpR, customRelayBpf;
        float customThroatC = 0.999f, customRadC = 0.999f, customRelayDecay = 0.993f;
        float customClickEnv = 0.0f;
        float customRingY1 = 0.0f, customRingY2 = 0.0f, customRingEnv = 0.0f;
        float customRingA1 = 0.0f, customRingA2 = 0.0f, customRingDecay = 1.0f;

        // brassiness: one instance per body (CAR) / per channel (TRAIN, CUSTOM)
        Brassiness brassCarLow, brassCarHigh, brassTrainL, brassTrainR, brassCustomL, brassCustomR;

        uint32_t noiseState = dsp::NOISE_SEED_CAR;
        bool active = false;
    };

    // wavetables
    Wavetable tableTrainChord_, tableTrainSub_;
    Wavetable tableEbikePulse_, tableEbikeBody_, tableMoto_;

    // --- engine internals -------------------------------------------------------------------
    bool openStreamLocked();
    void closeStream();
    void switchType(int type);
    void setEnvelopeTimes(int type);
    void resetOscillators();
    void voiceNoteOn();
    void voiceNoteOff();
    void buildCustomTable();

    void renderVoices(float pitchScale, float driveScale, float noiseScale,
                      float brassBeta, float brassAsym, float& outL, float& outR);
    void renderCar(float pitchScale, float driveScale, float brassBeta, float brassAsym,
                   float noiseScale, float& outL, float& outR);
    void renderTrain(float pitchScale, float driveScale, float brassBeta, float brassAsym,
                     float noiseScale, float& outL, float& outR);
    void renderEbike(float pitchScale, float driveScale, float& outL, float& outR);
    void renderMotorcycle(float pitchScale, float driveScale, float& outL, float& outR);
    void renderCustom(float pitchScale, float driveScale, float brassBeta,
                      float brassAsym, float& outL, float& outR);

    void buildSceneTaps();
    void updateLoudnessTarget();

    static float shapeAsym(float x, float k, float alpha);
    static int bandLimit(float fMaxHz);

    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> playing_{false};
    std::atomic<bool> released_{false};
    std::atomic<bool> ftzDone_{false};
    std::atomic<int> command_{0};
    std::atomic<int> requestedType_{0};
    std::atomic<int> requestedSpace_{0};
    std::atomic<int> protection_{0};
    std::atomic<int> outputMode_{0};
    std::atomic<float> expression_{0.0f};

    std::atomic<float> customPrimary_{620.0f};
    std::atomic<float> customRatio_{1.5f};
    std::atomic<int> customDual_{1};
    std::atomic<float> customRichness_{0.55f};
    std::atomic<float> customAttack_{20.0f};
    std::atomic<float> customRelease_{90.0f};
    std::atomic<float> customSaturation_{0.35f};
    std::atomic<bool> customTableDirty_{false};

    Envelope envelope_;
    VoiceState voices_;
    SceneStage scene_;
    VbsChain vbs_;
    HeadphoneChain headphone_;
    std::array<OutputStage, 2> output_;
    std::array<SceneTaps, dsp::SCENE_COUNT> sceneTaps_{};

    // smoothed control state
    float currentExpression_ = 0.0f;
    float brassBeta_ = dsp::EXPR_BRASS_BETA_BASE;
    float brassAsym_ = dsp::EXPR_BRASS_ASYM_BASE;
    float customPrimarySm_ = 620.0f;
    float customRatioSm_ = 1.5f;
    float customRichnessSm_ = 0.55f;
    float customSaturationSm_ = 0.35f;
    float vbsInject_ = 0.0f;
    float vbsInjectTarget_ = 0.0f;
    float loudnessGain_ = dsp::MAKEUP_CAR * dsp::MASTER_GAIN;
    float loudnessTarget_ = dsp::MAKEUP_CAR * dsp::MASTER_GAIN;
    float protectionGain_ = 1.0f;
    float protectionTarget_ = 1.0f;

    // mode crossfade
    int modeCurrent_ = 0;
    int modeTarget_ = 0;
    float modeXfade_ = 1.0f;
    float modeXfadeInc_ = 0.0f;

    int activeType_ = 0;
    int pendingType_ = -1;
    bool pendingSwitch_ = false;
    int tailRemaining_ = 0;
    int lastEnvelopeStage_ = 0;

    // output/telemetry work buffers (preallocated, render thread never allocates)
    std::array<float, 2 * kMaxFrames> workL_{};
    std::array<float, 2 * kMaxFrames> workR_{};

    int telemetryCallbacks_ = 0;
    std::atomic<int> rmsBits_{0};
    std::atomic<int> rmsLeftBits_{0};
    std::atomic<int> rmsRightBits_{0};
    std::atomic<int> correlationBits_{0};
    std::atomic<int> latencyBits_{0};
    std::atomic<int32_t> xrunCount_{0};
    std::atomic<int> renderMicros_{0};
    std::array<std::atomic<int32_t>, kWaveformSize> waveformL_{};
    std::array<std::atomic<int32_t>, kWaveformSize> waveformR_{};
    std::atomic<int> waveformIndex_{0};
};
