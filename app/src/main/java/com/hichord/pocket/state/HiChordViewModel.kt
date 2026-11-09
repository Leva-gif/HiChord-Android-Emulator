package com.hichord.pocket.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hichord.pocket.audio.NativeAudioBridge
import com.hichord.pocket.audio.AudioEngine
import com.hichord.pocket.theory.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActiveMenu {
    NONE, KEY_MENU, SOUND_MENU, MODE_MENU
}

enum class PlayMode(val label: String) {
    CHORD("CHORD"),
    ARP_UP("ARP UP"),
    ARP_DOWN("ARP DN"),
    ARP_UP_DOWN("ARP U/D"),
    STRUM("STRUM"),
    DRONE("DRONE"),
    LEAD("LEAD")
}

data class PresetInfo(val id: Int, val name: String, val defaultCutoff: Float)

data class HiChordUiState(
    val rootKeyIndex: Int = 0,
    val scaleType: ScaleType = ScaleType.MAJOR,
    val octaveOffset: Int = 0,
    val presetIndex: Int = 0,
    val playMode: PlayMode = PlayMode.CHORD,
    val bpm: Int = 115,
    val activeMenu: ActiveMenu = ActiveMenu.NONE,
    val joystickDirection: JoystickDirection = JoystickDirection.CENTER,
    val joystickX: Float = 0f,
    val joystickY: Float = 0f,
    val joystickMode: JoystickMode = JoystickMode.STANDARD,
    val buttonLayoutMode: ButtonLayoutMode = ButtonLayoutMode.SEQUENTIAL,
    val smartVoiceLeading: Boolean = true,
    val pressedButtons: Set<Int> = emptySet(),
    val activeButtonId: Int? = null,
    val currentChordName: String = "C",
    val currentRoman: String = "I",
    val currentNotes: List<Int> = emptyList(),
    val filterCutoffHz: Float = 3400f,
    val reverbMix: Float = 0.28f,
    val delayMix: Float = 0.12f,
    val latencyMs: Double = 0.0
)

class HiChordViewModel(private val audio: AudioEngine = NativeAudioBridge) : ViewModel() {

    val presets = listOf(
        PresetInfo(0, "WARM FM PIANO", 3400f),
        PresetInfo(1, "VINTAGE EP", 4200f),
        PresetInfo(2, "ANALOG PAD", 1850f),
        PresetInfo(3, "DREAM SAW", 2900f),
        PresetInfo(4, "SOUL ORGAN", 5200f),
        PresetInfo(5, "LO-FI KEYS", 1450f),
        PresetInfo(6, "CRYSTAL BELL", 7500f),
        PresetInfo(7, "RETRO SQUARE", 4800f)
    )

    private val _uiState = MutableStateFlow(HiChordUiState())
    val uiState: StateFlow<HiChordUiState> = _uiState.asStateFlow()

    private var patternJob: Job? = null
    private var arpStepIndex = 0
    private var latencyJob: Job? = null

    fun onEngineStarted() {
        val s = _uiState.value
        audio.applyPreset(s.presetIndex)
        audio.setFilterCutoff(s.filterCutoffHz)
        audio.setPitchBend(0f)
        audio.setVibrato(0f)
        latencyJob?.cancel()
        latencyJob = viewModelScope.launch {
            while (isActive) {
                refreshLatency()
                delay(1000)
            }
        }
    }

    fun onEngineStopped() {
        latencyJob?.cancel()
        latencyJob = null
        stopAllPlayingNotes()
        _uiState.update {
            it.copy(
                pressedButtons = emptySet(),
                activeButtonId = null,
                joystickDirection = JoystickDirection.CENTER,
                joystickX = 0f,
                joystickY = 0f,
                currentNotes = emptyList(),
                latencyMs = 0.0
            )
        }
    }

    fun refreshLatency() {
        val lat = audio.getLatencyMillis()
        _uiState.update { it.copy(latencyMs = lat) }
    }

    fun toggleMenu(menu: ActiveMenu) {
        _uiState.update { state ->
            val next = if (state.activeMenu == menu) ActiveMenu.NONE else menu
            state.copy(activeMenu = next)
        }
        refreshLatency()
    }

