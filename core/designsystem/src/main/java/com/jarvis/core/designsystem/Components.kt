package com.jarvis.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
) {
    TopAppBar(
        title = {
            Text(text = title, style = JarvisText.ConvTitle)
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
            ),
        modifier = modifier,
    )
}


@Composable
fun JarvisMark(
    size: Dp = 18.dp,
    color: Color = JarvisColors.Accent.primary,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size).semantics { contentDescription = "Jarvis" }) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = minOf(cx, cy)




        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - r * 0.44f, cy - r * 0.44f),
            size = Size(r * 0.88f, r * 0.88f),
            style = Stroke(width = r * 0.07f, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - r * 0.28f, cy - r * 0.28f),
            size = Size(r * 0.56f, r * 0.56f),
            style = Stroke(width = r * 0.09f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = color,
            radius = r * 0.13f,
            center = Offset(cx, cy),
        )
    }
}


@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(Motion.CURSOR_BLINK_HALF_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "blink",
    )
    Box(
        modifier
            .size(width = 8.dp, height = 18.dp)
            .clip(RoundedCornerShape(1.dp))
            .alpha(alpha)
            .background(JarvisColors.Accent.primary)
            .semantics { contentDescription = "Jarvis is responding" },
    )
}


@Composable
fun JarvisSendButton(
    enabled: Boolean,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        label = "sendPress",
    )
    val canAct = if (isStreaming) true else enabled
    val stopMode = isStreaming && !enabled

    Box(
        modifier
            .size(40.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                when {
                    stopMode -> JarvisColors.Accent.primary
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant
                    pressed -> JarvisColors.Accent.primaryPressed
                    else -> MaterialTheme.colorScheme.onSurface
                },
            ).clickable(
                interactionSource = interaction,
                indication = null,
                enabled = canAct,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (stopMode) onCancel() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (stopMode) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (stopMode) "Stop generating" else "Send",
            tint =
                if (enabled || isStreaming) {
                    if (stopMode || pressed) {
                        JarvisColors.Accent.onPrimary
                    } else {
                        MaterialTheme.colorScheme.background
                    }
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(20.dp),
        )
    }
}


@Composable
fun JarvisSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = JarvisColors.Dark.surface,
            contentColor = JarvisColors.Dark.textPrimary,
            actionColor = JarvisColors.Accent.primarySoft,
            dismissActionContentColor = JarvisColors.Dark.textSecondary,
            shape = JarvisShapes.chip,
        )
    }
}


@Composable
fun JarvisLoader(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = JarvisColors.Accent.primary,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        color = color,
    )
}


@Composable
fun JarvisScreenLoader(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        JarvisMark(size = 32.dp)
        JarvisLoader(size = 24.dp)
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
fun JarvisModePill(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    val borderColor =
        if (active) JarvisColors.Accent.primary else MaterialTheme.colorScheme.outlineVariant
    val fillColor =
        if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.background
        }
    val contentColor =
        if (active) {
            JarvisColors.Accent.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = JarvisShapes.pill,
        color = fillColor,
        border = BorderStroke(1.dp, borderColor),
        modifier =
            modifier
                .clip(JarvisShapes.pill)
                .clickable(role = Role.Switch, onClick = onClick)
                .semantics {


                    contentDescription = text
                    stateDescription = if (active) "on" else "off"
                },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.smPlus),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(Spacing.lg),
                )
            }
            Text(
                text = text,
                style = JarvisText.SenderLabel,
                color = contentColor,
            )
        }
    }
}


@Composable
fun JarvisHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val label = contentDescription
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Spacing.xlPlus),
        )
    }
}

/** The grab handle at the top of a bottom sheet — 42×5dp rounded bar on the deep surface. */
@Composable
fun JarvisSheetGrip(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .width(42.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}


@Composable
fun JarvisDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minimumWidth: Dp = 230.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val gapPx = with(LocalDensity.current) { Spacing.sm.toPx().toInt() }
    val positionProvider = remember(gapPx) { MenuPositionProvider(gapPx) }
    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier =
                modifier
                    .shadow(
                        elevation = 8.dp,
                        shape = JarvisShapes.card,
                        ambientColor = Color(0x0D111111),
                        spotColor = Color(0x14111111),
                    )
                    .clip(JarvisShapes.card)
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, JarvisShapes.card)
                    .widthIn(min = minimumWidth)
                    .width(IntrinsicSize.Min),
        ) {
            content()
        }
    }
}


private class MenuPositionProvider(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            (anchorBounds.right - popupContentSize.width)
                .coerceIn(0, windowSize.width - popupContentSize.width)
        val y =
            (anchorBounds.bottom + gapPx)
                .coerceAtMost(windowSize.height - popupContentSize.height)
        return IntOffset(x, y)
    }
}


@Composable
fun JarvisDropdownItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    dividerBelow: Boolean = false,
) {
    val color =
        if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val rowPadding =
        if (leadingIcon != null) {
            Spacing.xl
        } else {
            18.dp
        }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = rowPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(Spacing.xl),
                )
            }
            Text(
                text = text,
                style = JarvisText.Body,
                color = color,
                modifier = Modifier.weight(1f),
            )
        }
        if (dividerBelow) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
