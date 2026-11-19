package dog.bald.arcade.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dog.bald.arcade.input.RetroJoypad
import dog.bald.arcade.input.TouchLayoutConfig
import dog.bald.arcade.ui.theme.ArcadeBlack
import dog.bald.arcade.ui.theme.ArcadeMagenta
import dog.bald.arcade.ui.theme.ArcadeOrange
import dog.bald.arcade.ui.theme.CoinYellow
import dog.bald.arcade.ui.theme.LeatherBlack
import dog.bald.arcade.ui.theme.RetroIvory
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun ArcadeTouchOverlay(
    config: TouchLayoutConfig,
    editMode: Boolean,
    onConfigChange: (TouchLayoutConfig) -> Unit,
    onMaskChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var dpadMask by remember { mutableIntStateOf(0) }
    var buttonsMask by remember { mutableIntStateOf(0) }

    fun updateDpad(newMask: Int) {
        if (dpadMask != newMask) {
            dpadMask = newMask
            onMaskChanged(dpadMask or buttonsMask)
        }
    }

    fun updateButton(bit: Int, pressed: Boolean) {
        val next = if (pressed) buttonsMask or bit else buttonsMask and bit.inv()
        if (buttonsMask != next) {
            buttonsMask = next
            onMaskChanged(dpadMask or buttonsMask)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .alpha(if (editMode) 0.95f else config.opacity)
    ) {
        // COIN y START arriba a izquierda/derecha para no estorbar
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ArcadePillButton(
                label = "COIN",
                accent = CoinYellow,
                editMode = editMode,
                onPressChanged = { updateButton(RetroJoypad.COIN, it) }
            )
            ArcadePillButton(
                label = "START",
                accent = ArcadeOrange,
                editMode = editMode,
                onPressChanged = { updateButton(RetroJoypad.START, it) }
            )
        }

        // D-pad 8 direcciones (izquierda abajo)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(config.dpadOffsetX.roundToInt(), config.dpadOffsetY.roundToInt()) }
                .then(
                    if (editMode) {
                        Modifier.pointerInput(config) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onConfigChange(
                                    config.copy(
                                        dpadOffsetX = config.dpadOffsetX + dragAmount.x,
                                        dpadOffsetY = config.dpadOffsetY + dragAmount.y
                                    )
                                )
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            ArcadeEightWayDpad(
                sizeDp = (148f * config.scale).dp,
                activeMask = dpadMask,
                editMode = editMode,
                onDpadChanged = { updateDpad(it) }
            )
        }

        // Cluster de botones Arcade (derecha abajo)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(config.buttonsOffsetX.roundToInt(), config.buttonsOffsetY.roundToInt()) }
                .then(
                    if (editMode) {
                        Modifier.pointerInput(config) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onConfigChange(
                                    config.copy(
                                        buttonsOffsetX = config.buttonsOffsetX + dragAmount.x,
                                        buttonsOffsetY = config.buttonsOffsetY + dragAmount.y
                                    )
                                )
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val btnSize = (56f * config.scale).dp
            Column(
                verticalArrangement = Arrangement.spacedBy((10f * config.scale).dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((12f * config.scale).dp)) {
                    ArcadeCircleButton(
                        label = "Y",
                        size = btnSize,
                        accent = ArcadeMagenta,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.Y, it) }
                    )
                    ArcadeCircleButton(
                        label = "X",
                        size = btnSize,
                        accent = ArcadeMagenta,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.X, it) }
                    )
                    ArcadeCircleButton(
                        label = "L",
                        size = btnSize,
                        accent = ArcadeOrange,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.L, it) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy((12f * config.scale).dp)) {
                    ArcadeCircleButton(
                        label = "B",
                        size = btnSize,
                        accent = CoinYellow,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.B, it) }
                    )
                    ArcadeCircleButton(
                        label = "A",
                        size = btnSize,
                        accent = CoinYellow,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.A, it) }
                    )
                    ArcadeCircleButton(
                        label = "R",
                        size = btnSize,
                        accent = ArcadeOrange,
                        editMode = editMode,
                        onPressChanged = { updateButton(RetroJoypad.R, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArcadeEightWayDpad(
    sizeDp: Dp,
    activeMask: Int,
    editMode: Boolean,
    onDpadChanged: (Int) -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { (sizeDp / 2).toPx() }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .then(
                if (!editMode) {
                    Modifier.pointerInput(radiusPx) {
                        awaitPointerEventScope {
                            var activePointer: PointerId? = null
                            while (true) {
                                val event = awaitPointerEvent()
                                val center = Offset(size.width / 2f, size.height / 2f)

                                val tracked = activePointer?.let { pid ->
                                    event.changes.firstOrNull { it.id == pid }
                                }
                                if (tracked == null) {
                                    val pressed = event.changes.firstOrNull { it.pressed }
                                    if (pressed != null) {
                                        activePointer = pressed.id
                                        pressed.consume()
                                        val delta = pressed.position - center
                                        val (mask, clamped) = computeEightWay(delta, radiusPx)
                                        knobOffset = clamped
                                        onDpadChanged(mask)
                                    }
                                } else if (!tracked.pressed) {
                                    tracked.consume()
                                    activePointer = null
                                    knobOffset = Offset.Zero
                                    onDpadChanged(0)
                                } else {
                                    tracked.consume()
                                    val delta = tracked.position - center
                                    val (mask, clamped) = computeEightWay(delta, radiusPx)
                                    knobOffset = clamped
                                    onDpadChanged(mask)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val outerR = size.minDimension / 2f
            drawCircle(
                color = LeatherBlack.copy(alpha = 0.75f),
                radius = outerR,
                center = c
            )
            drawCircle(
                color = if (editMode) CoinYellow else ArcadeMagenta.copy(alpha = 0.8f),
                radius = outerR,
                center = c,
                style = Stroke(width = 3f)
            )
            val stickColor = if (activeMask != 0) CoinYellow else RetroIvory.copy(alpha = 0.7f)
            drawCircle(
                color = stickColor,
                radius = outerR * 0.34f,
                center = c + knobOffset
            )
        }
    }
}

private fun computeEightWay(delta: Offset, maxRadius: Float): Pair<Int, Offset> {
    val dist = hypot(delta.x, delta.y)
    val deadzone = maxRadius * 0.18f
    val clamped = if (dist > maxRadius * 0.6f && dist > 0f) {
        delta * ((maxRadius * 0.6f) / dist)
    } else {
        delta
    }
    if (dist < deadzone) {
        return 0 to clamped
    }
    val angle = Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble()))
    // 8 sectores de 45 grados
    var mask = 0
    if (angle in -67.5..67.5) mask = mask or RetroJoypad.RIGHT
    if (angle >= 112.5 || angle <= -112.5) mask = mask or RetroJoypad.LEFT
    if (angle in 22.5..157.5) mask = mask or RetroJoypad.DOWN
    if (angle in -157.5..-22.5) mask = mask or RetroJoypad.UP
    return mask to clamped
}

@Composable
private fun ArcadeCircleButton(
    label: String,
    size: Dp,
    accent: Color,
    editMode: Boolean,
    onPressChanged: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(
                color = if (pressed) accent else LeatherBlack.copy(alpha = 0.82f),
                shape = CircleShape
            )
            .border(
                width = 2.dp,
                color = if (editMode) CoinYellow else accent,
                shape = CircleShape
            )
            .then(
                if (!editMode) {
                    Modifier.pointerInput(label) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val anyDown = event.changes.any { it.pressed }
                                event.changes.forEach { it.consume() }
                                if (pressed != anyDown) {
                                    pressed = anyDown
                                    onPressChanged(anyDown)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = label,
            color = if (pressed) ArcadeBlack else RetroIvory,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ArcadePillButton(
    label: String,
    accent: Color,
    editMode: Boolean,
    onPressChanged: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(
                color = if (pressed) accent else LeatherBlack.copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(1.5.dp, accent, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .then(
                if (!editMode) {
                    Modifier.pointerInput(label) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val anyDown = event.changes.any { it.pressed }
                                event.changes.forEach { it.consume() }
                                if (pressed != anyDown) {
                                    pressed = anyDown
                                    onPressChanged(anyDown)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = label,
            color = if (pressed) ArcadeBlack else RetroIvory,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}
