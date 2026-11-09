package com.hichord.pocket.theory

import org.junit.Assert.*
import org.junit.Test

class MusicTheoryEngineTest {
    @Test fun inversionRotatesVoicesAndChangesBass() {
        val firstInversion = MusicTheoryEngine.buildChord(0, ScaleType.MAJOR, 0, 0,
            JoystickDirection.SOUTH_EAST, JoystickMode.STANDARD, false)
        assertEquals(4, firstInversion.midiNotes.first() % 12)
        assertEquals(setOf(0, 4, 7), firstInversion.midiNotes.map { it % 12 }.toSet())
        val dominant = MusicTheoryEngine.buildChord(0, ScaleType.MAJOR, 4, 0,
            JoystickDirection.SOUTH_EAST, JoystickMode.JAZZ_EXT, false)
        assertEquals("G7/3", dominant.chordName)
        val compactDominant = MusicTheoryEngine.buildChord(0, ScaleType.MAJOR, 4, 0,
            JoystickDirection.SOUTH_EAST, JoystickMode.JAZZ_EXT, true)
        assertEquals(11, compactDominant.midiNotes.first() % 12)
    }
    @Test fun allStageACombinationsStayInRange() {
        for (key in 0..11) for (scale in ScaleType.entries) for (degree in 0..6)
            for (octave in -2..2) for (mode in JoystickMode.entries)
                for (direction in JoystickDirection.entries) for (leading in listOf(false, true)) {
                    val chord = MusicTheoryEngine.buildChord(key, scale, degree, octave, direction, mode, leading)
                    assertTrue(chord.midiNotes.isNotEmpty())
                    assertTrue(chord.midiNotes.all { it in 24..104 })
                    assertEquals(chord.midiNotes.toList().distinct().sorted(), chord.midiNotes.toList())
                    assertTrue(chord.chordName.isNotBlank())
                }
    }

    @Test fun cMajorAndDominantSeventhHaveCorrectPitchClasses() {
        val tonic = MusicTheoryEngine.buildChord(0, ScaleType.MAJOR, 0, 0,
            JoystickDirection.CENTER, JoystickMode.STANDARD)
        assertEquals("C", tonic.chordName)
        assertEquals(setOf(0, 4, 7), tonic.midiNotes.map { it % 12 }.toSet())
        val dominant = MusicTheoryEngine.buildChord(0, ScaleType.MAJOR, 4, 0,
            JoystickDirection.NORTH, JoystickMode.STANDARD)
        assertEquals("G7", dominant.chordName)
        assertEquals(setOf(7, 11, 2, 5), dominant.midiNotes.map { it % 12 }.toSet())
    }
}
