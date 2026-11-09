package com.hichord.pocket

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.hichord.pocket.audio.NativeAudioBridge
import com.hichord.pocket.state.HiChordViewModel
import com.hichord.pocket.theory.JoystickDirection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InstrumentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[HiChordViewModel::class.java]
    private fun center(tag: String): Offset {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        return rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - root
    }

    @Test fun actualOboeStreamStartsAndRestarts() {
        rule.runOnIdle {
            assertTrue(NativeAudioBridge.startEngine())
            assertTrue(NativeAudioBridge.getLatencyMillis() > 0)
            NativeAudioBridge.noteOn(69, 0.5f)
        }
        Thread.sleep(250)
        rule.runOnIdle {
            NativeAudioBridge.allNotesOff()
            NativeAudioBridge.stopEngine()
            assertTrue(NativeAudioBridge.startEngine())
            assertTrue(NativeAudioBridge.getLatencyMillis() > 0)
        }
    }

    @Test fun twoChordFingersRestorePreviousAndCancelReleases() {
        val first = center("chord-1")
        val fourth = center("chord-4")
        rule.onRoot().performTouchInput { down(0, first); down(1, fourth) }
        rule.runOnIdle { assertEquals(4, model.uiState.value.activeButtonId) }
        rule.onRoot().performTouchInput { up(1) }
        rule.runOnIdle { assertEquals(1, model.uiState.value.activeButtonId) }
        rule.onRoot().performTouchInput { cancel() }
        rule.runOnIdle {
            assertNull(model.uiState.value.activeButtonId)
            assertTrue(model.uiState.value.pressedButtons.isEmpty())
        }
    }

    @Test fun chordAndJoystickWorkSimultaneously() {
        val chord = center("chord-1")
        val joy = center("joystick")
        val rect = rule.onNodeWithTag("joystick", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            down(0, chord)
            down(1, joy)
            moveTo(1, joy + Offset(0f, -rect.height * 0.4f))
        }
        rule.runOnIdle {
            assertEquals(1, model.uiState.value.activeButtonId)
            assertEquals(JoystickDirection.NORTH, model.uiState.value.joystickDirection)
            assertEquals("Cmaj7", model.uiState.value.currentChordName)
        }
        rule.onRoot().performTouchInput { up(1) }
        rule.runOnIdle { assertEquals(JoystickDirection.CENTER, model.uiState.value.joystickDirection) }
        rule.onRoot().performTouchInput { up(0) }
        rule.runOnIdle { assertNull(model.uiState.value.activeButtonId) }
    }
}
