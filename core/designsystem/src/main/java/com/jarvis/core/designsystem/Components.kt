package com.jarvis.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared header composable for screen titles, consistent across features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Text(text = title, style = JarvisText.ConvTitle)
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ),
        modifier = modifier,
    )
}

/**
 * The 6-point asterisk-meets-star logomark (drawn on Canvas).
 * Always rendered in the primary accent color.
 */
@Composable
fun JarvisMark(
    size: Dp = 18.dp,
    color: Color = JarvisColors.Accent.orange,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size).semantics { contentDescription = "Jarvis" }) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = minOf(cx, cy)
        val inner = r * 0.18f
        val path = Path()
        repeat(6) { i ->
            val a = (Math.PI / 3 * i - Math.PI / 2).toFloat()
            val tip = Offset(cx + r * cos(a), cy + r * sin(a))
            val la = a + (Math.PI / 2).toFloat()
            val lBase = Offset(cx + inner * cos(la), cy + inner * sin(la))
            val rBase = Offset(cx - inner * cos(la), cy - inner * sin(la))
            path.moveTo(lBase.x, lBase.y)
            path.quadraticBezierTo((lBase.x + tip.x) / 2 + 1, (lBase.y + tip.y) / 2 + 1, tip.x, tip.y)
            path.quadraticBezierTo((tip.x + rBase.x) / 2 - 1, (tip.y + rBase.y) / 2 - 1, rBase.x, rBase.y)
            path.close()
        }
        drawPath(path, color)
    }
}

/**
 * The streaming response cursor — an 8×18dp accent caret that blinks at a
 * 600ms cycle (300 on / 300 off) while the assistant is generating.
 */
@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.cursorBlinkHalfMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )
    Box(
        modifier
            .size(width = 8.dp, height = 18.dp)
            .clip(RoundedCornerShape(1.dp))
            .alpha(alpha)
            .background(JarvisColors.Accent.orange)
            .semantics { contentDescription = "Jarvis is responding" },
    )
}


/**
 * The circular send button — 40dp accent circle with a white up-arrow.
 * While streaming it becomes a stop control.
 * Press: scale 0.94 + medium haptic; disabled state uses the muted surface fill.
 */
@Composable
fun JarvisSendButton(
    enabled: Boolean,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressScale else 1f,
        label = "sendPress",
    )
    val canAct = if (isStreaming) true else enabled

    Box(
        modifier
            .size(40.dp)
            .scale(scale)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                when {
                    isStreaming -> JarvisColors.Accent.orange
                    !enabled -> androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                    pressed -> JarvisColors.Accent.orangePressed
                    else -> JarvisColors.Accent.orange
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = canAct,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress) // ~iOS medium impact
                if (isStreaming) onCancel() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isStreaming) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (isStreaming) "Stop generating" else "Send",
            tint = if (enabled || isStreaming) {
                JarvisColors.Accent.onOrange
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
