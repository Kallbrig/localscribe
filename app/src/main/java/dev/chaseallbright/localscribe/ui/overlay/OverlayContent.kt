package dev.chaseallbright.localscribe.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.dictation.DictationUiState

/**
 * Room around every shape inside its window, so shadows are drawn rather than clipped at the
 * window's edge -- the square-looking shadow behind the pill.
 */
val SHADOW_PADDING = 12.dp

val DISMISS_TARGET_SIZE = 64.dp
private val DOT_SIZE = 16.dp
private val PILL_BUTTON_SIZE = 40.dp

/** A touch held still this long starts a hold-to-record; moving first makes it a drag. */
const val HOLD_TO_RECORD_MS = 250L

/** Recording red. Never faded by the opacity setting: it is the live-microphone signal. */
private val RECORDING_RED = Color(0xFFE53935)

/** Everything the idle bubble's single gesture handler can report. */
class OverlayGestures(
    val onTap: (onDot: Boolean) -> Unit,
    val onDragStart: () -> Unit,
    val onDragMove: () -> Unit,
    /** [dropped] is false when the system cancelled the gesture: never treat that as a drop. */
    val onDragEnd: (dropped: Boolean) -> Unit,
    val onHoldStart: () -> Unit,
    val onHoldEnd: () -> Unit
)

private enum class GestureKind { TAP, DRAG, HOLD, GONE }

/**
 * The overlay window's content, centred with [SHADOW_PADDING] all round.
 *
 * The gesture handler sits here, on the root, rather than on the bubble: a hold replaces the
 * bubble with the pill while the finger is still down, and a handler attached to the bubble would
 * be disposed with it and never see the release.
 */
@Composable
fun OverlayContent(
    state: DictationUiState,
    shape: OverlayShape,
    style: BubbleStyle,
    gestures: OverlayGestures,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val idle = state == DictationUiState.Idle || state is DictationUiState.Error
    val currentIdle by rememberUpdatedState(idle)
    val currentShape by rememberUpdatedState(shape)
    val currentGestures by rememberUpdatedState(gestures)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(SHADOW_PADDING)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // Only gestures that begin on the idle bubble or dot. The tap-mode pill's
                    // buttons have their own handlers and consume their touches.
                    if (!currentIdle) return@awaitEachGesture
                    val onDot = currentShape == OverlayShape.DOT
                    val g = currentGestures

                    val kind = withTimeoutOrNull(HOLD_TO_RECORD_MS) {
                        var result = GestureKind.GONE
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (!change.pressed) {
                                result = GestureKind.TAP
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                result = GestureKind.DRAG
                                break
                            }
                        }
                        result
                    } ?: GestureKind.HOLD

                    when (kind) {
                        GestureKind.TAP -> g.onTap(onDot)
                        GestureKind.GONE -> Unit
                        GestureKind.DRAG -> {
                            var dropped = false
                            g.onDragStart()
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        dropped = true
                                        break
                                    }
                                    change.consume()
                                    // Positions come from raw screen coordinates in the service:
                                    // this window moves under the finger, so local ones drift.
                                    g.onDragMove()
                                }
                            } finally {
                                g.onDragEnd(dropped)
                            }
                        }
                        GestureKind.HOLD -> if (onDot) {
                            // The dot never opens the microphone, held or tapped: it only expands.
                            g.onTap(true)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                        } else {
                            g.onHoldStart()
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            } finally {
                                // No cancel while holding, as agreed: however the hold ends, it
                                // transcribes.
                                g.onHoldEnd()
                            }
                        }
                    }
                }
            }
    ) {
        when (shape) {
            OverlayShape.NONE -> Unit
            OverlayShape.DOT, OverlayShape.BUBBLE -> BubbleFace(
                style = style,
                collapsed = shape == OverlayShape.DOT,
                modifier = Modifier.semantics {
                    contentDescription = if (shape == OverlayShape.DOT) "Show dictation button" else "Start dictation"
                }
            )
            OverlayShape.PILL -> RecordingPill(style, onConfirm = onConfirm, onCancel = onCancel)
            OverlayShape.HOLD_PILL -> HoldPill(style)
            OverlayShape.PROCESSING -> ProcessingPill(style)
        }
    }
}

/**
 * A surface whose fill and shadow both follow the opacity setting. Opacity is applied through the
 * colours rather than a layer: a layer with alpha renders offscreen at its own size and cuts the
 * shadow off square.
 */
private fun Modifier.overlaySurface(color: Color, alpha: Float, elevation: Dp, shape: Shape): Modifier =
    this
        .outlineShadow(elevation, shape, alpha)
        .background(color.copy(alpha = alpha), shape)

/**
 * A drop shadow drawn only outside [shape].
 *
 * Android's elevation shadow (`Modifier.shadow`) is rendered beneath the whole shape, and for a
 * circle its dark core is a polygon. Under an opaque fill that is invisible; under a translucent
 * one it shows through as an octagon behind the mic. Here the shape's own outline is clipped out
 * before the shadow is drawn, so nothing is ever painted under the fill. `setShadowLayer` on a
 * path is hardware-accelerated from API 28, which is this app's minimum.
 */
