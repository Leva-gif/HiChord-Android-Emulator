package com.hichord.pocket.theory

enum class ScaleType(val displayName: String, val intervals: IntArray) {
    MAJOR("MAJ", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("MIN", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN("DOR", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    MIXOLYDIAN("MIX", intArrayOf(0, 2, 4, 5, 7, 9, 10))
}

enum class JoystickDirection(val shortLabel: String) {
    CENTER("TRIAD"),
    NORTH("7TH"),
    SOUTH("SUS4"),
    WEST("SUS2"),
    EAST("6TH"),
    NORTH_WEST("MAJ/MIN"),
    NORTH_EAST("9TH"),
    SOUTH_WEST("DIM/AUG"),
    SOUTH_EAST("INV")
}

enum class JoystickMode(val label: String) {
    STANDARD("STD MOD"),
    JAZZ_EXT("JAZZ EXT"),
    VOICE_INV("INVERS")
}

enum class ButtonLayoutMode(val label: String) {
    SEQUENTIAL("1-4 / 5-7"),
    NASH_PIANO("I-IV-V-VI")
}

data class ChordVoicing(
    val degreeIndex: Int,
    val romanNumeral: String,
    val chordName: String,
    val midiNotes: IntArray
)

object MusicTheoryEngine {

    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun mapButtonToDegree(buttonId: Int, layoutMode: ButtonLayoutMode): Int {
        return when (layoutMode) {
            ButtonLayoutMode.SEQUENTIAL -> (buttonId - 1).coerceIn(0, 6)
            ButtonLayoutMode.NASH_PIANO -> when (buttonId) {
                1 -> 0 // I
                2 -> 3 // IV
                3 -> 4 // V
                4 -> 5 // vi
                5 -> 1 // ii
                6 -> 2 // iii
                7 -> 6 // vii°
                else -> 0
            }
        }
    }

    fun buildChord(
        rootKeySemitone: Int,
        scaleType: ScaleType,
        degreeIndex: Int,
        octaveOffset: Int,
        joystickDir: JoystickDirection,
        joystickMode: JoystickMode,
        smartVoiceLeading: Boolean = true
    ): ChordVoicing {
        val scale = scaleType.intervals
        val deg = degreeIndex.coerceIn(0, 6)

        val rootOffset = scale[deg]
        val thirdOffset = scale[(deg + 2) % 7] + if (deg + 2 >= 7) 12 else 0
        val fifthOffset = scale[(deg + 4) % 7] + if (deg + 4 >= 7) 12 else 0
        val seventhOffset = scale[(deg + 6) % 7] + if (deg + 6 >= 7) 12 else 0

        var thirdInterval = thirdOffset - rootOffset
        var fifthInterval = fifthOffset - rootOffset
        val diatonicSeventh = seventhOffset - rootOffset

        val isInitiallyMinor = (thirdInterval == 3)
        val isInitiallyDim = (fifthInterval == 6)

        val extraIntervals = mutableListOf<Int>()
        var suffix = ""
        var inversionShift = 0

        when (joystickMode) {
            JoystickMode.STANDARD -> {
                when (joystickDir) {
                    JoystickDirection.CENTER -> {
                        suffix = when {
                            isInitiallyDim -> "dim"
                            isInitiallyMinor -> "m"
                            else -> ""
                        }
                    }
                    JoystickDirection.NORTH -> {
                        extraIntervals.add(diatonicSeventh)
                        suffix = when {
                            isInitiallyDim -> "m7b5"
                            isInitiallyMinor -> "m7"
                            diatonicSeventh == 11 -> "maj7"
                            else -> "7"
                        }
                    }
                    JoystickDirection.SOUTH -> {
                        thirdInterval = 5
                        suffix = "sus4"
                    }
                    JoystickDirection.WEST -> {
                        thirdInterval = 2
                        suffix = "sus2"
                    }
                    JoystickDirection.EAST -> {
                        extraIntervals.add(9)
                        suffix = if (isInitiallyMinor) "m6" else "6"
                    }
                    JoystickDirection.NORTH_WEST -> {
                        thirdInterval = if (isInitiallyMinor) 4 else 3
                        fifthInterval = 7
                        suffix = if (thirdInterval == 3) "m" else ""
                    }
                    JoystickDirection.NORTH_EAST -> {
                        extraIntervals.add(diatonicSeventh)
                        extraIntervals.add(14)
                        suffix = when {
                            isInitiallyMinor -> "m9"
                            diatonicSeventh == 11 -> "maj9"
                            else -> "9"
                        }
                    }
                    JoystickDirection.SOUTH_WEST -> {
                        if (isInitiallyMinor) {
                            thirdInterval = 3
                            fifthInterval = 6
                            extraIntervals.add(9)
                            suffix = "dim7"
                        } else {
                            thirdInterval = 4
                            fifthInterval = 8
                            suffix = "aug"
                        }
                    }
                    JoystickDirection.SOUTH_EAST -> {
                        inversionShift = 1
                        suffix = when {
                            isInitiallyDim -> "dim/3"
                            isInitiallyMinor -> "m/3"
                            else -> "/3"
                        }
                    }
                }
            }

            JoystickMode.JAZZ_EXT -> {
                when (joystickDir) {
                    JoystickDirection.CENTER -> {
                        extraIntervals.add(diatonicSeventh)
                        suffix = if (isInitiallyMinor) "m7" else if (diatonicSeventh == 11) "maj7" else "7"
                    }
                    JoystickDirection.NORTH -> {
                        extraIntervals.add(diatonicSeventh)
                        extraIntervals.add(14)
                        suffix = if (isInitiallyMinor) "m9" else if (diatonicSeventh == 11) "maj9" else "9"
                    }
                    JoystickDirection.SOUTH -> {
                        thirdInterval = 5
                        extraIntervals.add(10)
                        suffix = "7sus4"
                    }
                    JoystickDirection.WEST -> {
                        extraIntervals.add(14)
                        suffix = if (isInitiallyMinor) "m(add9)" else "add9"
                    }
                    JoystickDirection.EAST -> {
                        extraIntervals.add(9)
                        extraIntervals.add(14)
                        suffix = if (isInitiallyMinor) "m6/9" else "6/9"
                    }
                    JoystickDirection.NORTH_WEST -> {
                        thirdInterval = 4
                        fifthInterval = 7
                        extraIntervals.add(10)
                        suffix = "7"
                    }
                    JoystickDirection.NORTH_EAST -> {
                        extraIntervals.add(diatonicSeventh)
                        extraIntervals.add(14)
                        extraIntervals.add(17)
                        suffix = if (isInitiallyMinor) "m11" else "11"
                    }
                    JoystickDirection.SOUTH_WEST -> {
                        thirdInterval = 4
                        fifthInterval = 7
                        extraIntervals.add(10)
                        extraIntervals.add(13)
                        suffix = "7b9"
                    }
                    JoystickDirection.SOUTH_EAST -> {
                        extraIntervals.add(diatonicSeventh)
                        inversionShift = 1
                        suffix = when {
                            isInitiallyDim -> "m7b5/3"
                            isInitiallyMinor -> "m7/3"
                            diatonicSeventh == 11 -> "maj7/3"
                            else -> "7/3"
                        }
                    }
                }
            }

            JoystickMode.VOICE_INV -> {
                suffix = if (isInitiallyDim) "dim" else if (isInitiallyMinor) "m" else ""
                inversionShift = when (joystickDir) {
                    JoystickDirection.WEST, JoystickDirection.SOUTH_WEST -> -1
                    JoystickDirection.EAST, JoystickDirection.SOUTH_EAST -> 1
                    JoystickDirection.NORTH, JoystickDirection.NORTH_EAST -> 2
                    else -> 0
                }
            }
        }

        val baseMidiRoot = 60 + rootKeySemitone + rootOffset + (octaveOffset * 12)
        var bassNote = baseMidiRoot - 12

        val rawChordNotes = mutableListOf(
            baseMidiRoot,
            baseMidiRoot + thirdInterval,
            baseMidiRoot + fifthInterval
        )
        for (ext in extraIntervals) {
            rawChordNotes.add(baseMidiRoot + ext)
        }

        val voicedUpper = rawChordNotes.map { note ->
            var n = note
            if (smartVoiceLeading) {
                val centerTarget = 64 + (octaveOffset * 12)
                while (n - centerTarget > 7) n -= 12
                while (centerTarget - n > 6) n += 12
            }
            n
        }.sorted().toMutableList()

        // Rotate individual voices; shifting every note only transposes the chord.
        if (inversionShift > 0) repeat(inversionShift) {
            voicedUpper.add(voicedUpper.removeAt(0) + 12)
        }
        if (inversionShift < 0) repeat(-inversionShift) {
            voicedUpper.add(0, voicedUpper.removeAt(voicedUpper.lastIndex) - 12)
        }
        if (inversionShift != 0) {
            val bassIndex = Math.floorMod(inversionShift, rawChordNotes.size)
            bassNote = rawChordNotes[bassIndex] - 12
            while (bassNote >= voicedUpper.first()) bassNote -= 12
        }

        val allNotes = (listOf(bassNote) + voicedUpper)
            .map { it.coerceIn(24, 104) }
            .distinct()
            .sorted()
            .toIntArray()

        val noteName = noteNames[(rootKeySemitone + rootOffset) % 12]
        val romanBase = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")[deg]
        val roman = when {
            thirdInterval == 3 && fifthInterval == 6 -> "${romanBase.lowercase()}°"
            thirdInterval == 3 -> romanBase.lowercase()
            else -> romanBase
        }

        return ChordVoicing(
            degreeIndex = deg,
            romanNumeral = roman,
            chordName = "$noteName$suffix",
            midiNotes = allNotes
        )
    }
}