    fun onChordButtonDown(buttonId: Int) {
        if (buttonId !in 1..7 || buttonId in _uiState.value.pressedButtons) return
        val state = _uiState.value

        when (state.activeMenu) {
            ActiveMenu.KEY_MENU -> {
                when (buttonId) {
                    1 -> changeKey(-1)
                    2 -> changeKey(+1)
                    3 -> toggleScaleType()
                    4 -> toggleButtonLayout()
                    5 -> changeOctave(-1)
                    6 -> changeOctave(+1)
                    7 -> toggleVoiceLeading()
                }
            }
            ActiveMenu.SOUND_MENU -> {
                val targetPreset = (buttonId - 1).coerceIn(0, presets.lastIndex)
                selectPreset(targetPreset)
            }
            ActiveMenu.MODE_MENU -> {
                val modes = PlayMode.entries
                val targetMode = modes[(buttonId - 1) % modes.size]
                selectPlayMode(targetMode)
            }
            ActiveMenu.NONE -> Unit
        }

        // In a settings menu these keys choose options; they must not play notes.
        if (state.activeMenu != ActiveMenu.NONE) return

        _uiState.update {
            it.copy(
                pressedButtons = it.pressedButtons + buttonId,
                activeButtonId = buttonId
            )
        }
        triggerOrUpdateActiveChord(isNewPress = true)
    }

    fun onChordButtonUp(buttonId: Int) {
        val state = _uiState.value
        // Ignore release of a settings key that was never registered as a note.
        if (buttonId !in state.pressedButtons) return
        val remaining = state.pressedButtons - buttonId
        val nextActive = if (state.activeButtonId in remaining) state.activeButtonId else remaining.lastOrNull()

        _uiState.update {
            it.copy(
                pressedButtons = remaining,
                activeButtonId = nextActive
            )
        }

        if (nextActive != null && nextActive != state.activeButtonId) {
            triggerOrUpdateActiveChord(isNewPress = false)
        } else if (nextActive == null) {
            if (state.playMode != PlayMode.DRONE) {
                stopAllPlayingNotes()
            }
        }
    }

    fun onJoystickMove(normX: Float, normY: Float) {
        val dir = computeJoystickDirection(normX, normY)
        val prevDir = _uiState.value.joystickDirection

        _uiState.update {
            it.copy(
                joystickX = normX,
                joystickY = normY,
                joystickDirection = dir
            )
        }

        if (dir != prevDir) {
            val s = _uiState.value
            when (s.activeMenu) {
                ActiveMenu.KEY_MENU -> {
                    when (dir) {
                        JoystickDirection.EAST -> changeKey(+1)
                        JoystickDirection.WEST -> changeKey(-1)
                        JoystickDirection.NORTH -> changeOctave(+1)
                        JoystickDirection.SOUTH -> changeOctave(-1)
                        else -> Unit
                    }
                }
                ActiveMenu.SOUND_MENU -> {
                    when (dir) {
                        JoystickDirection.EAST -> selectPreset((s.presetIndex + 1) % presets.size)
                        JoystickDirection.WEST -> selectPreset((s.presetIndex - 1 + presets.size) % presets.size)
                        JoystickDirection.NORTH -> adjustCutoff(+450f)
                        JoystickDirection.SOUTH -> adjustCutoff(-450f)
                        else -> Unit
                    }
                }
                ActiveMenu.MODE_MENU -> {
                    when (dir) {
                        JoystickDirection.EAST -> changeBpm(+5)
                        JoystickDirection.WEST -> changeBpm(-5)
                        JoystickDirection.NORTH -> cyclePlayMode(+1)
                        JoystickDirection.SOUTH -> cyclePlayMode(-1)
                        else -> Unit
                    }
                }
                ActiveMenu.NONE -> Unit
            }

            if (_uiState.value.activeMenu == ActiveMenu.NONE && _uiState.value.activeButtonId != null) {
                triggerOrUpdateActiveChord(isNewPress = false)
            }
        }
    }

