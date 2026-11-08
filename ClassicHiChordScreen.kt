package com.hichord.pocket.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hichord.pocket.state.ActiveMenu
import com.hichord.pocket.state.HiChordUiState
import com.hichord.pocket.state.HiChordViewModel
import com.hichord.pocket.theory.MusicTheoryEngine
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun ClassicHiChordScreen(viewModel: HiChordViewModel) {
    val state by viewModel.uiState.collectAsState()

    val chordButtonRects = remember { mutableStateMapOf<Int, Rect>() }
    val funcButtonRects = remember { mutableStateMapOf<ActiveMenu, Rect>() }
    var joystickRect by remember { mutableStateOf(Rect.Zero) }
    var surfaceRootOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141517))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1.52f)
                .shadow(18.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFDCDAD5),
                            Color(0xFFC9C7C1),
                            Color(0xFFBEBCB6)
                        )
                    )
                )
                .border(3.dp, Color(0xFFA5A39D), RoundedCornerShape(24.dp))
                .onGloballyPositioned { surfaceRootOffset = it.positionInRoot() }
                .pointerInput(Unit) {
                    var joystickPointerId: PointerId? = null
                    var joystickMovedFar = false
                    val chordPointers = mutableMapOf<PointerId, Int>()
                    val consumedFuncPointers = mutableSetOf<PointerId>()

                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChanges = event.changes

                            for (change in activeChanges) {
                                val pid = change.id
                                // Hit-test rectangles are in root coordinates, not pointer-local.
                                val pos = change.position + surfaceRootOffset

                                if (change.pressed) {
                                    if (joystickPointerId == null &&
                                        !chordPointers.containsKey(pid) &&
                                        !consumedFuncPointers.contains(pid) &&
                                        joystickRect.contains(pos)
                                    ) {
                                        joystickPointerId = pid
                                        joystickMovedFar = false
                                    }

                                    if (joystickPointerId == pid) {
                                        val center = joystickRect.center
                                        val radius = (joystickRect.width / 2f).coerceAtLeast(1f)
                                        val dx = ((pos.x - center.x) / radius).coerceIn(-1f, 1f)
                                        val dy = ((pos.y - center.y) / radius).coerceIn(-1f, 1f)
                                        val mag = hypot(dx, dy)
                                        if (mag > 0.26f) joystickMovedFar = true
                                        val clampedX = if (mag > 1f) dx / mag else dx
                                        val clampedY = if (mag > 1f) dy / mag else dy
                                        viewModel.onJoystickMove(clampedX, clampedY)
                                        change.consume()
                                        continue
                                    }

                                    if (!consumedFuncPointers.contains(pid) && !chordPointers.containsKey(pid)) {
                                        val hitMenu = funcButtonRects.entries.firstOrNull { it.value.contains(pos) }?.key
                                        if (hitMenu != null) {
                                            consumedFuncPointers.add(pid)
                                            viewModel.toggleMenu(hitMenu)
                                            change.consume()
                                            continue
                                        }
                                    }

                                    if (!consumedFuncPointers.contains(pid)) {
                                        val hitBtn = chordButtonRects.entries.firstOrNull { it.value.contains(pos) }?.key
                                        val prevBtn = chordPointers[pid]
                                        if (hitBtn != prevBtn) {
                                            if (prevBtn != null) {
                                                chordPointers.remove(pid)
                                                if (!chordPointers.containsValue(prevBtn)) {
                                                    viewModel.onChordButtonUp(prevBtn)
                                                }
                                            }
                                            if (hitBtn != null) {
                                                chordPointers[pid] = hitBtn
                                                viewModel.onChordButtonDown(hitBtn)
                                            }
                                        }
                                        change.consume()
                                    }
                                } else {
                                    if (joystickPointerId == pid) {
                                        if (!joystickMovedFar) {
                                            viewModel.onJoystickCenterClick()
                                        }
                                        joystickPointerId = null
                                        viewModel.onJoystickMove(0f, 0f)
                                    }
                                    val prevBtn = chordPointers.remove(pid)
                                    if (prevBtn != null && !chordPointers.containsValue(prevBtn)) {
                                        viewModel.onChordButtonUp(prevBtn)
                                    }
                                    consumedFuncPointers.remove(pid)
                                    change.consume()
                                }
                            }

                            if (activeChanges.none { it.pressed }) break
                        }
                    }
                }
                .padding(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                LeftHardwareSection(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                        .padding(end = 8.dp),
                    joystickX = state.joystickX,
                    joystickY = state.joystickY,
                    onJoystickPositioned = { joystickRect = it }
                )

                RightBaySection(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                    state = state,
                    presetName = viewModel.presets[state.presetIndex].name,
                    onFuncButtonPositioned = { menu, rect -> funcButtonRects[menu] = rect },
                    onChordButtonPositioned = { id, rect -> chordButtonRects[id] = rect }
                )
            }
        }
    }
}

