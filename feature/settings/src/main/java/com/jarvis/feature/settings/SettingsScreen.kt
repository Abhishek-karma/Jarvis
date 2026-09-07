package com.jarvis.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.core.preferences.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefsState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }


    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val q = query.trim().lowercase()
    fun matches(vararg keys: String): Boolean = q.isEmpty() || keys.any { it.contains(q) }


    val sectionKeys =
        listOf(
            listOf("mode", "local", "cloud", "providers", "internet", "offline", "on-device", "routing"),
            listOf("appearance", "theme", "light", "dark", "system", "display"),
            listOf("reasoning", "think", "thinking", "off", "auto", "on"),
            listOf("agent", "cautious", "tools", "step cap", "limit"),
            listOf("about", "version", "privacy", "credits", "jarvis", "permissions", "microphone", "camera", "notifications"),
        )

    val themeLabel =
        when (prefs.themeMode) {
            ThemeMode.SYSTEM -> "System"
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
        }
            val thinkLabel =
        when (prefs.thinkMode) {
            ThinkMode.OFF -> "Off"
            ThinkMode.AUTO -> "Auto"
            ThinkMode.ON -> "On"
        }



    val context = LocalContext.current
    val grantedCount = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.CALL_PHONE,
    ).count { perm ->
    ContextCompat.checkSelfPermission(
    context, perm,
    ) == PackageManager.PERMISSION_GRANTED
    }
    val permissionSubtitle = "$grantedCount of 6 granted"

    Scaffold(
        topBar = {
            JarvisHeader(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->


        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                    },
        ) {
            SettingsSearchField(query = query, onQueryChange = { query = it })
            Spacer(Modifier.height(Spacing.md))


            if (matches("mode", "cloud", "providers", "routing", "local", "internet", "offline", "on-device")) {
                JarvisListSection(title = "Providers") {
                    NavRow(
                        icon = Icons.Outlined.Layers,
                        title = "Providers",
                        subtitle = "Add or manage LLM providers",
                        tinted = true,
                        onClick = onOpenProviders,
                    )
                }
            }


            if (matches("appearance", "theme", "light", "dark", "system", "display")) {
                JarvisListSection(title = "Appearance") {
                    ExpandGroup(
                        title = "Appearance",
                        subtitle = "How Jarvis looks",
                        value = themeLabel,
                        icon = Icons.Outlined.DarkMode,
                        expandedBySearch = q.isNotEmpty(),
                    ) {
                        OptionDivider(start = 60.dp)
                        RadioOption(
                            label = "System",
                            subtitle = "Follow the device dark-mode setting",
                            selected = prefs.themeMode == ThemeMode.SYSTEM,
                            onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                        )
                        RadioOption(
                            label = "Light",
                            subtitle = "Warm cream canvas",
                            selected = prefs.themeMode == ThemeMode.LIGHT,
                            onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                        )
                        RadioOption(
                            label = "Dark",
                            subtitle = "Warm-ink canvas",
                            selected = prefs.themeMode == ThemeMode.DARK,
                            onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                        )
                    }
                }
            }


            if (matches("reasoning", "think", "thinking", "off", "auto", "on")) {
                JarvisListSection(title = "Reasoning") {
                    ExpandGroup(
                        title = "Reasoning",
                        subtitle = "How hard Jarvis thinks",
                        value = thinkLabel,
                        icon = Icons.Outlined.Psychology,
                        expandedBySearch = q.isNotEmpty(),
                    ) {
                        OptionDivider(start = 60.dp)
                        RadioOption(
                            label = "Off",
                            subtitle = "Never request reasoning tokens",
                            selected = prefs.thinkMode == ThinkMode.OFF,
                            onClick = { viewModel.setThinkMode(ThinkMode.OFF) },
                        )
                        RadioOption(
                            label = "Auto",
                            subtitle = "Math, code and creative asks think; chat doesn't",
                            selected = prefs.thinkMode == ThinkMode.AUTO,
                            onClick = { viewModel.setThinkMode(ThinkMode.AUTO) },
                        )
                        RadioOption(
                            label = "On",
                            subtitle = "Always request reasoning",
                            selected = prefs.thinkMode == ThinkMode.ON,
                            onClick = { viewModel.setThinkMode(ThinkMode.ON) },
                        )
                    }
                }
            }


            if (matches("agent", "cautious", "tools", "step cap", "limit")) {
                JarvisListSection(title = "Agent") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 62.dp)
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        JarvisIconTile(Icons.Outlined.Shield)
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Cautious mode",
                                style = JarvisText.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Confirm every tool call, not just sensitive ones",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefs.cautiousModeEnabled,
                            onCheckedChange = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                                viewModel.setCautiousMode(it)
                            },
                            modifier = Modifier.semantics { contentDescription = "Cautious mode" },
                        )
                    }
                    OptionDivider()
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        JarvisIconTile(Icons.Outlined.BarChart)
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Agent step cap: ${prefs.agentStepCap}",
                                style = JarvisText.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Maximum reasoning steps per agent run (5–30, hard ceiling 40)",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = prefs.agentStepCap.toFloat(),
                                onValueChange = { viewModel.setAgentStepCap(it.toInt()) },
                                valueRange = 5f..30f,
                                steps = 24,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }


            if (matches("about", "version", "privacy", "credits", "jarvis", "permissions", "microphone", "camera", "notifications", "update")) {
                JarvisListSection(title = "System") {
                    NavRow(
                        icon = Icons.Outlined.Mic,
                        title = "Permissions",
                        subtitle = permissionSubtitle,
                        onClick = onOpenPermissions,
                    )
                    NavRow(
                        icon = Icons.Outlined.Info,
                        title = "About Jarvis",
                        subtitle = "Version, privacy, credits",
                        onClick = onOpenAbout,
                    )
                    UpdateRow(
                        state = viewModel.updateCheck.collectAsStateWithLifecycle().value,
                        onCheck = viewModel::checkForUpdates,
                    )
                }
            }

            if (q.isNotEmpty() && sectionKeys.none { keys -> keys.any { it.contains(q) } }) {
                JarvisEmptyState(
                    title = "No matching settings",
                    hint = "Try “theme”, “reasoning” or “providers”.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                "Jarvis v${prefs.appVersion} · settings persist on-device",
                style = JarvisText.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xl),
            )
        }
    }
}


