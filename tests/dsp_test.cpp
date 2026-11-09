#include "SynthCore.h"
#include <iostream>
#include <thread>
#include <limits>
#include <stdexcept>

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

double render(hichord::SynthCore& synth, float sr, int blocks) {
    std::array<float, 512> buffer{};
    double energy = 0;
    for (int block = 0; block < blocks; ++block) {
        synth.render(buffer.data(), 256, sr);
        for (float sample : buffer) {
            require(std::isfinite(sample), "non-finite output");
            require(std::abs(sample) <= 1.0f, "output exceeds limiter");
            energy += sample * sample;
        }
    }
    return energy;
}

int main() {
    try {
        // Heap allocation: delay lines intentionally exceed a small thread stack.
        auto synth = std::make_unique<hichord::SynthCore>();
        for (float sr : {44100.0f, 48000.0f, 96000.0f, 192000.0f}) {
            for (int preset = 0; preset < 8; ++preset) {
                synth->reset();
                synth->applyPreset(preset);
                require(render(*synth, sr, 2) == 0, "reset must be silent");
                const int chord[] = {48, 60, 64, 67};
                synth->setChordNotes(chord, 4, 0.85f);
                require(render(*synth, sr, 100) > 0.01, "preset must generate audio");
                const int next[] = {48, 60, 65, 69, 72};
                synth->setChordNotes(next, 5, 0.85f);
                require(render(*synth, sr, 50) > 0.01, "chord transition must generate audio");
                synth->allNotesOff();
                render(*synth, sr, static_cast<int>(sr * 8 / 256));
                require(render(*synth, sr, 20) < 0.00001, "release and FX tails must decay");
            }
        }
        synth->reset();
        synth->applyPreset(4);
        for (int i = 0; i < 1000; ++i) synth->noteOn(40 + i % 60, 0.8f);
        synth->allNotesOff(); // Deliberately overflow, including a release command.
        render(*synth, 48000, 2000);
        require(render(*synth, 48000, 20) < 0.00001, "queue overflow left stuck notes");
        synth->setPitchBendSemitones(std::numeric_limits<float>::quiet_NaN());
        synth->setFilterCutoff(std::numeric_limits<float>::infinity());
        synth->noteOn(-1, 1.0f);
        synth->noteOn(128, 1.0f);
        render(*synth, 48000, 10);

        std::thread producer1([&] {
            for (int i = 0; i < 20000; ++i) {
                synth->noteOn(48 + i % 24, 0.8f);
                synth->noteOff(48 + i % 24);
            }
        });
        std::thread producer2([&] {
            for (int i = 0; i < 10000; ++i) {
                const int chord[] = {48, 60, 64 + i % 2, 67};
                synth->setChordNotes(chord, 4, 0.8f);
                synth->setFilterCutoff(200.0f + i % 12000);
                synth->applyPreset(i % 8);
            }
        });
        render(*synth, 48000, 1000);
        producer1.join();
        producer2.join();
        synth->allNotesOff();
        render(*synth, 48000, 2000);
        require(render(*synth, 48000, 20) < 0.00001, "concurrent commands left stuck notes");
        std::cout << "PASS: 8 presets, 4 sample rates, chord transitions, release, overflow, concurrent producers\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
