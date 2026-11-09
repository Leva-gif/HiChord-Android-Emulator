package com.hichord.pocket.state

import com.hichord.pocket.audio.AudioEngine
import com.hichord.pocket.theory.JoystickDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiChordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val audio = RecordingAudio()
    private lateinit var model: HiChordViewModel

    @Before fun setup() { Dispatchers.setMain(dispatcher); model = HiChordViewModel(audio) }
    @After fun cleanup() { model.onEngineStopped(); Dispatchers.resetMain() }

    @Test fun lastHeldChordWinsAndReleaseRestoresPreviousChord() {
        model.onChordButtonDown(1)
        val first = audio.notes
        model.onChordButtonDown(4)
        assertEquals(4, model.uiState.value.activeButtonId)
        model.onChordButtonUp(4)
        assertEquals(1, model.uiState.value.activeButtonId)
        assertEquals(first, audio.notes)
        model.onChordButtonUp(1)
        assertTrue(audio.notes.isEmpty())
    }

    @Test fun duplicateDownAndInactiveReleaseDoNotRetrigger() {
        model.onChordButtonDown(1)
        model.onChordButtonDown(4)
        val calls = audio.chordCalls
        model.onChordButtonDown(4)
        model.onChordButtonUp(1)
        assertEquals(calls, audio.chordCalls)
        assertEquals(4, model.uiState.value.activeButtonId)
    }

    @Test fun menuSelectionDoesNotPlayAndJoystickChangesHeldVoicing() {
        model.toggleMenu(ActiveMenu.SOUND_MENU)
        model.onChordButtonDown(2)
        model.onChordButtonUp(2)
        assertEquals(0, audio.chordCalls)
        model.toggleMenu(ActiveMenu.SOUND_MENU)
        model.onChordButtonDown(1)
        val triad = audio.notes
        model.onJoystickMove(0f, -1f)
        assertEquals(JoystickDirection.NORTH, model.uiState.value.joystickDirection)
        assertNotEquals(triad, audio.notes)
        model.cancelTouches()
        assertTrue(audio.notes.isEmpty())
        assertTrue(model.uiState.value.pressedButtons.isEmpty())
    }

    @Test fun strumCannotReintroduceOldNotesAfterJoystickChange() = runTest(dispatcher) {
        model.selectPlayMode(PlayMode.STRUM)
        model.onChordButtonDown(1)
        runCurrent()
        model.onJoystickMove(0f, -1f)
        val expected = audio.notes
        advanceTimeBy(500)
        runCurrent()
        assertEquals(expected, audio.notes)
    }

    @Test fun backgroundCancelsArpeggiatorAndDrone() = runTest(dispatcher) {
        for (mode in listOf(PlayMode.ARP_UP, PlayMode.ARP_DOWN, PlayMode.ARP_UP_DOWN, PlayMode.DRONE)) {
            model.selectPlayMode(mode)
            model.onChordButtonDown(1)
            runCurrent()
            model.onEngineStopped()
            advanceTimeBy(1000)
            assertTrue(audio.notes.isEmpty())
            assertNull(model.uiState.value.activeButtonId)
        }
    }

    private class RecordingAudio : AudioEngine {
        var notes = emptyList<Int>()
        var chordCalls = 0
        override fun noteOn(midiNote: Int, velocity: Float) { notes = notes + midiNote }
        override fun setChordNotes(notes: IntArray, velocity: Float) { this.notes = notes.toList(); chordCalls++ }
        override fun allNotesOff() { notes = emptyList() }
        override fun applyPreset(index: Int) = Unit
        override fun setFilterCutoff(cutoffHz: Float) = Unit
        override fun setPitchBend(semitones: Float) = Unit
        override fun setVibrato(depth: Float) = Unit
        override fun getLatencyMillis() = 0.0
    }
}
