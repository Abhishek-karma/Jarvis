package com.jarvis.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisSheetGrip
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteChip(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    Box {
        Text(
            text = routeLabel(selected) + " ▾",
            style = JarvisText.SenderLabel,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .clip(JarvisShapes.pill)
                    .clickable(role = Role.Button) { showSheet = true }
                    .padding(horizontal = Spacing.md, vertical = Spacing.smPlus)
                    .semantics { contentDescription = "Response route, ${routeLabel(selected)}" },
        )

        if (showSheet) {
            RouteSheet(
                selected = selected,
                onSelect = {
                    showSheet = false
                    onSelect(it)
                },
                onDismiss = { showSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSheet(
    selected: RoutingOverride,
    onSelect: (RoutingOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape =
            RoundedCornerShape(
                topStart = Radius.sheet,
                topEnd = Radius.sheet,
            ),
        dragHandle = {
            Box(modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)) {
                JarvisSheetGrip()
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xxl),
        ) {
            Text(
                text = "Response route",
                style = JarvisText.CodeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.xxl)
                        .padding(top = Spacing.md, bottom = Spacing.xs),
            )
            RoutingOverride.entries.forEachIndexed { index, route ->
                RouteOptionRow(
                    route = route,
                    selected = route == selected,
                    showDivider = index < RoutingOverride.entries.lastIndex,
                    onClick = { onSelect(route) },
                )
            }
        }
    }
}

@Composable
fun RouteOptionRow(
    route: RoutingOverride,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val (title, description) =
        when (route) {
            RoutingOverride.AUTO -> "Auto" to "Smart routing per message"
            RoutingOverride.LOCAL -> "On-device" to "Private, works offline"
            RoutingOverride.CLOUD -> "Cloud" to "Your configured provider"
        }
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .clickable(
                        role = Role.RadioButton,
                        enabled = true,
                        onClick = onClick,
                    )
                    .padding(horizontal = Spacing.xxl, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lgPlus),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = JarvisText.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier =
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                JarvisColors.Accent.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        )
                        .semantics {
                            contentDescription = if (selected) "Selected" else "Not selected"
                        },
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
        }
    }
}

fun routeLabel(route: RoutingOverride): String =
    when (route) {
        RoutingOverride.AUTO -> "Auto"
        RoutingOverride.LOCAL -> "On-device"
        RoutingOverride.CLOUD -> "Cloud"
    }
