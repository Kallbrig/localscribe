package dev.chaseallbright.localscribe.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.dictation.DictationUiState

private val BUBBLE_SIZE = 56.dp
private val PILL_HEIGHT = 56.dp
private val PILL_ICON_SIZE = 40.dp

@Composable
fun OverlayContent(
    state: DictationUiState,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onTapBubble: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    when (state) {
        is DictationUiState.Hidden -> Unit
        is DictationUiState.Idle -> IdleBubble(onDrag = onDrag, onTap = onTapBubble)
        is DictationUiState.Recording -> RecordingPill(onConfirm = onConfirm, onCancel = onCancel)
        is DictationUiState.Processing -> ProcessingPill()
        is DictationUiState.Error -> IdleBubble(onDrag = onDrag, onTap = onTapBubble)
    }
}

@Composable
private fun IdleBubble(onDrag: (Float, Float) -> Unit, onTap: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(BUBBLE_SIZE)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onTap() }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Start dictation",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun RecordingPill(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
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
                background = MaterialTheme.colorScheme.errorContainer,
                onClick = onCancel
            )
            Box(
                modifier = Modifier.size(PILL_ICON_SIZE),
                contentAlignment = Alignment.Center
            ) {
                PulsingDot()
            }
            PillButton(
                icon = Icons.Filled.Check,
                contentDescription = "Confirm dictation",
                background = MaterialTheme.colorScheme.primaryContainer,
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun ProcessingPill() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.size(BUBBLE_SIZE)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    background: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier
            .size(PILL_ICON_SIZE)
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

@Composable
private fun PulsingDot() {
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
            .size(14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.error, CircleShape)
    )
}
