#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>

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
    void setCustomProfile(float primary, float ratio, bool dual, float richness,
                          float attackMs, float releaseMs, float saturation);

    int readTelemetry(float* destination, int capacity);
    int readWaveform(float* destination, int capacity);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    static constexpr int kSampleRate = 48000;
    static constexpr int kFramesPerCallback = 128;
    static constexpr int kWaveformSize = 256;
    static constexpr double kPi = 3.14159265358979323846;

    struct Envelope {
        float attackMs = 12.0f;
        float releaseMs = 14.0f;
        float level = 0.0f;
        float startLevel = 0.0f;
        int attackSamples = 1;
        int releaseSamples = 1;
        int elapsed = 0;
        int stage = 0; // 0 idle, 1 attack, 2 sustain, 3 release

        void setTimes(float attack, float release);
        void noteOn();
        void noteOff();
        void reset();
        float next();
        bool isActive() const { return stage != 0; }
        bool isSilent() const { return stage == 0; }
    };

    struct Reverb {
        std::array<std::vector<float>, 4> comb;
        std::array<int, 4> combIndex{};
        std::array<float, 4> combDamping{};
        std::array<std::vector<float>, 2> allPass;
        std::array<int, 2> allPassIndex{};
        std::vector<float> preDelay;
        int preDelayIndex = 0;
        int preDelaySamples = 384;
        float wet = 0.1f;
        float feedback = 0.58f;
        float damping = 0.35f;

        Reverb();
        void reset();
        void setSpace(int space);
        void process(float* buffer, int frames);
        int tailBlocks(int space) const;
    };

    bool openStreamLocked();
    void closeStream();
    void switchType(int type);
    void setEnvelopeTimes(int type);
    void resetOscillators();
    float renderVoice(float pitchScale, float driveScale, float noiseScale);
    float nextSample(double& phase, double frequency);
    float applyLimiter(float value);
    void publishTelemetry(int frames, int renderMicros);
    int spaceTailBlocks() const;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> playing_{false};
    std::atomic<bool> released_{false};
    std::atomic<int> command_{0};
    std::atomic<int> requestedType_{0};
    std::atomic<int> requestedSpace_{0};
    std::atomic<int> protection_{0};
    std::atomic<float> expression_{0.0f};

    std::atomic<float> customPrimary_{620.0f};
    std::atomic<float> customRatio_{1.5f};
    std::atomic<int> customDual_{1};
    std::atomic<float> customRichness_{0.55f};
    std::atomic<float> customAttack_{20.0f};
    std::atomic<float> customRelease_{90.0f};
    std::atomic<float> customSaturation_{0.35f};

    Envelope envelope_;
    Reverb reverb_;
    std::vector<float> mixBuffer_;
    int activeType_ = 0;
    int pendingType_ = -1;
    bool pendingSwitch_ = false;
    int tailRemaining_ = 0;

    double phaseCarLow_ = 0.0;
    double phaseCarHigh_ = 0.0;
    std::array<double, 6> phaseTrain_{};
    std::array<double, 2> phaseSub_{};
    double phaseEbikeA_ = 0.0;
    double phaseEbikeB_ = 0.0;
    double phaseEbikeBody_ = 0.0;
    double phaseMotorcycle_ = 0.0;
    double phaseCustomA_ = 0.0;
    double phaseCustomB_ = 0.0;
    double phaseMod_ = 0.0;
    float currentExpression_ = 0.0f;
    float lowPassState_ = 0.0f;
    float highPassState_ = 0.0f;
    uint32_t noiseState_ = 0x51A1C0DEu;
    int telemetryCallbacks_ = 0;

    std::atomic<int> rmsBits_{0};
    std::atomic<int> latencyBits_{0};
    std::atomic<int32_t> xrunCount_{0};
    std::atomic<int> renderMicros_{0};
    std::array<std::atomic<int32_t>, kWaveformSize> waveform_{};
    std::atomic<int> waveformIndex_{0};
};
