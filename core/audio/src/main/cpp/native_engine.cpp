#include "native_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

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

float softLimit(float value) {
    const float drive = 1.1f;
    return std::tanh(value * drive) / std::tanh(drive);
}

float voiceGain(int type) {
    switch (type) {
        case kTypeCar: return 0.47f;
        case kTypeTrain: return 0.52f;
        case kTypeEbike: return 0.18f;
        case kTypeMotorcycle: return 0.30f;
        default: return 0.22f;
    }
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
}  // namespace

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

NativeHornEngine::Reverb::Reverb() {
    const std::array<int, 4> combDelays = {1215, 1293, 1390, 1476};
    const std::array<int, 2> allPassDelays = {605, 480};
    for (size_t index = 0; index < comb.size(); ++index) {
        comb[index].assign(combDelays[index], 0.0f);
    }
    for (size_t index = 0; index < allPass.size(); ++index) {
        allPass[index].assign(allPassDelays[index], 0.0f);
    }
    preDelay.assign(2400, 0.0f);
}

void NativeHornEngine::Reverb::reset() {
    for (auto& buffer : comb) std::fill(buffer.begin(), buffer.end(), 0.0f);
    combIndex.fill(0);
    combDamping.fill(0.0f);
    for (auto& buffer : allPass) std::fill(buffer.begin(), buffer.end(), 0.0f);
    allPassIndex.fill(0);
    std::fill(preDelay.begin(), preDelay.end(), 0.0f);
    preDelayIndex = 0;
}

void NativeHornEngine::Reverb::setSpace(int space) {
    switch (space) {
        case 1:  // tunnel
            wet = 0.38f;
            feedback = 0.84f;
            damping = 0.22f;
            preDelaySamples = 864;
            break;
        case 2:  // open road
            wet = 0.22f;
            feedback = 0.66f;
            damping = 0.52f;
            preDelaySamples = 1632;
            break;
        default:  // street
            wet = 0.10f;
            feedback = 0.58f;
            damping = 0.35f;
            preDelaySamples = 384;
            break;
    }
}

int NativeHornEngine::Reverb::tailBlocks(int space) const {
    constexpr int msPerBlock = 3;
    switch (space) {
        case 1: return 1200 / msPerBlock;
        case 2: return 400 / msPerBlock;
        default: return 60 / msPerBlock;
    }
}

void NativeHornEngine::Reverb::process(float* buffer, int frames) {
    for (int sampleIndex = 0; sampleIndex < frames; ++sampleIndex) {
        const float dry = buffer[sampleIndex];
        preDelay[preDelayIndex] = dry;
        int readIndex = preDelayIndex - preDelaySamples;
        if (readIndex < 0) readIndex += static_cast<int>(preDelay.size());
        const float delayed = preDelay[readIndex];
        preDelayIndex = (preDelayIndex + 1) % static_cast<int>(preDelay.size());

        float wetSignal = 0.0f;
        for (size_t combIndexValue = 0; combIndexValue < comb.size(); ++combIndexValue) {
            auto& delayBuffer = comb[combIndexValue];
            const int index = combIndex[combIndexValue];
            const float delayedComb = delayBuffer[index];
            combDamping[combIndexValue] =
                delayedComb * (1.0f - damping) + combDamping[combIndexValue] * damping;
            delayBuffer[index] = std::clamp(
                delayed + combDamping[combIndexValue] * feedback, -4.0f, 4.0f);
            combIndex[combIndexValue] = (index + 1) % static_cast<int>(delayBuffer.size());
            wetSignal += delayedComb;
        }
        wetSignal *= 0.25f;

        for (size_t indexValue = 0; indexValue < allPass.size(); ++indexValue) {
            auto& delayBuffer = allPass[indexValue];
            const int index = allPassIndex[indexValue];
            const float buffered = delayBuffer[index];
            const float output = buffered - wetSignal;
            delayBuffer[index] = wetSignal + buffered * 0.5f;
            allPassIndex[indexValue] = (index + 1) % static_cast<int>(delayBuffer.size());
            wetSignal = output;
        }

        buffer[sampleIndex] = dry * (1.0f - wet * 0.5f) + wetSignal * wet;
    }
}

