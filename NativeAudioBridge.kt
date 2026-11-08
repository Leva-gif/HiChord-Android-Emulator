package com.hichord.pocket.audio

object NativeAudioBridge {
    init {
        System.loadLibrary("hichord_dsp")
    }

    external fun startEngine(): Boolean
    external fun stopEngine()
    external fun noteOn(midiNote: Int, velocity: Float = 0.85f)
    external fun noteOff(midiNote: Int)
    external fun setChordNotes(notes: IntArray, velocity: Float = 0.85f)
    external fun allNotesOff()
    external fun applyPreset(index: Int)
    external fun setFilterCutoff(cutoffHz: Float)
    external fun setFilterResonance(resonance: Float)
    external fun setReverbMix(mix: Float)
    external fun setDelayMix(mix: Float)
    external fun setPitchBend(semitones: Float)
    external fun setVibrato(depth: Float)
    external fun getLatencyMillis(): Double
}
