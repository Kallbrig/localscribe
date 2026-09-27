package dev.chaseallbright.localscribe.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.dictation.DictationUiState

val BUBBLE_SIZE = 56.dp

/** The dot's window. Larger than the dot itself so it stays tappable, small enough not to block much. */
val DOT_TOUCH_SIZE = 28.dp
private val DOT_SIZE = 16.dp
private val PILL_HEIGHT = 56.dp
private val PILL_ICON_SIZE = 40.dp

/** Drag callbacks shared by the bubble and the dot. */
class DragHandlers(
    val onDragStart: () -> Unit,
    val onDrag: (dx: Float, dy: Float) -> Unit,
    val onDragEnd: () -> Unit
)

@Composable
fun OverlayContent(
    state: DictationUiState,
    style: BubbleStyle,
    collapsed: Boolean,
    drag: DragHandlers,
    onTapBubble: () -> Unit,
    onTapDot: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    // Opacity covers every state, pill and spinner included, as agreed.
    Box(modifier = Modifier.graphicsLayer { alpha = BubbleOpacity.alphaOf(style.opacityPercent) }) {
        when (state) {
            is DictationUiState.Hidden -> Unit
            is DictationUiState.Idle, is DictationUiState.Error ->
                IdleBubble(style, collapsed, drag, onTapBubble = onTapBubble, onTapDot = onTapDot)
            is DictationUiState.Recording -> RecordingPill(style, onConfirm = onConfirm, onCancel = onCancel)
            is DictationUiState.Processing -> ProcessingPill(style)
        }
    }
}

/**
 * The idle bubble or its collapsed dot, with no gestures. Shared with the Settings preview so the
 * preview cannot drift from what the overlay draws.
 */
@Composable
fun BubbleFace(style: BubbleStyle, collapsed: Boolean, modifier: Modifier = Modifier) {
    val background = Color(style.color.argb)
    if (collapsed) {
        Box(modifier = modifier.size(DOT_TOUCH_SIZE), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = background,
                shadowElevation = 3.dp,
                modifier = Modifier.size(DOT_SIZE)
            ) {}
        }
    } else {
        Surface(
            shape = CircleShape,
            color = background,
            shadowElevation = 6.dp,
            modifier = modifier.size(BUBBLE_SIZE)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color(style.color.contentArgb)
                )
            }
        }
    }
}

@Composable
private fun IdleBubble(
    style: BubbleStyle,
    collapsed: Boolean,
    drag: DragHandlers,
    onTapBubble: () -> Unit,
    onTapDot: () -> Unit
) {
    BubbleFace(
        style = style,
        collapsed = collapsed,
        modifier = Modifier
            .semantics {
                contentDescription = if (collapsed) "Show dictation button" else "Start dictation"
            }
            .pointerInput(drag) {
                detectDragGestures(
                    onDragStart = { drag.onDragStart() },
                    onDragEnd = { drag.onDragEnd() },
                    onDragCancel = { drag.onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    drag.onDrag(dragAmount.x, dragAmount.y)
                }
            }
            // Keyed on `collapsed` so the handler is rebuilt when the meaning of a tap changes.
            .pointerInput(collapsed) {
                // A tap on the dot only expands it. The dot is a small target, and a mis-tap
                // must never open the microphone.
                detectTapGestures { if (collapsed) onTapDot() else onTapBubble() }
            }
    )
}

@Composable
private fun RecordingPill(style: BubbleStyle, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val content = Color(style.color.contentArgb)
    Surface(
        shape = CircleShape,
        color = Color(style.color.argb),
        shadowElevation = 6.dp,
        modifier = Modifier.size(width = PILL_ICON_SIZE * 3, height = PILL_HEIGHT)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(
                icon = Icons.Filled.Close,
                contentDescription = "Cancel dictation",
                content = content,
                onClick = onCancel
            )
            Box(
                modifier = Modifier.size(PILL_ICON_SIZE),
                contentAlignment = Alignment.Center
            ) {
                PulsingDot(ring = content)
            }
            PillButton(
                icon = Icons.Filled.Check,
                contentDescription = "Confirm dictation",
                content = content,
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun ProcessingPill(style: BubbleStyle) {
    Surface(
        shape = CircleShape,
        color = Color(style.color.argb),
        shadowElevation = 6.dp,
        modifier = Modifier.size(BUBBLE_SIZE)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
            CircularProgressIndicator(strokeWidth = 3.dp, color = Color(style.color.contentArgb))
        }
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    content: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        // A translucent wash of the glyph colour reads as a button on any swatch.
        color = content.copy(alpha = 0.18f),
        modifier = Modifier
            .size(PILL_ICON_SIZE)
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = content)
        }
    }
}

/**
 * Stays red whatever the chosen colour: it is the live-microphone signal. The ring keeps it
 * visible on a red swatch, where the dot alone would vanish into the pill.
 */
@Composable
private fun PulsingDot(ring: Color) {
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(2.dp, ring, CircleShape)
            .padding(2.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.error, CircleShape)
    )
}

/** The drag-to-dismiss target, drawn in its own overlay window at the bottom of the screen. */
@Composable
fun DismissTarget(highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.error else Color(0xCC303030),
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

val DISMISS_TARGET_SIZE = 64.dp
