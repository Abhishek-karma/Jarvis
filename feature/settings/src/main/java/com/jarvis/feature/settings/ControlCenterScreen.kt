package com.jarvis.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
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
        AlertDialog(
            onDismissRequest = { showExpertWarningDialog = false },
            title = { Text("Enable Expert Shell Mode?") },
            text = {
                Text(
                    "Expert shell mode allows the assistant to execute policy-checked system commands when a privileged bridge is active.\n\nPermanently dangerous destructive operations (e.g. root wiping, filesystem format) remain strictly blocked by the deterministic CommandPolicyEngine."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExpertWarningDialog = false
                        onToggleExpertMode(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Accent.primary),
                ) {
                    Text("Enable Expert Mode")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExpertWarningDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Control Center", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Bridges",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Spacer(modifier = Modifier.height(Spacing.xs))
                ActiveTierBanner(activeTier = uiState.activeTier)
            }

            item {
                Text(
                    text = "Privilege Tier Ladder",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            items(uiState.tiers) { tierInfo ->
                BridgeTierCard(
                    tierInfo = tierInfo,
                    onLaunchShizuku = if (tierInfo.tier == BridgeTier.SHIZUKU) onLaunchShizuku else null,
                )
            }

            item {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "Expert & Safety Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item {
                ExpertModeCard(
                    isExpertMode = uiState.isExpertModeEnabled,
                    onToggle = { enabled ->
                        if (enabled) {
                            showExpertWarningDialog = true
                        } else {
                            onToggleExpertMode(false)
                        }
                    },
                )
            }

            item {
                PolicyEngineSummaryCard(blockedRules = uiState.blockedRulesSummary)
            }

            item {
                ShizukuSetupGuideCard(onLaunchShizuku = onLaunchShizuku)
                Spacer(modifier = Modifier.height(Spacing.xl))
            }
        }
    }
}

@Composable
fun ActiveTierBanner(activeTier: BridgeTier) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activeTier == BridgeTier.SHIZUKU) JarvisColors.Accent.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (activeTier == BridgeTier.SHIZUKU) JarvisColors.Accent.primary else Color(0xFF64748B)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Current Active Tier",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = activeTier.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun BridgeTierCard(
    tierInfo: BridgeTierInfo,
    onLaunchShizuku: (() -> Unit)? = null,
) {
    val statusColor = when (tierInfo.status) {
        BridgeStatus.AVAILABLE -> Color(0xFF10B981)
        BridgeStatus.SERVICE_STOPPED -> Color(0xFFF59E0B)
        BridgeStatus.NOT_INSTALLED -> Color(0xFFEF4444)
        BridgeStatus.PERMISSION_DENIED -> Color(0xFFEF4444)
        BridgeStatus.UNSUPPORTED -> Color(0xFF94A3B8)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = tierInfo.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = tierInfo.status.name.replace("_", " "),
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = tierInfo.status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onLaunchShizuku != null && tierInfo.status != BridgeStatus.AVAILABLE) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = onLaunchShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Configure Shizuku Companion")
                }
            }
        }
    }
}

@Composable
fun ExpertModeCard(
    isExpertMode: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = JarvisColors.Accent.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column {
                    Text(
                        text = "Expert Shell Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Allows policy-governed shell command tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Switch(
                checked = isExpertMode,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = JarvisColors.Accent.primary),
            )
        }
    }
}

@Composable
fun PolicyEngineSummaryCard(blockedRules: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = "CommandPolicyEngine Invariants",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "The following dangerous patterns are permanently blocked by code-level regexes:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            blockedRules.forEach { rule ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("• ", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    Text(
                        text = rule,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun ShizukuSetupGuideCard(onLaunchShizuku: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = "How to set up Shizuku",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "1. Install Shizuku from GitHub/F-Droid or Google Play.\n2. Enable Developer Options & Wireless Debugging on your phone.\n3. Open Shizuku, pair Wireless Debugging, and tap Start.\n4. Authorize Jarvis when prompted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Button(
                onClick = onLaunchShizuku,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Accent.primary),
            ) {
                Text("Get / Launch Shizuku")
            }
        }
    }
}
