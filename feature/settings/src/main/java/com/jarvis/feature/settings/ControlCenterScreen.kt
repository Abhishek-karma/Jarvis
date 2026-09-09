package com.jarvis.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.agent.bridge.BridgeStatus
import com.jarvis.core.agent.bridge.BridgeTier
import com.jarvis.core.agent.bridge.BridgeTierInfo
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

@Composable
fun ControlCenterRoute(
    onBack: () -> Unit,
    viewModel: ControlCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ControlCenterEvent.ShowToast -> snackbarHostState.showSnackbar(event.message)
                is ControlCenterEvent.OpenShizukuApp -> {
                    try {
                        val launchIntent =
                            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        } else {
                            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app"))
                            context.startActivity(marketIntent)
                        }
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Could not open Shizuku: ${e.message}")
                    }
                }
            }
        }
    }

    ControlCenterScreen(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::loadState,
        onToggleExpertMode = viewModel::toggleExpertMode,
        onToggleShizuku = viewModel::toggleShizuku,
        onLaunchShizuku = viewModel::onLaunchShizuku,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen(
    uiState: ControlCenterUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleExpertMode: (Boolean) -> Unit,
    onToggleShizuku: (Boolean) -> Unit,
    onLaunchShizuku: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var showExpertWarningDialog by remember { mutableStateOf(false) }

    if (showExpertWarningDialog) {
        JarvisConfirmDialog(
            title = "Enable Expert Shell Mode?",
            message = "Expert shell mode allows the assistant to execute policy-governed system commands when an elevated bridge is active.\n\nPermanently destructive operations (such as root wipes and filesystem formatting) remain strictly blocked by the deterministic safety engine.",
            confirmLabel = "Enable Expert Mode",
            destructive = false,
            onConfirm = {
                showExpertWarningDialog = false
                onToggleExpertMode(true)
            },
            onDismiss = { showExpertWarningDialog = false },
        )
    }

    Scaffold(
        topBar = {
            JarvisHeader(
                title = "Device Control Center",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("control_center_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("control_center_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh Bridges",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.huge),
        ) {
            // Section 1: Active Tier Banner
            JarvisListSection(title = "ACTIVE PRIVILEGE TIER") {
                ActiveTierRow(activeTier = uiState.activeTier)
            }

            // Section 2: Bridges Ladder
            JarvisListSection(title = "PRIVILEGE BRIDGES") {
                uiState.tiers.forEachIndexed { index, tierInfo ->
                    BridgeTierItem(
                        tierInfo = tierInfo,
                        onLaunchShizuku = if (tierInfo.tier == BridgeTier.SHIZUKU) onLaunchShizuku else null,
                    )
                    if (index < uiState.tiers.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }

            // Section 3: Expert Shell Mode
            JarvisListSection(title = "EXPERT MODE & BRIDGE TOGGLES") {
                ExpertShellToggleRow(
                    isExpertMode = uiState.isExpertModeEnabled,
                    onToggle = { enabled ->
                        if (enabled) {
                            showExpertWarningDialog = true
                        } else {
                            onToggleExpertMode(false)
                        }
                    },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                ShizukuBridgeToggleRow(
                    isShizukuEnabled = uiState.isShizukuEnabled,
                    onToggle = onToggleShizuku,
                )
            }

            // Section 4: Policy Engine Invariants
            JarvisListSection(title = "DETERMINISTIC POLICY ENGINE") {
                PolicyEngineSummaryContent(blockedRules = uiState.blockedRulesSummary)
            }

            // Section 5: Setup Guide
            JarvisListSection(title = "SHIZUKU SETUP GUIDE") {
                ShizukuSetupGuideContent(onLaunchShizuku = onLaunchShizuku)
            }
        }
    }
}

@Composable
private fun ActiveTierRow(activeTier: BridgeTier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarvisIconTile(
            icon = Icons.Outlined.Shield,
            tinted = activeTier != BridgeTier.SANDBOX,
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activeTier.displayName,
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when (activeTier) {
                    BridgeTier.SANDBOX -> "Operating within unprivileged app sandbox"
                    BridgeTier.SHIZUKU -> "ADB-level device control active via Shizuku"
                    BridgeTier.ROOT -> "Direct superuser root access active"
                },
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            shape = JarvisShapes.pill,
            color = if (activeTier != BridgeTier.SANDBOX) {
                JarvisColors.Accent.primarySoft
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Text(
                text = if (activeTier != BridgeTier.SANDBOX) "ELEVATED" else "STANDARD",
                style = JarvisText.Metadata.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                ),
                color = if (activeTier != BridgeTier.SANDBOX) {
                    JarvisColors.Accent.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
    }
}

@Composable
private fun BridgeTierItem(
    tierInfo: BridgeTierInfo,
    onLaunchShizuku: (() -> Unit)? = null,
) {
    val (statusColor, statusBg, statusIcon) = when (tierInfo.status) {
        BridgeStatus.AVAILABLE -> Triple(
            JarvisColors.Semantic.success,
            JarvisColors.Semantic.success.copy(alpha = 0.12f),
            Icons.Outlined.CheckCircle,
        )
        BridgeStatus.SERVICE_STOPPED -> Triple(
            JarvisColors.Semantic.warning,
            JarvisColors.Semantic.warning.copy(alpha = 0.12f),
            Icons.Outlined.WarningAmber,
        )
        BridgeStatus.NOT_INSTALLED,
        BridgeStatus.PERMISSION_DENIED -> Triple(
            JarvisColors.Semantic.error,
            JarvisColors.Semantic.error.copy(alpha = 0.12f),
            Icons.Outlined.ErrorOutline,
        )
        BridgeStatus.UNSUPPORTED -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            Icons.Outlined.HelpOutline,
        )
    }

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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JarvisIconTile(
                    icon = if (tierInfo.tier == BridgeTier.SHIZUKU) Icons.Outlined.Security else Icons.Outlined.Code,
                    tinted = tierInfo.status == BridgeStatus.AVAILABLE,
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = tierInfo.name,
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tierInfo.status.message,
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = JarvisShapes.pill,
                color = statusBg,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tierInfo.status.name.replace("_", " "),
                        color = statusColor,
                        style = JarvisText.Metadata.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        ),
                    )
                }
            }
        }

        if (tierInfo.tier == BridgeTier.SHIZUKU && tierInfo.status != BridgeStatus.AVAILABLE && onLaunchShizuku != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            OutlinedButton(
                onClick = onLaunchShizuku,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Open / Pair Shizuku Companion", style = JarvisText.Button)
            }
        }
    }
}

@Composable
private fun ExpertShellToggleRow(
    isExpertMode: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JarvisIconTile(
                icon = Icons.Outlined.Terminal,
                tinted = isExpertMode,
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column {
                Text(
                    text = "Expert Shell Mode",
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Allows policy-governed shell command tools",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Switch(
            checked = isExpertMode,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisColors.Accent.primary,
                checkedTrackColor = JarvisColors.Accent.primarySoft,
            ),
        )
    }
}

@Composable
private fun ShizukuBridgeToggleRow(
    isShizukuEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JarvisIconTile(
                icon = Icons.Outlined.Security,
                tinted = isShizukuEnabled,
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column {
                Text(
                    text = "Shizuku Integration",
                    style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Probe and route elevated operations through Shizuku",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Switch(
            checked = isShizukuEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisColors.Accent.primary,
                checkedTrackColor = JarvisColors.Accent.primarySoft,
            ),
        )
    }
}

@Composable
private fun PolicyEngineSummaryContent(blockedRules: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = JarvisColors.Semantic.success,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "Safety Engine Invariants",
                style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "Dangerous patterns permanently blocked at the code level:",
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        Surface(
            shape = JarvisShapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                blockedRules.forEach { rule ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "• ",
                            color = JarvisColors.Semantic.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = rule,
                            style = JarvisText.Metadata.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuSetupGuideContent(onLaunchShizuku: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
    ) {
        Text(
            text = "Setting up Shizuku on your device",
            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "1. Install Shizuku from GitHub, F-Droid, or Google Play.\n2. Enable Developer Options & Wireless Debugging in Android Settings.\n3. Open Shizuku, pair Wireless Debugging, and start the service.\n4. Authorize Jarvis when the permission popup appears.",
            style = JarvisText.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
            onClick = onLaunchShizuku,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Accent.primary),
        ) {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text("Open / Download Shizuku", style = JarvisText.Button)
        }
    }
}