private fun Modifier.outlineShadow(elevation: Dp, shape: Shape, alpha: Float): Modifier =
    // Cached per size: the pills animate continuously, and the path and paint never change.
    drawWithCache {
        val blur = elevation.toPx()
        val path = Path().apply {
            addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
        }.asAndroidPath()
        val shadowArgb = Color.Black.copy(alpha = (SHADOW_ALPHA * alpha).coerceIn(0f, 1f)).toArgb()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = shadowArgb
            if (blur > 0f) setShadowLayer(blur, 0f, blur / 2f, shadowArgb)
        }
        onDrawBehind {
            if (blur <= 0f || alpha <= 0f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.clipOutPath(path)
                native.drawPath(path, paint)
                native.restore()
            }
        }
    }

/** Peak darkness of a shadow at full opacity, roughly Material's key-plus-ambient at rest. */
private const val SHADOW_ALPHA = 0.35f

/**
 * The idle bubble or its collapsed dot, with no gestures. Shared with the Settings preview so the
 * preview cannot drift from what the overlay draws.
 */
@Composable
fun BubbleFace(style: BubbleStyle, collapsed: Boolean, modifier: Modifier = Modifier) {
    val alpha = BubbleOpacity.alphaOf(style.opacityPercent)
    val color = Color(style.color.argb)
    if (collapsed) {
        Box(modifier = modifier.size(OverlayShape.DOT.widthDp.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(DOT_SIZE).overlaySurface(color, alpha, 3.dp, CircleShape))
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(OverlayShape.BUBBLE.widthDp.dp)
                .overlaySurface(color, alpha, 6.dp, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = Color(style.color.contentArgb).copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun RecordingPill(style: BubbleStyle, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val alpha = BubbleOpacity.alphaOf(style.opacityPercent)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(OverlayShape.PILL.widthDp.dp, OverlayShape.PILL.heightDp.dp)
            .overlaySurface(Color(style.color.argb), alpha, 6.dp, CircleShape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(Icons.Filled.Close, "Cancel dictation", style, alpha, onCancel)
            Box(Modifier.size(24.dp))
            PillButton(Icons.Filled.Check, "Confirm dictation", style, alpha, onConfirm)
        }
        // Drawn on top and outside the faded surface, so it is always fully opaque.
        PulsingDot()
    }
}

/** Held down to record: no buttons, just an equalizer the size of the pill. */
@Composable
private fun HoldPill(style: BubbleStyle) {
    val alpha = BubbleOpacity.alphaOf(style.opacityPercent)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(OverlayShape.HOLD_PILL.widthDp.dp, OverlayShape.HOLD_PILL.heightDp.dp)
            .overlaySurface(Color(style.color.argb), alpha, 6.dp, CircleShape)
            .semantics { contentDescription = "Recording. Release to transcribe." }
    ) {
        Equalizer()
    }
}

@Composable
private fun ProcessingPill(style: BubbleStyle) {
    val alpha = BubbleOpacity.alphaOf(style.opacityPercent)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(OverlayShape.PROCESSING.widthDp.dp)
            .overlaySurface(Color(style.color.argb), alpha, 6.dp, CircleShape)
            .padding(12.dp)
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = Color(style.color.contentArgb).copy(alpha = alpha)
        )
    }
}

/**
 * Raised, not flat: a fill shifted from the pill colour, a top-lit gradient, and its own shadow
 * falling onto the pill.
 */
@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    style: BubbleStyle,
    alpha: Float,
    onClick: () -> Unit
) {
    val fill = Color(style.color.buttonArgb)
    val light = Color(BubbleColor.mix(style.color.buttonArgb, 0xFFFFFFFFL, 0.18))
    val dark = Color(BubbleColor.mix(style.color.buttonArgb, 0xFF000000L, 0.18))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(PILL_BUTTON_SIZE)
            // Stronger than the pill's own, so the buttons read as raised above it.
            .outlineShadow(5.dp, CircleShape, alpha * 1.4f)
            .background(
                Brush.verticalGradient(
                    listOf(light.copy(alpha = alpha), fill.copy(alpha = alpha), dark.copy(alpha = alpha))
                ),
                CircleShape
            )
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color(style.color.contentArgb).copy(alpha = alpha)
        )
    }
}

/**
 * Pulses in size, not alpha, so it stays fully opaque as agreed. The white ring keeps it visible
 * on the red swatch.
 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Box(
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(2.dp, Color.White, CircleShape)
            .padding(2.dp)
            .background(RECORDING_RED, CircleShape)
    )
}

/** Five bars rising and falling out of step. Decorative, not driven by the audio level. */
@Composable
private fun Equalizer() {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val periods = listOf(420, 560, 360, 500, 440)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(36.dp)
    ) {
        periods.forEachIndexed { index, period ->
            val fraction by transition.animateFloat(
                initialValue = if (index % 2 == 0) 0.25f else 1f,
                targetValue = if (index % 2 == 0) 1f else 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = period, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(36.dp * fraction)
                    .background(RECORDING_RED, RoundedCornerShape(3.dp))
            )
        }
    }
}

/** The drag-to-dismiss target, drawn in its own overlay window at the bottom of the screen. */
@Composable
fun DismissTarget(highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        // Opaque: a translucent fill would show its own elevation shadow through it.
        color = if (highlighted) MaterialTheme.colorScheme.error else Color(0xFF303030),
        shadowElevation = 4.dp,
        modifier = Modifier
            .size(if (highlighted) DISMISS_TARGET_SIZE else DISMISS_TARGET_SIZE - 8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Drop here to hide the dictation button",
                tint = Color.White
            )
        }
    }
}
