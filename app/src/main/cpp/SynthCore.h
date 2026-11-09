#pragma once

#include <cmath>
#include <array>
#include <atomic>
#include <mutex>
#include <memory>
#include <algorithm>
#include <cstdint>

namespace hichord {

constexpr int kMaxVoices = 16;
static_assert(std::atomic<size_t>::is_always_lock_free);
static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr int kDelayBufferSize = 96000; // До 2 секунд при 48 кГц

enum class Waveform : int {
    SINE = 0,
    SAW = 1,
    SQUARE = 2,
    TRIANGLE = 3,
    FM_WARM = 4,
    FM_EP = 5,
    ORGAN = 6,
    PAD_DETUNE = 7
};

struct PresetParams {
    Waveform waveform;
    float attack;
    float decay;
    float sustain;
    float release;
    float cutoffHz;
    float resonance;
    float fmIndex;
    float fmRatio;
    float detune;
    float chorusMix;
    float delayMix;
    float reverbMix;
};

enum class EnvStage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE };

struct Voice {
    bool active = false;
    int midiNote = -1;
    float velocity = 0.0f;
    float freq = 440.0f;
    float targetFreq = 440.0f;
    float phase1 = 0.0f;
    float phase2 = 0.0f;
    float subPhase = 0.0f;
    float envLevel = 0.0f;
    EnvStage stage = EnvStage::IDLE;
    uint64_t age = 0;

    // Состояние фильтра Чемберлена (SVF) на каждый голос
    float ic1eq = 0.0f;
    float ic2eq = 0.0f;
};

class SynthCore {
public:
    SynthCore() {
        setupPresets();
        applyPreset(0);
        delayBufferL_.fill(0.0f);
        delayBufferR_.fill(0.0f);
        reverbComb1_.fill(0.0f);
        reverbComb2_.fill(0.0f);
        reverbComb3_.fill(0.0f);
        reverbComb4_.fill(0.0f);
    }

    // Call only after the audio consumer has stopped.
    void reset() {
        std::lock_guard<std::mutex> lock(producerMutex_);
        readIndex_.store(writeIndex_.load());
        panic_.store(false);
        for (auto& voice : voices_) voice = Voice{};
        delayBufferL_.fill(0.0f);
        delayBufferR_.fill(0.0f);
        reverbComb1_.fill(0.0f);
        reverbComb2_.fill(0.0f);
        reverbComb3_.fill(0.0f);
        reverbComb4_.fill(0.0f);
    }

    // JNI producers are serialized here. The real-time consumer never locks.
    void noteOn(int note, float velocity) {
        if (note < 0 || note > 127 || !std::isfinite(velocity)) return;
        Command command{};
        command.type = CommandType::On;
        command.notes[0] = note;
        command.velocity = std::clamp(velocity, 0.0f, 1.0f);
        enqueue(command);
    }

    void noteOff(int note) {
        Command command{};
        command.type = CommandType::Off;
        command.notes[0] = note;
        enqueue(command);
    }

    void setChordNotes(const int* notes, int count, float velocity) {
        if (!notes || !std::isfinite(velocity)) return;
        Command command{};
        command.type = CommandType::Chord;
        command.velocity = std::clamp(velocity, 0.0f, 1.0f);
        for (int i = 0; i < std::min(count, kMaxVoices); ++i) {
            if (notes[i] >= 0 && notes[i] <= 127)
                command.notes[command.count++] = notes[i];
        }
        enqueue(command);
    }

    void allNotesOff() {
        Command command{};
        command.type = CommandType::AllOff;
        enqueue(command);
    }

    void applyPreset(int index) {
        int safeIdx = std::clamp(index, 0, static_cast<int>(presets_.size()) - 1);
        const auto& p = presets_[safeIdx];
        waveform_.store(static_cast<int>(p.waveform));
        attack_.store(p.attack);
        decay_.store(p.decay);
        sustain_.store(p.sustain);
        release_.store(p.release);
        cutoffHz_.store(p.cutoffHz);
        resonance_.store(p.resonance);
        fmIndex_.store(p.fmIndex);
        fmRatio_.store(p.fmRatio);
        detune_.store(p.detune);
        chorusMix_.store(p.chorusMix);
        delayMix_.store(p.delayMix);
        reverbMix_.store(p.reverbMix);
    }

    void setFilterCutoff(float cutoffHz) {
        cutoffHz_.store(std::clamp(std::isfinite(cutoffHz) ? cutoffHz : 0.0f, 80.0f, 16000.0f));
    }

