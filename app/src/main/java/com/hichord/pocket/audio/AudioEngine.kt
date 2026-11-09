package com.hichord.pocket.audio

interface AudioEngine {
    fun noteOn(midiNote: Int, velocity: Float = 0.85f)
    fun setChordNotes(notes: IntArray, velocity: Float = 0.85f)
    fun allNotesOff()
    fun applyPreset(index: Int)
    fun setFilterCutoff(cutoffHz: Float)
    fun setPitchBend(semitones: Float)
    fun setVibrato(depth: Float)
    fun getLatencyMillis(): Double
}