@Composable
private fun LeftHardwareSection(
    modifier: Modifier,
    joystickX: Float,
    joystickY: Float,
    onJoystickPositioned: (Rect) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "HiChord",
            color = Color(0xFF191919),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            letterSpacing = (-0.8).sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .aspectRatio(1f)
        ) {
            val gridCount = 11
            val step = size.width / (gridCount + 1)
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.width * 0.45f

            for (row in 1..gridCount) {
                for (col in 1..gridCount) {
                    val pt = Offset(col * step, row * step)
                    val dist = hypot(pt.x - center.x, pt.y - center.y)
                    if (dist <= maxRadius) {
                        val holeRadius = step * 0.28f
                        drawCircle(
                            color = Color(0xFF1F1D1B),
                            radius = holeRadius,
                            center = pt
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.70f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { coords ->
                        onJoystickPositioned(coords.boundsInRoot())
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val dotRingRadius = size.minDimension * 0.44f

                    for (i in 0 until 8) {
                        val angleRad = Math.toRadians((i * 45.0) - 90.0)
                        val dotPos = Offset(
                            x = c.x + (cos(angleRad) * dotRingRadius).toFloat(),
                            y = c.y + (sin(angleRad) * dotRingRadius).toFloat()
                        )
                        drawCircle(
                            color = Color(0xFF1E1E1E),
                            radius = size.minDimension * 0.022f,
                            center = dotPos
                        )
                    }

                    drawCircle(
                        color = Color(0xFF3E3B37),
                        radius = size.minDimension * 0.27f,
                        center = c
                    )

                    val maxTravel = size.minDimension * 0.16f
                    val capCenter = Offset(
                        x = c.x + joystickX * maxTravel,
                        y = c.y + joystickY * maxTravel
                    )

                    drawCircle(
                        color = Color(0x55000000),
                        radius = size.minDimension * 0.30f,
                        center = capCenter + Offset(2f, 5f)
                    )

                    drawCircle(
                        color = Color(0xFFE7DFCF),
                        radius = size.minDimension * 0.29f,
                        center = capCenter
                    )

                    drawCircle(
                        color = Color(0xFFFAF3E6),
                        radius = size.minDimension * 0.23f,
                        center = capCenter
                    )
                }
            }

            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(color = Color(0xFF1A1A1A), radius = size.minDimension / 2f)
            }
            Text(
                text = "mic",
                color = Color(0xFF2B2B2B),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RightBaySection(
    modifier: Modifier,
    state: HiChordUiState,
    presetName: String,
    onFuncButtonPositioned: (ActiveMenu, Rect) -> Unit,
    onChordButtonPositioned: (Int, Rect) -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF231F1E))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.24f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            OledDisplayBox(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                state = state,
                presetName = presetName
            )

            FunctionHardwareButton(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned {
                        onFuncButtonPositioned(ActiveMenu.KEY_MENU, it.boundsInRoot())
                    },
                baseColor = Color(0xFF9E9D9E),
                isSelected = state.activeMenu == ActiveMenu.KEY_MENU,
                iconType = FuncIconType.KEY
            )

            FunctionHardwareButton(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned {
                        onFuncButtonPositioned(ActiveMenu.SOUND_MENU, it.boundsInRoot())
                    },
                baseColor = Color(0xFFF6B926),
                isSelected = state.activeMenu == ActiveMenu.SOUND_MENU,
                iconType = FuncIconType.WAVE
            )

            FunctionHardwareButton(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned {
                        onFuncButtonPositioned(ActiveMenu.MODE_MENU, it.boundsInRoot())
                    },
                baseColor = Color(0xFFE9281D),
                isSelected = state.activeMenu == ActiveMenu.MODE_MENU,
                iconType = FuncIconType.CLOCK
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.27f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ChordHardwareKey(
                buttonId = 5,
                state = state,
                isWideUpper = true,
                modifier = Modifier
                    .weight(1.22f)
                    .fillMaxHeight()
                    .onGloballyPositioned { onChordButtonPositioned(5, it.boundsInRoot()) }
            )
            ChordHardwareKey(
                buttonId = 6,
                state = state,
                isWideUpper = false,
                modifier = Modifier
                    .weight(0.92f)
                    .fillMaxHeight()
                    .onGloballyPositioned { onChordButtonPositioned(6, it.boundsInRoot()) }
            )
            ChordHardwareKey(
                buttonId = 7,
                state = state,
                isWideUpper = true,
                modifier = Modifier
                    .weight(1.22f)
                    .fillMaxHeight()
                    .onGloballyPositioned { onChordButtonPositioned(7, it.boundsInRoot()) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.49f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            for (btnId in 1..4) {
                ChordHardwareKey(
                    buttonId = btnId,
                    state = state,
                    isWideUpper = false,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { onChordButtonPositioned(btnId, it.boundsInRoot()) }
                )
            }
        }
    }
}

@Composable
private fun OledDisplayBox(
    modifier: Modifier,
    state: HiChordUiState,
    presetName: String
) {
    val keyName = MusicTheoryEngine.noteNames[state.rootKeyIndex]
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF050608))
            .border(2.dp, Color(0xFFC4C2BC), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        when (state.activeMenu) {
            ActiveMenu.NONE -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$keyName ${state.scaleType.displayName}",
                            color = Color(0xFF67E8F9),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.joystickDirection.shortLabel,
                            color = Color(0xFFFDE047),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = state.currentChordName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = presetName.take(9),
                            color = Color(0xFFA7F3D0),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${state.playMode.label.take(5)} ${state.latencyMs.toInt()}ms",
                            color = Color(0xFF94A3B8),
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            ActiveMenu.KEY_MENU -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "KEY MENU [1-7]",
                        color = Color(0xFFCBD5E1),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$keyName ${state.scaleType.displayName} OCT:${state.octaveOffset}",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "VL:${if (state.smartVoiceLeading) "ON" else "OFF"} ${state.buttonLayoutMode.label}",
                        color = Color(0xFF94A3B8),
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            ActiveMenu.SOUND_MENU -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SOUND [1-7/JOY]",
                        color = Color(0xFFFDE047),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = presetName.take(12),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "LPF:${state.filterCutoffHz.toInt()}Hz",
                        color = Color(0xFFA7F3D0),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            ActiveMenu.MODE_MENU -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "MODE [1-7/JOY]",
                        color = Color(0xFFF87171),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.playMode.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "BPM:${state.bpm} JOY:${state.joystickMode.label.take(4)}",
                        color = Color(0xFFFDE047),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private enum class FuncIconType { KEY, WAVE, CLOCK }

@Composable
private fun FunctionHardwareButton(
    modifier: Modifier,
    baseColor: Color,
    isSelected: Boolean,
    iconType: FuncIconType
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.95f else 1.0f,
        animationSpec = tween(70),
        label = "funcScale"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (isSelected) baseColor.copy(alpha = 0.85f) else baseColor)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) Color.White else Color(0x33000000),
                shape = RoundedCornerShape(9.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize(0.48f * scale)) {
            val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
            when (iconType) {
                FuncIconType.KEY -> {
                    val r = size.minDimension * 0.26f
                    val ringCenter = Offset(size.width * 0.34f, size.height * 0.66f)
                    drawCircle(color = Color.White, radius = r, center = ringCenter, style = stroke)
                    val bladeStart = Offset(ringCenter.x + r * 0.7f, ringCenter.y - r * 0.7f)
                    val bladeEnd = Offset(size.width * 0.85f, size.height * 0.15f)
                    drawLine(Color.White, bladeStart, bladeEnd, strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(
                        Color.White,
                        Offset(bladeEnd.x - r * 0.3f, bladeEnd.y + r * 0.3f),
                        Offset(bladeEnd.x, bladeEnd.y + r * 0.6f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round
                    )
                }
                FuncIconType.WAVE -> {
                    val path = Path().apply {
                        moveTo(0f, size.height * 0.55f)
                        lineTo(size.width * 0.22f, size.height * 0.55f)
                        lineTo(size.width * 0.35f, size.height * 0.15f)
                        lineTo(size.width * 0.62f, size.height * 0.85f)
                        lineTo(size.width * 0.78f, size.height * 0.45f)
                        lineTo(size.width, size.height * 0.45f)
                    }
                    drawPath(path = path, color = Color.White, style = stroke)
                }
                FuncIconType.CLOCK -> {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(color = Color.White, radius = size.minDimension * 0.45f, center = c, style = stroke)
                    drawLine(Color.White, c, Offset(c.x, c.y - size.height * 0.26f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(Color.White, c, Offset(c.x + size.width * 0.22f, c.y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ChordHardwareKey(
    buttonId: Int,
    state: HiChordUiState,
    isWideUpper: Boolean,
    modifier: Modifier
) {
    val isPressed = state.pressedButtons.contains(buttonId)
    val pressDepth by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = tween(durationMillis = 45),
        label = "keyPress"
    )

    val degreeIdx = MusicTheoryEngine.mapButtonToDegree(buttonId, state.buttonLayoutMode)
    val previewChord = remember(state.rootKeyIndex, state.scaleType, state.joystickDirection, state.joystickMode, state.buttonLayoutMode, state.octaveOffset, state.smartVoiceLeading) {
        MusicTheoryEngine.buildChord(
            rootKeySemitone = state.rootKeyIndex,
            scaleType = state.scaleType,
            degreeIndex = degreeIdx,
            octaveOffset = state.octaveOffset,
            joystickDir = state.joystickDirection,
            joystickMode = state.joystickMode,
            smartVoiceLeading = state.smartVoiceLeading
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) Color(0xFFE3DAC8) else Color(0xFFF6EFE0))
            .border(
                width = 1.5.dp,
                color = if (isPressed) Color(0xFFB3A997) else Color(0xFFD7CDBA),
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val insetX = if (isWideUpper) size.width * 0.20f else size.width * 0.11f
            val insetY = size.height * 0.08f + (pressDepth * 2f)
            val capWidth = size.width - insetX * 2f
            val capHeight = size.height - insetY * 2f

            drawRoundRect(
                color = if (isPressed) Color(0xFFECE3D2) else Color(0xFFFAF5EA),
                topLeft = Offset(insetX, insetY),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = Color(0x308B806E),
                topLeft = Offset(insetX, insetY),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 2f)
            )
        }

        val hintText = when (state.activeMenu) {
            ActiveMenu.NONE -> "${previewChord.romanNumeral} · ${previewChord.chordName}"
            ActiveMenu.KEY_MENU -> when (buttonId) {
                1 -> "KEY -"
                2 -> "KEY +"
                3 -> "SCALE"
                4 -> "LAYOUT"
                5 -> "OCT -"
                6 -> "OCT +"
                else -> "V.LEAD"
            }
            ActiveMenu.SOUND_MENU -> "PST $buttonId"
            ActiveMenu.MODE_MENU -> when (buttonId) {
                1 -> "CHORD"
                2 -> "ARP UP"
                3 -> "ARP DN"
                4 -> "ARP U/D"
                5 -> "STRUM"
                6 -> "DRONE"
                else -> "LEAD"
            }
        }

        Text(
            text = hintText,
            color = Color(0x884A443B),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}