    void setFilterResonance(float q) {
        resonance_.store(std::clamp(std::isfinite(q) ? q : 0.0f, 0.5f, 8.0f));
    }

    void setReverbMix(float mix) {
        reverbMix_.store(std::clamp(std::isfinite(mix) ? mix : 0.0f, 0.0f, 0.85f));
    }

    void setDelayMix(float mix) {
        delayMix_.store(std::clamp(std::isfinite(mix) ? mix : 0.0f, 0.0f, 0.75f));
    }

    void setPitchBendSemitones(float semitones) {
        pitchBendSemitones_.store(std::clamp(std::isfinite(semitones) ? semitones : 0.0f, -12.0f, 12.0f));
    }

    void setVibratoDepth(float depth) {
        vibratoDepth_.store(std::clamp(std::isfinite(depth) ? depth : 0.0f, 0.0f, 1.0f));
    }

    void render(float* out, int32_t numFrames, float sr) {
        drainCommands();
        const float invSr = 1.0f / sr;

        const Waveform wf = static_cast<Waveform>(waveform_.load());
        const float attTime = std::max(0.002f, attack_.load());
        const float decTime = std::max(0.005f, decay_.load());
        const float susLevel = sustain_.load();
        const float relTime = std::max(0.005f, release_.load());

        const float attStep = 1.0f / (attTime * sr);
        const float decStep = (1.0f - susLevel) / (decTime * sr);
        const float relStep = 1.0f / (relTime * sr);

        const float targetCutoff = cutoffHz_.load();
        const float resQ = resonance_.load();
        const float fmIdx = fmIndex_.load();
        const float fmRat = fmRatio_.load();
        const float detuneAmt = detune_.load();
        const float cMix = chorusMix_.load();
        const float dMix = delayMix_.load();
        const float rMix = reverbMix_.load();
        const float bendRatio = std::pow(2.0f, pitchBendSemitones_.load() / 12.0f);
        const float vibDepth = vibratoDepth_.load();

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            smoothedCutoff_ += 0.004f * (targetCutoff - smoothedCutoff_);
            smoothedBend_ += 0.008f * (bendRatio - smoothedBend_);

            lfoPhase_ += 5.5f * invSr;
            if (lfoPhase_ >= 1.0f) lfoPhase_ -= 1.0f;
            float vibratoMod = 1.0f + std::sin(lfoPhase_ * kTwoPi) * (vibDepth * 0.015f);

            chorusPhase_ += 0.75f * invSr;
            if (chorusPhase_ >= 1.0f) chorusPhase_ -= 1.0f;

            float mixL = 0.0f;
            float mixR = 0.0f;

            const float g = std::tan(3.14159265f * std::min(smoothedCutoff_, sr * 0.42f) * invSr);
            const float k = 1.0f / resQ;
            const float a1 = 1.0f / (1.0f + g * (g + k));
            const float a2 = g * a1;
            const float a3 = g * a2;

            for (auto& v : voices_) {
                if (!v.active) continue;

                switch (v.stage) {
                    case EnvStage::ATTACK:
                        v.envLevel += attStep;
                        if (v.envLevel >= 1.0f) {
                            v.envLevel = 1.0f;
                            v.stage = EnvStage::DECAY;
                        }
                        break;
                    case EnvStage::DECAY:
                        v.envLevel -= decStep;
                        if (v.envLevel <= susLevel) {
                            v.envLevel = susLevel;
                            v.stage = EnvStage::SUSTAIN;
                        }
                        break;
                    case EnvStage::SUSTAIN:
                        v.envLevel = susLevel;
                        break;
                    case EnvStage::RELEASE:
                        v.envLevel -= relStep;
                        if (v.envLevel <= 0.0001f) {
                            v.envLevel = 0.0f;
                            v.active = false;
                            v.stage = EnvStage::IDLE;
                            continue;
                        }
                        break;
                    case EnvStage::IDLE:
                        v.active = false;
                        continue;
                }

                v.freq += 0.02f * (v.targetFreq - v.freq);
                float currentFreq = std::min(v.freq * smoothedBend_ * vibratoMod, sr * 0.45f);
                float dt1 = currentFreq * invSr;
                float dt2 = (currentFreq * (1.0f + detuneAmt)) * invSr;

                v.phase1 += dt1;
                v.phase1 -= std::floor(v.phase1);

                v.phase2 += (wf == Waveform::FM_WARM || wf == Waveform::FM_EP)
                            ? (currentFreq * fmRat * invSr)
                            : dt2;
                v.phase2 -= std::floor(v.phase2);

                v.subPhase += dt1 * 0.5f;
                v.subPhase -= std::floor(v.subPhase);
                float sample = 0.0f;
                switch (wf) {
                    case Waveform::SINE:
                        sample = std::sin(v.phase1 * kTwoPi);
                        break;
                    case Waveform::SAW:
                        sample = (2.0f * v.phase1 - 1.0f) - polyBlep(v.phase1, dt1);
                        if (detuneAmt > 0.0005f) {
                            float s2 = (2.0f * v.phase2 - 1.0f) - polyBlep(v.phase2, dt2);
                            sample = 0.55f * (sample + s2);
                        }
                        break;
                    case Waveform::SQUARE: {
                        float sq = (v.phase1 < 0.5f ? 1.0f : -1.0f);
                        sq += polyBlep(v.phase1, dt1);
                        float p2 = v.phase1 + 0.5f;
                        if (p2 >= 1.0f) p2 -= 1.0f;
                        sq -= polyBlep(p2, dt1);
                        sample = sq * 0.7f;
                        break;
                    }
                    case Waveform::TRIANGLE:
                        sample = 1.0f - 4.0f * std::fabs(std::round(v.phase1 - 0.25f) - (v.phase1 - 0.25f));
                        break;
                    case Waveform::FM_WARM: {
                        float mod = std::sin(v.phase2 * kTwoPi) * fmIdx * (0.4f + 0.6f * v.envLevel);
                        sample = std::sin(v.phase1 * kTwoPi + mod);
                        break;
                    }
                    case Waveform::FM_EP: {
                        float modEnv = v.envLevel * v.envLevel;
                        float mod = std::sin(v.phase2 * kTwoPi) * fmIdx * modEnv;
                        float tine = std::sin(v.phase2 * 2.0f * kTwoPi) * 0.18f * modEnv;
                        sample = std::sin(v.phase1 * kTwoPi + mod) + tine;
                        break;
                    }
                    case Waveform::ORGAN: {
                        sample = 0.50f * std::sin(v.phase1 * kTwoPi)
                               + 0.28f * std::sin(v.phase1 * 2.0f * kTwoPi)
                               + 0.15f * std::sin(v.phase1 * 3.0f * kTwoPi)
                               + 0.10f * std::sin(v.phase1 * 4.0f * kTwoPi);
                        break;
                    }
                    case Waveform::PAD_DETUNE: {
                        float s1 = (2.0f * v.phase1 - 1.0f) - polyBlep(v.phase1, dt1);
                        float s2 = (2.0f * v.phase2 - 1.0f) - polyBlep(v.phase2, dt2);
                        float sub = std::sin(v.subPhase * kTwoPi) * 0.4f;
                        sample = (s1 + s2) * 0.42f + sub;
                        break;
                    }
                }

                // Резонансный фильтр нижних частот (Zero-Delay Feedback SVF)
                float v3 = sample - v.ic2eq;
                float v1 = a1 * v.ic1eq + a2 * v3;
                float v2 = v.ic2eq + a2 * v.ic1eq + a3 * v3;
                v.ic1eq = 2.0f * v1 - v.ic1eq;
                v.ic2eq = 2.0f * v2 - v.ic2eq;
                float filtered = v2;

                float voiceOut = filtered * v.envLevel * v.velocity * 0.22f;
                float pan = std::clamp((v.midiNote - 60.0f) / 36.0f, -0.45f, 0.45f);
                mixL += voiceOut * (1.0f - pan);
                mixR += voiceOut * (1.0f + pan);
            }

            int readOffset = std::clamp(static_cast<int>(sr * 0.28f), 1, kDelayBufferSize - 1);
            int readIdx = (delayWriteIdx_ - readOffset + kDelayBufferSize) % kDelayBufferSize;

            int chorusOffset = static_cast<int>((0.012f + 0.003f * std::sin(chorusPhase_ * kTwoPi)) * sr);
            int chorusIdx = (delayWriteIdx_ - chorusOffset + kDelayBufferSize) % kDelayBufferSize;

            float chorusL = delayBufferL_[chorusIdx];
            float chorusR = delayBufferR_[chorusIdx];

            float delayedL = delayBufferL_[readIdx];
            float delayedR = delayBufferR_[readIdx];

            delayBufferL_[delayWriteIdx_] = mixL + delayedR * 0.36f;
            delayBufferR_[delayWriteIdx_] = mixR + delayedL * 0.36f;
            delayWriteIdx_ = (delayWriteIdx_ + 1) % kDelayBufferSize;

            float postFxL = mixL + chorusL * cMix + delayedL * dMix;
            float postFxR = mixR + chorusR * cMix + delayedR * dMix;

            float revIn = (postFxL + postFxR) * 0.5f;
            float r1 = reverbComb1_[revIdx1_];
            reverbComb1_[revIdx1_] = revIn + r1 * 0.78f;
            revIdx1_ = (revIdx1_ + 1) % static_cast<int>(reverbComb1_.size());

            float r2 = reverbComb2_[revIdx2_];
            reverbComb2_[revIdx2_] = revIn + r2 * 0.81f;
            revIdx2_ = (revIdx2_ + 1) % static_cast<int>(reverbComb2_.size());

            float r3 = reverbComb3_[revIdx3_];
            reverbComb3_[revIdx3_] = revIn + r3 * 0.83f;
            revIdx3_ = (revIdx3_ + 1) % static_cast<int>(reverbComb3_.size());

            float r4 = reverbComb4_[revIdx4_];
            reverbComb4_[revIdx4_] = revIn + r4 * 0.85f;
            revIdx4_ = (revIdx4_ + 1) % static_cast<int>(reverbComb4_.size());

            float revWetL = (r1 + r3) * 0.25f;
            float revWetR = (r2 + r4) * 0.25f;

            float finalL = postFxL * (1.0f - rMix * 0.35f) + revWetL * rMix;
            float finalR = postFxR * (1.0f - rMix * 0.35f) + revWetR * rMix;

            out[frame * 2]     = std::tanh(finalL * 1.15f);
            out[frame * 2 + 1] = std::tanh(finalR * 1.15f);
        }


    }