NativeHornEngine::NativeHornEngine() {
    mixBuffer_.resize(4096, 0.0f);
    envelope_.setTimes(12.0f, 14.0f);
    reverb_.setSpace(0);
    for (auto& value : waveform_) value.store(0);
}

NativeHornEngine::~NativeHornEngine() {
    release();
}

bool NativeHornEngine::prepare() {
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
            ->setChannelCount(1)
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
    std::fill(mixBuffer_.begin(), mixBuffer_.end(), 0.0f);
}

void NativeHornEngine::setType(int type) {
    requestedType_.store(std::clamp(type, kTypeCar, kTypeCustom));
}

void NativeHornEngine::setExpression(float expression) {
    expression_.store(std::clamp(expression, -1.0f, 1.0f));
}

void NativeHornEngine::setSpace(int space) {
    requestedSpace_.store(std::clamp(space, 0, 2));
}

void NativeHornEngine::setProtection(bool enabled) {
    protection_.store(enabled ? 1 : 0);
}

void NativeHornEngine::setCustomProfile(float primary, float ratio, bool dual, float richness,
                                        float attackMs, float releaseMs, float saturation) {
    customPrimary_.store(std::clamp(primary, 150.0f, 3000.0f));
    customRatio_.store(std::clamp(ratio, 0.5f, 2.0f));
    customDual_.store(dual ? 1 : 0);
    customRichness_.store(std::clamp(richness, 0.0f, 1.0f));
    customAttack_.store(std::clamp(attackMs, 3.0f, 120.0f));
    customRelease_.store(std::clamp(releaseMs, 5.0f, 250.0f));
    customSaturation_.store(std::clamp(saturation, 0.0f, 1.0f));
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
    phaseCarLow_ = 0.0;
    phaseCarHigh_ = 0.0;
    phaseTrain_.fill(0.0);
    phaseSub_.fill(0.0);
    phaseEbikeA_ = 0.0;
    phaseEbikeB_ = 0.0;
    phaseEbikeBody_ = 0.0;
    phaseMotorcycle_ = 0.0;
    phaseCustomA_ = 0.0;
    phaseCustomB_ = 0.0;
    phaseMod_ = 0.0;
    lowPassState_ = 0.0f;
    highPassState_ = 0.0f;
    noiseState_ = 0x51A1C0DEu;
}

float NativeHornEngine::nextSample(double& phase, double frequency) {
    const float value = static_cast<float>(std::sin(phase));
    phase += 2.0 * kPi * frequency / kSampleRate;
    if (phase >= 2.0 * kPi) phase -= 2.0 * kPi;
    return value;
}

