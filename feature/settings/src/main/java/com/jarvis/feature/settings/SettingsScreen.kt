package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ThinkMode
import com.jarvis.core.designsystem.JarvisBadge
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisListRow
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefsState.collectAsStateWithLifecycle()

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
        // Only settings that actually work are listed — no "coming soon" rows
        // teasing features the build can't open.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            JarvisListSection(title = "Models") {
                JarvisListRow(
                    title = "Providers",
                    subtitle = "Add or manage LLM providers",
                    onClick = onOpenProviders,
                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }

            // ---- Appearance (persisted via UserPreferencesRepository) ----
            JarvisListSection(title = "Appearance") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RadioRow(
                        label = "System",
                        subtitle = "Follow the device dark-mode setting",
                        selected = prefs.themeMode == ThemeMode.SYSTEM,
                        onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                    )
                    RadioRow(
                        label = "Light",
                        subtitle = "Warm cream canvas",
                        selected = prefs.themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                    )
                    RadioRow(
                        label = "Dark",
                        subtitle = "Warm-ink canvas",
                        selected = prefs.themeMode == ThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                    )
                }
            }

            // ---- Reasoning (think mode) ----
            JarvisListSection(title = "Reasoning") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RadioRow(
                        label = "Off",
                        subtitle = "Never request reasoning tokens",
                        selected = prefs.thinkMode == ThinkMode.OFF,
                        onClick = { viewModel.setThinkMode(ThinkMode.OFF) },
                    )
                    RadioRow(
                        label = "Auto",
                        subtitle = "Math, code and creative asks think; chat doesn't",
                        selected = prefs.thinkMode == ThinkMode.AUTO,
                        onClick = { viewModel.setThinkMode(ThinkMode.AUTO) },
                    )
                    RadioRow(
                        label = "On",
                        subtitle = "Always request reasoning",
                        selected = prefs.thinkMode == ThinkMode.ON,
                        onClick = { viewModel.setThinkMode(ThinkMode.ON) },
                    )
                }
            }

            // ---- Agent ----
            JarvisListSection(title = "Agent") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Cautious mode",
                                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                "Confirm every tool call, not just sensitive ones",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefs.cautiousModeEnabled,
                            onCheckedChange = viewModel::setCautiousMode,
                        )
                    }

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        Text(
                            "Agent step cap: ${prefs.agentStepCap}",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            "Maximum ReAct steps per run (5–30, hard ceiling 40)",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = prefs.agentStepCap.toFloat(),
                            onValueChange = { viewModel.setAgentStepCap(it.toInt()) },
                            valueRange = 5f..30f,
                            steps = 24,
                        )
                    }
                }
            }

            JarvisListSection(title = "About") {
                JarvisListRow(
                    title = "About Jarvis",
                    subtitle = "Version, privacy, credits",
                    onClick = onOpenAbout,
                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    JarvisListRow(
        title = label,
        subtitle = subtitle,
        onClick = onClick,
        trailingContent = {
            if (selected) {
                JarvisBadge(text = "Selected")
            }
        },
    )
}