private:
    static inline float polyBlep(float t, float dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.0f;
        } else if (t > 1.0f - dt) {
            t = (t - 1.0f) / dt;
            return t * t + t + t + 1.0f;
        }
        return 0.0f;
    }

    void triggerVoiceInternal(int midiNote, float velocity) {
        float freq = 440.0f * std::pow(2.0f, (midiNote - 69.0f) / 12.0f);
        Voice* chosen = nullptr;
        for (auto& v : voices_) {
            if (v.active && v.midiNote == midiNote) { chosen = &v; break; }
        }
        if (!chosen) for (auto& v : voices_) {
            if (!v.active) { chosen = &v; break; }
        }
        if (!chosen) for (auto& v : voices_) {
            if (v.stage == EnvStage::RELEASE && (!chosen || v.age < chosen->age)) chosen = &v;
        }
        if (!chosen) for (auto& v : voices_) {
            if (!chosen || v.age < chosen->age) chosen = &v;
        }
        if (chosen) {
            if (!chosen->active) *chosen = Voice{};
            chosen->active = true;
            chosen->midiNote = midiNote;
            chosen->velocity = velocity;
            chosen->freq = freq;
            chosen->targetFreq = freq;
            chosen->stage = EnvStage::ATTACK;
            chosen->age = ++voiceCounter_;
        }
    }

    void setupPresets() {
        presets_ = {{
            {Waveform::FM_WARM, 0.008f, 0.65f, 0.35f, 0.35f, 3400.0f, 0.85f, 1.45f, 2.0f, 0.002f, 0.25f, 0.12f, 0.28f},
            {Waveform::FM_EP,   0.005f, 0.85f, 0.28f, 0.40f, 4200.0f, 0.90f, 1.90f, 1.0f, 0.003f, 0.35f, 0.15f, 0.30f},
            {Waveform::PAD_DETUNE, 0.22f, 0.90f, 0.80f, 0.75f, 1850.0f, 1.35f, 0.0f, 1.0f, 0.007f, 0.45f, 0.25f, 0.48f},
            {Waveform::SAW,     0.025f, 0.50f, 0.65f, 0.45f, 2900.0f, 1.60f, 0.0f, 1.0f, 0.005f, 0.35f, 0.28f, 0.42f},
            {Waveform::ORGAN,   0.006f, 0.15f, 0.92f, 0.12f, 5200.0f, 0.75f, 0.0f, 1.0f, 0.002f, 0.30f, 0.08f, 0.25f},
            {Waveform::TRIANGLE, 0.015f, 0.45f, 0.40f, 0.30f, 1450.0f, 1.20f, 0.0f, 1.0f, 0.006f, 0.40f, 0.20f, 0.32f},
            {Waveform::FM_WARM, 0.003f, 1.10f, 0.10f, 0.85f, 7500.0f, 0.90f, 2.80f, 3.5f, 0.003f, 0.25f, 0.32f, 0.50f},
            {Waveform::SQUARE,  0.003f, 0.25f, 0.55f, 0.15f, 4800.0f, 1.10f, 0.0f, 1.0f, 0.001f, 0.10f, 0.22f, 0.15f}
        }};
    }

    enum class CommandType { On, Off, Chord, AllOff };
    struct Command {
        CommandType type = CommandType::AllOff;
        std::array<int, kMaxVoices> notes{};
        int count = 0;
        float velocity = 0.85f;
    };
    static constexpr size_t kCommandCapacity = 256;
    std::array<Command, kCommandCapacity> commands_{};
    std::atomic<size_t> writeIndex_{0}, readIndex_{0};
    std::atomic<bool> panic_{false};
    std::mutex producerMutex_;

    void enqueue(const Command& command) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        const auto write = writeIndex_.load(std::memory_order_relaxed);
        const auto next = (write + 1) % kCommandCapacity;
        if (next == readIndex_.load(std::memory_order_acquire)) {
            // Overflow must never leave a held note stuck.
            panic_.store(true, std::memory_order_release);
            return;
        }
        commands_[write] = command;
        writeIndex_.store(next, std::memory_order_release);
    }

    void releaseVoices() {
        for (auto& voice : voices_) if (voice.active) voice.stage = EnvStage::RELEASE;
    }

    void drainCommands() {
        auto read = readIndex_.load(std::memory_order_relaxed);
        const auto end = writeIndex_.load(std::memory_order_acquire);
        while (read != end) {
            const auto& command = commands_[read];
            switch (command.type) {
                case CommandType::On:
                    triggerVoiceInternal(command.notes[0], command.velocity);
                    break;
                case CommandType::Off:
                    for (auto& voice : voices_)
                        if (voice.active && voice.midiNote == command.notes[0])
                            voice.stage = EnvStage::RELEASE;
                    break;
                case CommandType::AllOff: releaseVoices(); break;
                case CommandType::Chord:
                    for (auto& voice : voices_) {
                        if (std::find(command.notes.begin(), command.notes.begin() + command.count,
                                      voice.midiNote) == command.notes.begin() + command.count)
                            if (voice.active) voice.stage = EnvStage::RELEASE;
                    }
                    for (int i = 0; i < command.count; ++i) {
                        bool held = false;
                        for (const auto& voice : voices_)
                            if (voice.active && voice.midiNote == command.notes[i] &&
                                voice.stage != EnvStage::RELEASE) held = true;
                        if (!held) triggerVoiceInternal(command.notes[i], command.velocity);
                    }
                    break;
            }
            read = (read + 1) % kCommandCapacity;
        }
        readIndex_.store(read, std::memory_order_release);
        if (panic_.exchange(false, std::memory_order_acq_rel)) releaseVoices();
    }

    std::array<Voice, kMaxVoices> voices_{};
    std::array<PresetParams, 8> presets_{};
    uint64_t voiceCounter_ = 0;

    std::atomic<int> waveform_{4};
    std::atomic<float> attack_{0.008f};
    std::atomic<float> decay_{0.65f};
    std::atomic<float> sustain_{0.35f};
    std::atomic<float> release_{0.35f};
    std::atomic<float> cutoffHz_{3400.0f};
    std::atomic<float> resonance_{0.85f};
    std::atomic<float> fmIndex_{1.45f};
    std::atomic<float> fmRatio_{2.0f};
    std::atomic<float> detune_{0.002f};
    std::atomic<float> chorusMix_{0.25f};
    std::atomic<float> delayMix_{0.12f};
    std::atomic<float> reverbMix_{0.28f};
    std::atomic<float> pitchBendSemitones_{0.0f};
    std::atomic<float> vibratoDepth_{0.0f};

    float smoothedCutoff_ = 3400.0f;
    float smoothedBend_ = 1.0f;
    float lfoPhase_ = 0.0f;
    float chorusPhase_ = 0.0f;

    std::array<float, kDelayBufferSize> delayBufferL_{};
    std::array<float, kDelayBufferSize> delayBufferR_{};
    int delayWriteIdx_ = 0;

    std::array<float, 1557> reverbComb1_{};
    std::array<float, 1617> reverbComb2_{};
    std::array<float, 1491> reverbComb3_{};
    std::array<float, 1422> reverbComb4_{};
    int revIdx1_ = 0, revIdx2_ = 0, revIdx3_ = 0, revIdx4_ = 0;
};

} // namespace hichord