@Composable
private fun UpdateRow(
    state: UpdateCheckState,
    onCheck: () -> Unit,
) {
    val subtitle =
        when (state) {
            UpdateCheckState.Idle -> "Check now — you'll also be notified when one lands"
            UpdateCheckState.Checking -> "Checking…"
            is UpdateCheckState.Available -> "Jarvis ${state.version} available — tap to download"
            UpdateCheckState.UpToDate -> "You're on the latest version"
            UpdateCheckState.Failed -> "Couldn't check — are you online?"
        }
    NavRow(
        icon = Icons.Outlined.SystemUpdate,
        title = "App updates",
        subtitle = subtitle,
        onClick = onCheck,
    )
}

/** Search field — the HTML `.search`: borderless input on the raised surface. */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = JarvisText.Body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier =
                    Modifier
                        .weight(1f)

                        .semantics { contentDescription = "Search settings" },
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search settings",
                                style = JarvisText.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}


@Composable
private fun ExpandGroup(
    title: String,
    subtitle: String,
    value: String,
    icon: ImageVector,
    expandedBySearch: Boolean,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val isOpen = open || expandedBySearch
    val chevronRotation by animateFloatAsState(if (isOpen) 90f else 0f, label = "chevron")
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .clickable {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        open = !open
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JarvisIconTile(icon)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = JarvisText.Body, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                value,
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isOpen) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }
        AnimatedVisibility(visible = isOpen) { Column { content() } }
    }
}

/** Hairline divider between card rows, inset to [start]. */
@Composable
private fun OptionDivider(start: Dp = Spacing.lg) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = start),
    )
}

/** Radio option row — the HTML `.opts` rows: indented, with the HTML radio dot. */
@Composable
private fun RadioOption(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onClick()
                }

                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                }
                .padding(start = 60.dp, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioDot(selected)
        Column {
            Text(label, style = JarvisText.Body, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The HTML `.radio`: 22dp circle with an accent ring + accent dot when selected. */
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Indented navigation row inside an expanded group. */
@Composable
private fun OptionNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onClick()
                }
                .padding(start = 60.dp, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = JarvisText.Body, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Indented switch row inside an expanded group. */
@Composable
private fun OptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 60.dp, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = JarvisText.Body, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                focusManager.clearFocus()
                keyboard?.hide()
                onCheckedChange(it)
            },
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** Top-level card row with an icon tile — the HTML `.row` with `.tile`. */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tinted: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .clickable {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onClick()
                }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarvisIconTile(icon, tinted = tinted)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = JarvisText.Body, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
