#pragma once

#include <oboe/Oboe.h>
#include "SynthCore.h"

namespace hichord {

class SynthEngine : public SynthCore,
                    public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    ~SynthEngine() override { stop(); }

    bool start() {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_ && !restartNeeded_.exchange(false)) return true;
        if (stream_) { stream_->close(); stream_.reset(); }
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setDataCallback(this)
               ->setErrorCallback(this);
        const auto opened = builder.openStream(stream_);
        if (opened != oboe::Result::OK || !stream_) return false;
        if (stream_->requestStart() != oboe::Result::OK) {
            stream_->close();
            stream_.reset();
            return false;
        }
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
        restartNeeded_.store(false);
        reset();
    }

    double getLatencyMillis() {
        // Recovery runs on the control thread, never on Oboe's error thread.
        if (restartNeeded_.load()) start();
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (!stream_) return 0.0;
        const auto latency = stream_->calculateLatencyMillis();
        if (latency) return latency.value();
        return 1000.0 * stream_->getFramesPerBurst() / stream_->getSampleRate();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* data,
                                          int32_t frames) override {
        render(static_cast<float*>(data), frames, static_cast<float>(stream->getSampleRate()));
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) restartNeeded_.store(true);
    }

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;
    std::atomic<bool> restartNeeded_{false};
};
} // namespace hichord
