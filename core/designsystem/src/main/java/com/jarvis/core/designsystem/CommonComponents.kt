package com.jarvis.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

/**
 * The single source of truth for common building blocks shared across screens:
 * grouped list sections, list rows, empty states, and confirmation dialogs.
 *
 * Everything here renders from the Jarvis token system only (Spacing / Radius /
 * JarvisShapes / JarvisText / MaterialTheme.colorScheme) so every screen that
 * uses these pieces stays visually identical by construction.
 */

/**
 * Grouped list card — section label above a surfaceContainerLow card with
 * rounded corners. The same treatment for Settings, About, and any future list
 * screen. The label aligns to the card's content (Spacing.huge).
 */
@Composable
fun JarvisListSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(top = Spacing.lg)) {
        Text(
            text = title,
            style = JarvisText.SectionHeader,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.huge),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = JarvisShapes.card,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
        ) {
            Column { content() }
        }
    }
}

/**
 * One row inside a [JarvisListSection]: title, optional subtitle, optional
 * trailing icon. Pass [onClick] for tappable rows; omit it for static rows.
 * [dividerBelow] draws the leading-inset divider between sibling rows — set it
 * on every row except the last.
 */
@Composable
fun JarvisListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    contentDescription: String? = null,
    dividerBelow: Boolean = false,
) {
    val modifier =
        if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = JarvisText.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (dividerBelow) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = Spacing.lg),
        )
    }
}

/**
 * Centered empty state: optional icon, a short title, one muted hint line —
 * the one component for "no data yet" screens (providers, history, searches).
 */
@Composable
fun JarvisEmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl, vertical = Spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.huge),
            )
        }
        Text(
            text = title,
            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = hint,
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The app's single confirmation dialog — used for every destructive action
 * (delete provider, delete conversation). Destructive intent turns the confirm
 * label red; dismiss is always "Cancel".
 */
@Composable
fun JarvisConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = JarvisText.ConvTitle) },
        text = {
            Text(
                message,
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color =
                        if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Compact label pill for trailing metadata ("Default") — one shape, one style,
 * wherever a small badge chip is needed.
 */
@Composable
fun JarvisBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        shape = JarvisShapes.chip,
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = JarvisText.Caption,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}