    fun onJoystickCenterClick() {
        val s = _uiState.value
        if (s.activeMenu != ActiveMenu.NONE) {
            _uiState.update { it.copy(activeMenu = ActiveMenu.NONE) }
            return
        }
        val modes = JoystickMode.entries
        val nextMode = modes[(s.joystickMode.ordinal + 1) % modes.size]
        _uiState.update { it.copy(joystickMode = nextMode) }
        if (_uiState.value.activeButtonId != null) {
            triggerOrUpdateActiveChord(isNewPress = false)
        }
    }

    fun changeKey(delta: Int) {
        _uiState.update {
            val nextKey = (it.rootKeyIndex + delta + 12) % 12
            val preview = buildVoicingForState(it.copy(rootKeyIndex = nextKey), buttonId = it.activeButtonId ?: 1)
            it.copy(
                rootKeyIndex = nextKey,
                currentChordName = preview.chordName,
                currentRoman = preview.romanNumeral
            )
        }
        if (_uiState.value.activeButtonId != null) {
            triggerOrUpdateActiveChord(isNewPress = false)
        }
    }

    fun toggleScaleType() {
        val types = ScaleType.entries
        _uiState.update {
            val next = types[(it.scaleType.ordinal + 1) % types.size]
            val preview = buildVoicingForState(it.copy(scaleType = next), buttonId = it.activeButtonId ?: 1)
            it.copy(
                scaleType = next,
                currentChordName = preview.chordName,
                currentRoman = preview.romanNumeral
            )
        }
        if (_uiState.value.activeButtonId != null) {
            triggerOrUpdateActiveChord(isNewPress = false)
        }
    }

    fun changeOctave(delta: Int) {
        _uiState.update {
            it.copy(octaveOffset = (it.octaveOffset + delta).coerceIn(-2, 2))
        }
        if (_uiState.value.activeButtonId != null) {
            triggerOrUpdateActiveChord(isNewPress = false)
        }
    }

    fun toggleButtonLayout() {
        val layouts = ButtonLayoutMode.entries
        _uiState.update {
            val next = layouts[(it.buttonLayoutMode.ordinal + 1) % layouts.size]
            it.copy(buttonLayoutMode = next)
        }
        if (_uiState.value.activeButtonId != null) triggerOrUpdateActiveChord(false)
    }

    fun toggleVoiceLeading() {
        _uiState.update { it.copy(smartVoiceLeading = !it.smartVoiceLeading) }
        if (_uiState.value.activeButtonId != null) triggerOrUpdateActiveChord(false)
    }

    fun selectPreset(index: Int) {
        val safeIdx = index.coerceIn(0, presets.lastIndex)
        val preset = presets[safeIdx]
        audio.applyPreset(safeIdx)
        _uiState.update {
            it.copy(
                presetIndex = safeIdx,
                filterCutoffHz = preset.defaultCutoff
            )
        }
    }

    fun adjustCutoff(deltaHz: Float) {
        _uiState.update {
            val next = (it.filterCutoffHz + deltaHz).coerceIn(180f, 12000f)
            audio.setFilterCutoff(next)
            it.copy(filterCutoffHz = next)
        }
    }

    fun selectPlayMode(mode: PlayMode) {
        stopAllPlayingNotes()
        _uiState.update { it.copy(playMode = mode) }
        if (_uiState.value.activeButtonId != null) {
            triggerOrUpdateActiveChord(isNewPress = true)
        }
    }

    fun cyclePlayMode(delta: Int) {
        val modes = PlayMode.entries
        val next = modes[(_uiState.value.playMode.ordinal + delta + modes.size) % modes.size]
        selectPlayMode(next)
    }

    fun changeBpm(delta: Int) {
        _uiState.update { it.copy(bpm = (it.bpm + delta).coerceIn(50, 220)) }
    }

