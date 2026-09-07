package com.jarvis.feature.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.core.common.LocalBenchmarkResult
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocalModelConfigSection(
    localInternetAccess: Boolean,
    onToggleInternetAccess: (Boolean) -> Unit,
    localTemperature: Float,
    onTemperatureChange: (Float) -> Unit,
    localTopP: Float,
    onTopPChange: (Float) -> Unit,
    localMaxTokens: Int,
    onMaxTokensChange: (Int) -> Unit,
    localThreads: Int,
    onThreadsChange: (Int) -> Unit,
    localPrewarm: Boolean,
    onTogglePrewarm: (Boolean) -> Unit,
    benchmarkResult: LocalBenchmarkResult?,
    isBenchmarking: Boolean,
    benchmarkProgress: Float,
    benchmarkStatusText: String,
    onRunBenchmark: () -> Unit,
    onCancelBenchmark: () -> Unit,
    onClearBenchmark: () -> Unit,
) {
    var showAdvancedParameters by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Section 1: Privacy, Prewarming & Tuning
        JarvisListSection(title = "INFERENCE & PRIVACY CONTROLS") {
            // Internet Access Toggle
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg)
                        .testTag("local_internet_access_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                JarvisIconTile(
                    icon = if (localInternetAccess) Icons.Outlined.Public else Icons.Outlined.PublicOff,
                    tinted = localInternetAccess,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = "Allow Internet Access",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            shape = JarvisShapes.pill,
                            color =
                                if (localInternetAccess) {
                                    JarvisColors.Accent.primarySoft
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                        ) {
                            Text(
                                text = if (localInternetAccess) "ONLINE" else "AIR-GAPPED",
                                style =
                                    JarvisText.Metadata.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                    ),
                                color =
                                    if (localInternetAccess) {
                                        JarvisColors.Accent.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text =
                            if (localInternetAccess) {
                                "Allows on-device models to use live web tools & search."
                            } else {
                                "Strict air-gap: zero outbound network calls."
                            },
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = localInternetAccess,
                    onCheckedChange = onToggleInternetAccess,
                    modifier = Modifier.testTag("local_internet_access_switch"),
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Accent.onPrimary,
                            checkedTrackColor = JarvisColors.Accent.primary,
                        ),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Prewarm Switch
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                JarvisIconTile(
                    icon = Icons.Outlined.Memory,
                    tinted = localPrewarm,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Prewarm Model in Memory",
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Keeps model weights in RAM for zero cold-start latency.",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = localPrewarm,
                    onCheckedChange = onTogglePrewarm,
                    modifier = Modifier.testTag("local_prewarm_switch"),
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Accent.onPrimary,
                            checkedTrackColor = JarvisColors.Accent.primary,
                        ),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Collapsible Advanced Tuning
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedParameters = !showAdvancedParameters }
                        .padding(Spacing.lg)
                        .testTag("advanced_tuning_toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                JarvisIconTile(
                    icon = Icons.Outlined.Tune,
                    tinted = showAdvancedParameters,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Advanced Hyperparameters",
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Temp: ${"%.2f".format(localTemperature)} · Max Tokens: $localMaxTokens · $localThreads Cores",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (showAdvancedParameters) "Hide" else "Tune",
                    style = JarvisText.Button.copy(fontSize = 12.sp, color = JarvisColors.Accent.primary),
                )
            }

            AnimatedVisibility(visible = showAdvancedParameters) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f))
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                ) {
                    // Temperature Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Temperature",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "%.2f".format(localTemperature),
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = JarvisColors.Accent.primary,
                        )
                    }
                    Slider(
                        value = localTemperature,
                        onValueChange = onTemperatureChange,
                        valueRange = 0.0f..1.5f,
                        steps = 29,
                        modifier = Modifier.fillMaxWidth().testTag("local_temperature_slider"),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = JarvisColors.Accent.primary,
                                activeTrackColor = JarvisColors.Accent.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // Max Tokens Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Max Output Tokens",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "$localMaxTokens",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = JarvisColors.Accent.primary,
                        )
                    }
                    Slider(
                        value = localMaxTokens.toFloat(),
                        onValueChange = { onMaxTokensChange(it.toInt()) },
                        valueRange = 256f..4096f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth().testTag("local_max_tokens_slider"),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = JarvisColors.Accent.primary,
                                activeTrackColor = JarvisColors.Accent.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // CPU Threads Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Compute Threads",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "$localThreads Cores",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = JarvisColors.Accent.primary,
                        )
                    }
                    Slider(
                        value = localThreads.toFloat(),
                        onValueChange = { onThreadsChange(it.toInt()) },
                        valueRange = 1f..8f,
                        steps = 6,
                        modifier = Modifier.fillMaxWidth().testTag("local_threads_slider"),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = JarvisColors.Accent.primary,
                                activeTrackColor = JarvisColors.Accent.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    )
                }
            }
        }

        // Section 2: On-Device Hardware Benchmark
        JarvisListSection(title = "SPEED & HARDWARE BENCHMARK") {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        modifier = Modifier.weight(1f),
                    ) {
                        JarvisIconTile(
                            icon = Icons.Outlined.Speed,
                            tinted = true,
                        )
                        Column {
                            Text(
                                text = "Performance Benchmark",
                                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Measures tokens/sec & response speed",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!isBenchmarking) {
                        Button(
                            onClick = onRunBenchmark,
                            shape = RoundedCornerShape(10.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = JarvisColors.Accent.primary,
                                    contentColor = JarvisColors.Accent.onPrimary,
                                ),
                            modifier = Modifier.testTag("run_benchmark_button"),
                        ) {
                            Icon(
                                imageVector = if (benchmarkResult != null) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(
                                text = if (benchmarkResult != null) "Re-run" else "Test Speed",
                                style = JarvisText.Button.copy(fontSize = 13.sp),
                            )
                        }
                    }
                }

                // Live Benchmarking in progress
                AnimatedVisibility(visible = isBenchmarking) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = benchmarkStatusText.ifBlank { "Benchmarking on-device compute…" },
                                style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                                color = JarvisColors.Accent.primary,
                            )
                            Text(
                                text = "${(benchmarkProgress * 100).toInt()}%",
                                style = JarvisText.Metadata.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { benchmarkProgress.coerceIn(0f, 1f) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            color = JarvisColors.Accent.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = onCancelBenchmark,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("cancel_benchmark_button"),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cancel", style = JarvisText.Metadata)
                            }
                        }
                    }
                }

                // Benchmark Results Card
                if (benchmarkResult != null && !isBenchmarking) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = benchmarkResult.modelName.ifBlank { "Active Model" },
                                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val dateStr =
                                        remember(benchmarkResult.timestamp) {
                                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                                .format(Date(benchmarkResult.timestamp))
                                        }
                                    Text(
                                        text = "Tested $dateStr",
                                        style = JarvisText.Caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                val speed = benchmarkResult.generationSpeedTps
                                val speedLabel =
                                    when {
                                        speed >= 20f -> "⚡ Fast"
                                        speed >= 10f -> "🟢 Responsive"
                                        speed >= 5f -> "🟡 Standard"
                                        else -> "🟠 Heavy"
                                    }

                                Surface(
                                    shape = JarvisShapes.pill,
                                    color = JarvisColors.Accent.primarySoft,
                                ) {
                                    Text(
                                        text = speedLabel,
                                        style =
                                            JarvisText.Metadata.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                            ),
                                        color = JarvisColors.Accent.primary,
                                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(Spacing.md))

                            // Metric Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                MetricCard(
                                    label = "THROUGHPUT",
                                    value = "${benchmarkResult.generationSpeedTps} tok/s",
                                    modifier = Modifier.weight(1f),
                                )
                                MetricCard(
                                    label = "FIRST TOKEN",
                                    value = "${benchmarkResult.timeToFirstTokenMs} ms",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                MetricCard(
                                    label = "PEAK MEMORY",
                                    value = "${benchmarkResult.peakMemoryMb} MB",
                                    modifier = Modifier.weight(1f),
                                )
                                MetricCard(
                                    label = "THREADS",
                                    value = "${benchmarkResult.threadCount} Cores",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Text(
                text = label,
                style =
                    JarvisText.Caption.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
