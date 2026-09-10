package com.jarvis.feature.settings.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Radius
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.settings.OnboardingStep
import com.jarvis.feature.settings.OnboardingUiState

/**
 * Top navigation bar for Onboarding with back navigation, animated step segment progress, and skip action.
 */
@Composable
fun OnboardingTopBar(
    step: OnboardingStep,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showBack = step != OnboardingStep.WELCOME
    val showSkip = step != OnboardingStep.PERMISSIONS

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        // Segmented Step Indicator
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingStep.entries.forEach { s ->
                val isActive = s == step
                val isCompleted = s.ordinal < step.ordinal
                val targetWidth by animateDpAsState(
                    targetValue = if (isActive) 24.dp else 8.dp,
                    animationSpec = tween(300),
                    label = "step_pill_width",
                )
                val targetColor by animateColorAsState(
                    targetValue = when {
                        isActive -> JarvisColors.Accent.primary
                        isCompleted -> JarvisColors.Accent.primary.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(300),
                    label = "step_pill_color",
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(targetWidth)
                        .clip(RoundedCornerShape(3.dp))
                        .background(targetColor),
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        if (showSkip) {
            Surface(
                onClick = onSkip,
                shape = JarvisShapes.pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
            ) {
                Text(
                    text = "Skip",
                    style = JarvisText.SenderLabel.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun WelcomeStep(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Spacing.xl))

            // Glowing hero mark
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                JarvisMark(
                    size = 54.dp,
                    color = JarvisColors.Accent.primary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            Text(
                text = "Meet Jarvis",
                style = JarvisText.Display,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Your Intelligent AI & Android Companion",
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = JarvisColors.Accent.primary,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Chat with top cloud LLMs, run completely private models on-device, and automate device workflows with full safety.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Value Proposition Highlights
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                WelcomeFeatureCard(
                    icon = Icons.Outlined.Computer,
                    title = "100% Private & On-Device",
                    subtitle = "Run lightweight models locally via LiteRT & GGUF with zero internet required.",
                )
                WelcomeFeatureCard(
                    icon = Icons.Outlined.Cloud,
                    title = "Universal Cloud Hub",
                    subtitle = "Connect OpenAI, Claude, Gemini, DeepSeek, or private Ollama endpoints.",
                )
                WelcomeFeatureCard(
                    icon = Icons.Outlined.Security,
                    title = "System Control & Voice",
                    subtitle = "Real-time hands-free voice mode and policy-governed device automation.",
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
        ) {
            PrimaryActionButton(
                text = "Get Started",
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun WelcomeFeatureCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = JarvisColors.Accent.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SetupStep(
    uiState: OnboardingUiState,
    onOpenProviders: () -> Unit,
    onOpenLocalModels: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Configure Intelligence",
                style = JarvisText.Display,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Choose how Jarvis will generate responses. You can use Cloud APIs, on-device models, or combine both.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Cloud Provider Option Card
            IntelligenceChoiceCard(
                icon = Icons.Outlined.Cloud,
                title = "Cloud AI Providers",
                subtitle = "OpenAI, Claude, Gemini, DeepSeek, Groq, or custom endpoints.",
                tag = "Fastest • Most Capable",
                statusText = if (uiState.hasProvider) "Configured (${uiState.providerCount})" else "Not configured",
                isConfigured = uiState.hasProvider,
                actionLabel = if (uiState.hasProvider) "Manage Cloud Keys" else "Connect Cloud Provider",
                onClick = onOpenProviders,
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            // On-Device Model Option Card
            IntelligenceChoiceCard(
                icon = Icons.Outlined.Computer,
                title = "On-Device Local AI",
                subtitle = "Gemma, Qwen, Llama & Phi running locally. Works completely offline.",
                tag = "100% Private • Offline",
                statusText = if (uiState.hasLocalModel) {
                    uiState.activeLocalModelName?.let { "Active: $it" } ?: "Model Ready (${uiState.installedLocalCount})"
                } else {
                    "No model installed"
                },
                isConfigured = uiState.hasLocalModel,
                actionLabel = if (uiState.hasLocalModel) "Manage Local Models" else "Download On-Device Model",
                onClick = onOpenLocalModels,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
        ) {
            val hasAnyConfig = uiState.hasProvider || uiState.hasLocalModel
            PrimaryActionButton(
                text = if (hasAnyConfig) "Continue" else "Continue with Default Setup",
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                onClick = onContinue,
            )
            if (!hasAnyConfig) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "You can add API keys or download models anytime in Settings",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun IntelligenceChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    statusText: String,
    isConfigured: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JarvisShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isConfigured) 1.5.dp else 1.dp,
            color = if (isConfigured) JarvisColors.Accent.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(JarvisShapes.card)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isConfigured) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isConfigured) JarvisColors.Accent.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // Tag pill
                Surface(
                    shape = JarvisShapes.pill,
                    color = if (isConfigured) {
                        JarvisColors.Semantic.success.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Text(
                        text = tag,
                        style = JarvisText.Metadata.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isConfigured) JarvisColors.Semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.smPlus, vertical = 3.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            Text(
                text = title,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConfigured) JarvisColors.Semantic.success else MaterialTheme.colorScheme.outline,
                            ),
                    )
                    Text(
                        text = statusText,
                        style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                        color = if (isConfigured) JarvisColors.Semantic.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "$actionLabel →",
                    style = JarvisText.Metadata.copy(fontWeight = FontWeight.Bold),
                    color = JarvisColors.Accent.primary,
                )
            }
        }
    }
}

@Composable
fun PermissionsStep(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = remember {
        listOf(
            PermissionItem(
                permission = Manifest.permission.RECORD_AUDIO,
                icon = Icons.Outlined.Mic,
                title = "Microphone (Voice Mode)",
                subtitle = "Required for hands-free, two-way conversational voice mode.",
            ),
            PermissionItem(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                icon = Icons.Outlined.Notifications,
                title = "System Notifications",
                subtitle = "Receive alerts for background completions and model download progress.",
            ),
            PermissionItem(
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                },
                icon = Icons.Outlined.Folder,
                title = "Device Storage & Files",
                subtitle = "Allows Jarvis to search, read, and analyze documents and files on your device.",
            ),
        )
    }

    val granted = remember(resumeTick) {
        permissions.associate { spec ->
            spec.permission to (
                ContextCompat.checkSelfPermission(context, spec.permission) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
    }

    var pendingRequest by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { wasGranted ->
        val requested = pendingRequest
        pendingRequest = null

        if (!wasGranted && requested != null &&
            context is Activity &&
            !context.shouldShowRequestPermissionRationale(requested)
        ) {
            openAppSettings(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Permissions & Privacy",
                style = JarvisText.Display,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Enable optional features. Jarvis never accesses sensors or runs tools without your explicit permission.",
                style = JarvisText.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            Surface(
                shape = JarvisShapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    permissions.forEachIndexed { index, item ->
                        val isGranted = granted[item.permission] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isGranted) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = if (isGranted) JarvisColors.Accent.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = item.subtitle,
                                    style = JarvisText.Metadata,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Switch(
                                checked = isGranted,
                                onCheckedChange = { want ->
                                    if (want) {
                                        pendingRequest = item.permission
                                        launcher.launch(item.permission)
                                    } else {
                                        openAppSettings(context)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = JarvisColors.Accent.onPrimary,
                                    checkedTrackColor = JarvisColors.Accent.primary,
                                ),
                            )
                        }

                        if (index < permissions.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )

                    // Control Center & Shizuku Info Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device Bridges (Optional)",
                                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Policy-governed terminal commands and Shizuku device bridge.",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            shape = JarvisShapes.pill,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = "In Settings",
                                style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
        ) {
            PrimaryActionButton(
                text = "Start Using Jarvis",
                icon = Icons.AutoMirrored.Outlined.Chat,
                onClick = onDone,
            )
        }
    }
}

private data class PermissionItem(
    val permission: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = JarvisColors.Accent.primary,
            contentColor = JarvisColors.Accent.onPrimary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = JarvisText.Button.copy(fontWeight = FontWeight.Bold),
            )
            if (icon != null) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
