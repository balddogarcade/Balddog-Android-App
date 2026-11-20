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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
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
    var clusterMask by remember { mutableIntStateOf(0) }
    var pillMask by remember { mutableIntStateOf(0) }

    fun emitCombined() {
        onMaskChanged(dpadMask or clusterMask or pillMask)
    }

    fun updateDpad(newMask: Int) {
        if (dpadMask != newMask) {
            dpadMask = newMask
            emitCombined()
        }
    }

    fun updateCluster(newMask: Int) {
        if (clusterMask != newMask) {
            clusterMask = newMask
            emitCombined()
        }
    }

    fun updatePill(bit: Int, pressed: Boolean) {
        val next = if (pressed) pillMask or bit else pillMask and bit.inv()
        if (pillMask != next) {
            pillMask = next
            emitCombined()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .alpha(if (editMode) 0.95f else config.opacity)
    ) {
        // COIN y START arriba a la izquierda
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ArcadePillButton(
                label = "COIN",
                accent = CoinYellow,
                editMode = editMode,
                onPressChanged = { updatePill(RetroJoypad.COIN, it) }
            )
            ArcadePillButton(
                label = "START",
                accent = ArcadeOrange,
                editMode = editMode,
                onPressChanged = { updatePill(RetroJoypad.START, it) }
            )
        }

        // D-pad 8 direcciones compacto (116.dp base)
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
                sizeDp = (116f * config.scale).dp,
                activeMask = dpadMask,
                editMode = editMode,
                onDpadChanged = { updateDpad(it) }
            )
        }

        // Cluster multi-touch real de botones Arcade (soporta varios dedos y deslizamiento)
        ArcadeButtonCluster(
            scale = config.scale,
            activeMask = clusterMask,
            editMode = editMode,
            offsetX = config.buttonsOffsetX,
            offsetY = config.buttonsOffsetY,
            onDrag = { dx, dy ->
                onConfigChange(
                    config.copy(
                        buttonsOffsetX = config.buttonsOffsetX + dx,
                        buttonsOffsetY = config.buttonsOffsetY + dy
                    )
                )
            },
            onMaskChanged = { updateCluster(it) },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun ArcadeButtonCluster(
    scale: Float,
    activeMask: Int,
    editMode: Boolean,
    offsetX: Float,
    offsetY: Float,
    onDrag: (Float, Float) -> Unit,
    onMaskChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonBounds = remember { mutableStateMapOf<Int, Rect>() }
    val btnSize = (54f * scale).dp
    val rowBounds = remember { mutableStateMapOf<Int, Rect>() }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .then(
                if (editMode) {
                    Modifier.pointerInput(offsetX, offsetY) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                } else {
                    Modifier.pointerInput(scale) {
                        awaitPointerEventScope {
                            val activePointers = mutableMapOf<PointerId, Offset>()
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    change.consume()
                                    if (change.pressed) {
                                        activePointers[change.id] = change.position
                                    } else {
                                        activePointers.remove(change.id)
                                    }
                                }
                                var newMask = 0
                                for (pos in activePointers.values) {
                                    for ((bit, rect) in buttonBounds) {
                                        // Hit-box circular con margen cómodo para pulsar 2 botones a la vez
                                        val center = rect.center
                                        val hitR = (rect.width.coerceAtLeast(rect.height) * 0.62f)
                                        if (hypot(pos.x - center.x, pos.y - center.y) <= hitR) {
                                            newMask = newMask or bit
                                        }
                                    }
                                }
                                onMaskChanged(newMask)
                            }
                        }
                    }
                }
            )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy((10f * scale).dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy((12f * scale).dp),
                modifier = Modifier.onGloballyPositioned { coords ->
                    rowBounds[0] = coords.boundsInParent()
                }
            ) {
                val rOffset = rowBounds[0]?.topLeft ?: Offset.Zero
                ArcadeCircleVisual(
                    label = "Y",
                    size = btnSize,
                    accent = ArcadeMagenta,
                    pressed = (activeMask and RetroJoypad.Y) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.Y] = rect.translate(rOffset) }
                )
                ArcadeCircleVisual(
                    label = "X",
                    size = btnSize,
                    accent = ArcadeMagenta,
                    pressed = (activeMask and RetroJoypad.X) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.X] = rect.translate(rOffset) }
                )
                ArcadeCircleVisual(
                    label = "L",
                    size = btnSize,
                    accent = ArcadeOrange,
                    pressed = (activeMask and RetroJoypad.L) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.L] = rect.translate(rOffset) }
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy((12f * scale).dp),
                modifier = Modifier.onGloballyPositioned { coords ->
                    rowBounds[1] = coords.boundsInParent()
                }
            ) {
                val rOffset = rowBounds[1]?.topLeft ?: Offset.Zero
                ArcadeCircleVisual(
                    label = "B",
                    size = btnSize,
                    accent = CoinYellow,
                    pressed = (activeMask and RetroJoypad.B) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.B] = rect.translate(rOffset) }
                )
                ArcadeCircleVisual(
                    label = "A",
                    size = btnSize,
                    accent = CoinYellow,
                    pressed = (activeMask and RetroJoypad.A) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.A] = rect.translate(rOffset) }
                )
                ArcadeCircleVisual(
                    label = "R",
                    size = btnSize,
                    accent = ArcadeOrange,
                    pressed = (activeMask and RetroJoypad.R) != 0,
                    editMode = editMode,
                    onPositioned = { rect -> buttonBounds[RetroJoypad.R] = rect.translate(rOffset) }
                )
            }
        }
    }
}

@Composable
private fun ArcadeCircleVisual(
    label: String,
    size: Dp,
    accent: Color,
    pressed: Boolean,
    editMode: Boolean,
    onPositioned: (Rect) -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { coords -> onPositioned(coords.boundsInParent()) }
            .background(
                color = if (pressed) accent else LeatherBlack.copy(alpha = 0.82f),
                shape = CircleShape
            )
            .border(
                width = 2.dp,
                color = if (editMode) CoinYellow else accent,
                shape = CircleShape
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
    var mask = 0
    if (angle in -67.5..67.5) mask = mask or RetroJoypad.RIGHT
    if (angle >= 112.5 || angle <= -112.5) mask = mask or RetroJoypad.LEFT
    if (angle in 22.5..157.5) mask = mask or RetroJoypad.DOWN
    if (angle in -157.5..-22.5) mask = mask or RetroJoypad.UP
    return mask to clamped
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
