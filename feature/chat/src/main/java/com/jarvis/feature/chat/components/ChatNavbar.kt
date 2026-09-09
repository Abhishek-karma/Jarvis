package com.jarvis.feature.chat.components

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.core.common.Message
import com.jarvis.core.common.MessageRole
import com.jarvis.core.common.RoutingOverride
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import com.jarvis.core.designsystem.JarvisHeaderIconButton
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

@Composable
fun ChatNavbar(
    title: String,
    routingOverride: RoutingOverride,
    messages: List<Message>,
    onOpenDrawer: () -> Unit,
    onRoutingChange: (RoutingOverride) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVoiceMode: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JarvisHeaderIconButton(
                icon = Icons.Default.Menu,
                contentDescription = "Open history",
                onClick = onOpenDrawer,
            )

            Text(
                text = title,
                style = JarvisText.ConvTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            RouteChip(
                selected = routingOverride,
                onSelect = onRoutingChange,
            )

            Box {
                JarvisHeaderIconButton(
                    icon = Icons.Default.MoreHoriz,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                JarvisDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    JarvisDropdownItem(
                        text = "Voice mode",
                        onClick = {
                            menuOpen = false
                            onOpenVoiceMode()
                        },
                        dividerBelow = true,
                    )
                    JarvisDropdownItem(
                        text = "Share conversation",
                        onClick = {
                            menuOpen = false
                            val transcript =
                                messages.joinToString("\n\n") { msg ->
                                    val who = if (msg.role == MessageRole.USER) "You" else "Jarvis"
                                    "$who: ${msg.content}"
                                }
                            if (transcript.isNotBlank()) {
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, transcript)
                                    }
                                context.startActivity(
                                    Intent.createChooser(intent, "Share conversation"),
                                )
                            }
                        },
                        dividerBelow = true,
                    )
                    JarvisDropdownItem(
                        text = "Settings",
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }
    }
}