float NativeHornEngine::renderVoice(float pitchScale, float driveScale, float noiseScale) {
    float mix = 0.0f;
    switch (activeType_) {
        case kTypeCar: {
            const double low = 400.0 * pitchScale;
            const double high = 500.0 * pitchScale;
            // Additive harmonic body: 4 harmonics per tone.
            double lowSample = 0.0;
            double highSample = 0.0;
            for (int harmonic = 1; harmonic <= 4; ++harmonic) {
                lowSample += std::sin(phaseCarLow_ * harmonic) / harmonic;
                highSample += std::sin(phaseCarHigh_ * harmonic) / harmonic;
            }
            phaseCarLow_ += 2.0 * kPi * low / kSampleRate;
            phaseCarHigh_ += 2.0 * kPi * high / kSampleRate;
            if (phaseCarLow_ >= 2.0 * kPi) phaseCarLow_ -= 2.0 * kPi;
            if (phaseCarHigh_ >= 2.0 * kPi) phaseCarHigh_ -= 2.0 * kPi;
            mix = static_cast<float>(lowSample * 0.32 + highSample * 0.28);
            lowPassState_ += 0.54f * (mix - lowPassState_);
            mix = lowPassState_ * 0.95f;
            break;
        }
        case kTypeTrain: {
            const std::array<double, 6> frequencies = {311.13, 311.13, 369.99, 369.99,
                                                       493.88, 493.88};
            const std::array<double, 6> detune = {0.996, 1.004, 0.996, 1.004, 0.996, 1.004};
            const std::array<double, 6> gains = {0.18, 0.14, 0.16, 0.13, 0.14, 0.11};
            for (size_t index = 0; index < frequencies.size(); ++index) {
                mix += nextSample(phaseTrain_[index], frequencies[index] * detune[index] *
                                                          pitchScale) *
                    static_cast<float>(gains[index]);
            }
            mix += nextSample(phaseSub_[0], 155.56 * pitchScale) * 0.10f;
            mix += nextSample(phaseSub_[1], 123.47 * pitchScale) * 0.06f;
            noiseState_ = noiseState_ * 1664525u + 1013904223u;
            const float noise =
                (static_cast<float>((noiseState_ >> 8) & 0xFFFFu) / 32768.0f - 1.0f) * 0.04f;
            mix += noise * noiseScale;
            lowPassState_ += 0.45f * (mix - lowPassState_);
            mix = lowPassState_;
            break;
        }
        case kTypeEbike: {
            const float a = nextSample(phaseEbikeA_, 2740.0 * pitchScale);
            const float b = nextSample(phaseEbikeB_, 2785.0 * pitchScale);
            const float body = nextSample(phaseEbikeBody_, 685.0 * pitchScale);
            mix = a * 0.42f + b * 0.28f + body * 0.10f;
            highPassState_ += 0.06f * (mix - highPassState_);
            const float highPassed = mix - highPassState_;
            lowPassState_ += 0.45f * (highPassed - lowPassState_);
            mix = lowPassState_;
            break;
        }
        case kTypeMotorcycle: {
            phaseMod_ += 2.0 * kPi * 29.0 / kSampleRate;
            if (phaseMod_ >= 2.0 * kPi) phaseMod_ -= 2.0 * kPi;
            const float flutter = 1.0f + 0.18f * static_cast<float>(std::sin(phaseMod_));
            float tone = 0.0f;
            const std::array<double, 6> harmonics = {1.0, 0.5, 0.36, 0.25, 0.17, 0.11};
            for (size_t index = 0; index < harmonics.size(); ++index) {
                tone += static_cast<float>(std::sin(phaseMotorcycle_ * (index + 1))) *
                    static_cast<float>(harmonics[index]);
            }
            phaseMotorcycle_ += 2.0 * kPi * 444.0 * pitchScale / kSampleRate;
            if (phaseMotorcycle_ >= 2.0 * kPi) phaseMotorcycle_ -= 2.0 * kPi;
            mix = tone * flutter * 0.38f;
            highPassState_ += 0.02f * (mix - highPassState_);
            lowPassState_ += 0.48f * ((mix - highPassState_) - lowPassState_);
            mix = lowPassState_;
            break;
        }
        default: {
            const float richness = customRichness_.load();
            const float primary = customPrimary_.load() * pitchScale;
            const float secondary = primary * customRatio_.load();
            const int harmonicCount = 2 + static_cast<int>(richness * 8.0f);
            double a = 0.0;
            double b = 0.0;
            for (int harmonic = 1; harmonic <= harmonicCount; ++harmonic) {
                const double amplitude = 1.0 / std::pow(static_cast<double>(harmonic), 1.45);
                a += std::sin(phaseCustomA_ * harmonic) * amplitude;
                b += std::sin(phaseCustomB_ * harmonic) * amplitude;
            }
            phaseCustomA_ += 2.0 * kPi * primary / kSampleRate;
            phaseCustomB_ += 2.0 * kPi * secondary / kSampleRate;
            if (phaseCustomA_ >= 2.0 * kPi) phaseCustomA_ -= 2.0 * kPi;
            if (phaseCustomB_ >= 2.0 * kPi) phaseCustomB_ -= 2.0 * kPi;
            mix = static_cast<float>(a * 0.38 * (0.4 + richness * 0.6));
            if (customDual_.load() == 1) {
                mix += static_cast<float>(b * 0.22 * (0.4 + richness * 0.6));
            }
            highPassState_ += 0.008f * (mix - highPassState_);
            lowPassState_ += 0.55f * ((mix - highPassState_) - lowPassState_);
            mix = lowPassState_;
            break;
        }
    }

    const float saturation = activeType_ == kTypeCustom
        ? 1.2f + customSaturation_.load() * 5.2f
        : 1.4f;
    return std::tanh(mix * saturation * driveScale) / std::tanh(saturation);
}

