package com.hichord.pocket.audio

object NativeAudioBridge : AudioEngine {
    init {
        System.loadLibrary("hichord_dsp")
    }

    external fun startEngine(): Boolean
    external fun stopEngine()
    external override fun noteOn(midiNote: Int, velocity: Float)
    external fun noteOff(midiNote: Int)
    external override fun setChordNotes(notes: IntArray, velocity: Float)
    external override fun allNotesOff()
    external override fun applyPreset(index: Int)
    external override fun setFilterCutoff(cutoffHz: Float)
    external fun setFilterResonance(resonance: Float)
    external fun setReverbMix(mix: Float)
    external fun setDelayMix(mix: Float)
    external override fun setPitchBend(semitones: Float)
    external override fun setVibrato(depth: Float)
    external override fun getLatencyMillis(): Double
}