    private fun triggerOrUpdateActiveChord(isNewPress: Boolean) {
        val s = _uiState.value
        val btnId = s.activeButtonId ?: return
        val voicing = buildVoicingForState(s, btnId)

        _uiState.update {
            it.copy(
                currentChordName = voicing.chordName,
                currentRoman = voicing.romanNumeral,
                currentNotes = voicing.midiNotes.toList()
            )
        }

        when (s.playMode) {
            PlayMode.CHORD, PlayMode.DRONE -> {
                patternJob?.cancel()
                audio.setChordNotes(voicing.midiNotes, 0.85f)
            }
            PlayMode.LEAD -> {
                patternJob?.cancel()
                val leadNote = voicing.midiNotes.lastOrNull() ?: 60
                audio.setChordNotes(intArrayOf(leadNote), 0.92f)
            }
            PlayMode.STRUM -> {
                if (isNewPress) {
                    patternJob?.cancel()
                    patternJob = viewModelScope.launch {
                        audio.allNotesOff()
                        for (note in voicing.midiNotes) {
                            if (!isActive) break
                            audio.noteOn(note, 0.85f)
                            delay(34L)
                        }
                    }
                } else {
                    patternJob?.cancel()
                    patternJob = null
                    audio.setChordNotes(voicing.midiNotes, 0.82f)
                }
            }
            PlayMode.ARP_UP, PlayMode.ARP_DOWN, PlayMode.ARP_UP_DOWN -> {
                if (patternJob?.isActive != true) {
                    arpStepIndex = 0
                    patternJob = viewModelScope.launch {
                        while (isActive && _uiState.value.activeButtonId != null) {
                            val current = _uiState.value
                            val notes = current.currentNotes
                            if (notes.isNotEmpty()) {
                                val noteToPlay = pickArpNote(notes, current.playMode, arpStepIndex++)
                                audio.allNotesOff()
                                audio.noteOn(noteToPlay, 0.88f)
                            }
                            val stepMs = (60_000L / current.bpm.coerceAtLeast(40)) / 2L
                            delay(stepMs)
                        }
                    }
                }
            }
        }
    }

    private fun pickArpNote(notes: List<Int>, mode: PlayMode, step: Int): Int {
        if (notes.size == 1) return notes[0]
        return when (mode) {
            PlayMode.ARP_UP -> notes[step % notes.size]
            PlayMode.ARP_DOWN -> notes[(notes.size - 1) - (step % notes.size)]
            PlayMode.ARP_UP_DOWN -> {
                val cycle = (notes.size * 2 - 2).coerceAtLeast(1)
                val pos = step % cycle
                if (pos < notes.size) notes[pos] else notes[cycle - pos]
            }
            else -> notes[0]
        }
    }

    fun cancelTouches() {
        stopAllPlayingNotes()
        _uiState.update { it.copy(pressedButtons = emptySet(), activeButtonId = null,
            joystickDirection = JoystickDirection.CENTER, joystickX = 0f, joystickY = 0f,
            currentNotes = emptyList()) }
    }

    override fun onCleared() {
        latencyJob?.cancel()
        stopAllPlayingNotes()
        super.onCleared()
    }

    private fun stopAllPlayingNotes() {
        patternJob?.cancel()
        patternJob = null
        audio.allNotesOff()
    }

    private fun buildVoicingForState(state: HiChordUiState, buttonId: Int): ChordVoicing {
        val deg = MusicTheoryEngine.mapButtonToDegree(buttonId, state.buttonLayoutMode)
        return MusicTheoryEngine.buildChord(
            rootKeySemitone = state.rootKeyIndex,
            scaleType = state.scaleType,
            degreeIndex = deg,
            octaveOffset = state.octaveOffset,
            joystickDir = state.joystickDirection,
            joystickMode = state.joystickMode,
            smartVoiceLeading = state.smartVoiceLeading
        )
    }

    private fun computeJoystickDirection(x: Float, y: Float): JoystickDirection {
        val mag = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat()
        if (mag < 0.28f) return JoystickDirection.CENTER

        val angleDeg = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
        return when {
            angleDeg in -22.5f..22.5f -> JoystickDirection.EAST
            angleDeg in 22.5f..67.5f -> JoystickDirection.SOUTH_EAST
            angleDeg in 67.5f..112.5f -> JoystickDirection.SOUTH
            angleDeg in 112.5f..157.5f -> JoystickDirection.SOUTH_WEST
            angleDeg < -157.5f || angleDeg > 157.5f -> JoystickDirection.WEST
            angleDeg in -157.5f..-112.5f -> JoystickDirection.NORTH_WEST
            angleDeg in -112.5f..-67.5f -> JoystickDirection.NORTH
            else -> JoystickDirection.NORTH_EAST
        }
    }
}