oboe::DataCallbackResult NativeHornEngine::onAudioReady(oboe::AudioStream* stream,
                                                        void* audioData, int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    const auto startedAt = std::chrono::steady_clock::now();
    const int frames = std::min(static_cast<int>(numFrames), static_cast<int>(mixBuffer_.size()));

    if (released_.load() || stream == nullptr) {
        std::memset(output, 0, sizeof(float) * numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    const int command = command_.exchange(0);
    if (command == 3) {
        envelope_.reset();
        reverb_.reset();
        tailRemaining_ = 0;
        pendingSwitch_ = false;
        pendingType_ = -1;
    } else if (command == 2) {
        envelope_.noteOff();
        tailRemaining_ = reverb_.tailBlocks(requestedSpace_.load());
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

    const bool playing = playing_.load();
    if (playing && envelope_.isSilent() && tailRemaining_ == 0) {
        envelope_.noteOn();
    }

    const float targetExpression = expression_.load();
    currentExpression_ += (targetExpression - currentExpression_) * 0.0025f;
    const float pitchScale = 1.0f + currentExpression_ * 0.08f;
    const float driveScale = 1.0f + currentExpression_ * 0.35f;
    const float noiseScale = 1.0f + currentExpression_ * 0.6f;
    const bool voiceActive = envelope_.isActive();

    for (int index = 0; index < frames; ++index) {
        if (voiceActive) {
            const float envelope = envelope_.next();
            float sample = renderVoice(pitchScale, driveScale, noiseScale) * envelope;
            const float gain = voiceGain(activeType_) *
                (protection_.load() == 1 ? 0.501f : 1.0f);
            mixBuffer_[index] = sample * gain;
        } else {
            mixBuffer_[index] = 0.0f;
        }
    }

    if (frames < static_cast<int>(numFrames)) {
        std::memset(output + frames, 0, sizeof(float) * (numFrames - frames));
    }

    reverb_.setSpace(requestedSpace_.load());
    reverb_.process(mixBuffer_.data(), frames);

    double sumSquares = 0.0;
    int waveformIndex = waveformIndex_.load(std::memory_order_relaxed);
    for (int index = 0; index < static_cast<int>(numFrames); ++index) {
        float sample = index < frames ? mixBuffer_[index] : 0.0f;
        sample = std::clamp(softLimit(sample), -0.98f, 0.98f);
        output[index] = sample;
        if (index < frames) {
            sumSquares += static_cast<double>(sample) * sample;
            waveform_[waveformIndex % kWaveformSize].store(
                static_cast<int32_t>(sample * 32767.0f), std::memory_order_relaxed);
            ++waveformIndex;
        }
    }
    waveformIndex_.store(waveformIndex, std::memory_order_relaxed);

    if (!voiceActive && !playing && tailRemaining_ > 0) {
        --tailRemaining_;
    }

    const float rms = numFrames > 0
        ? static_cast<float>(std::sqrt(sumSquares / numFrames))
        : 0.0f;
    rmsBits_.store(floatBits(rms), std::memory_order_relaxed);
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

int NativeHornEngine::readTelemetry(float* destination, int capacity) {
    if (destination == nullptr || capacity < 6) return 0;
    const int32_t rmsBits = rmsBits_.load(std::memory_order_relaxed);
    const int32_t latencyBits = latencyBits_.load(std::memory_order_relaxed);
    destination[0] = bitsFloat(rmsBits);
    destination[1] = bitsFloat(latencyBits);
    destination[2] = static_cast<float>(xrunCount_.load(std::memory_order_relaxed));
    destination[3] = static_cast<float>(renderMicros_.load(std::memory_order_relaxed));
    destination[4] = playing_.load(std::memory_order_relaxed) ? 1.0f : 0.0f;
    destination[5] = static_cast<float>(requestedType_.load());
    return 6;
}

int NativeHornEngine::readWaveform(float* destination, int capacity) {
    if (destination == nullptr || capacity <= 0) return 0;
    const int count = std::min(capacity, kWaveformSize);
    int index = waveformIndex_.load(std::memory_order_relaxed);
    for (int offset = 0; offset < count; ++offset) {
        const int source = ((index - count + offset) % kWaveformSize + kWaveformSize) %
            kWaveformSize;
        destination[offset] = static_cast<float>(
            waveform_[source].load(std::memory_order_relaxed)) / 32768.0f;
    }
    return count;
}

int NativeHornEngine::spaceTailBlocks() const {
    return reverb_.tailBlocks(requestedSpace_.load());
}
