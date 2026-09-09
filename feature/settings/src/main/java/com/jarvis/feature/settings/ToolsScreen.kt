package com.jarvis.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.ToolSource
import com.jarvis.core.database.repository.ToolCatalogEntry
import com.jarvis.core.designsystem.JarvisEmptyState
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing
import com.jarvis.feature.settings.components.SettingsSearchField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            JarvisHeader(
                title = "Tools Manager",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("tools_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("add_custom_tool_fab"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add custom tool")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Search field
            Box(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                SettingsSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                )
            }

            // Filter Tabs
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    FilterChip(
                        selected = uiState.activeFilter == ToolFilterTab.ALL,
                        onClick = { viewModel.setFilterTab(ToolFilterTab.ALL) },
                        label = { Text("All (${uiState.tools.size})") },
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == ToolFilterTab.BUILTIN,
                        onClick = { viewModel.setFilterTab(ToolFilterTab.BUILTIN) },
                        label = { Text("Built-in") },
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == ToolFilterTab.CUSTOM,
                        onClick = { viewModel.setFilterTab(ToolFilterTab.CUSTOM) },
                        label = { Text("Custom HTTP") },
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.activeFilter == ToolFilterTab.MCP,
                        onClick = { viewModel.setFilterTab(ToolFilterTab.MCP) },
                        label = { Text("MCP") },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            if (uiState.tools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    JarvisEmptyState(
                        title = "No tools found",
                        hint = if (uiState.searchQuery.isNotBlank()) "No tool matches '${uiState.searchQuery}'" else "No tools in this category",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.lg,
                        vertical = Spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(uiState.tools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            onToggle = { enabled -> viewModel.toggleTool(tool.id, enabled) },
                            onTest = { viewModel.openTestDialog(tool) },
                            onDelete = { viewModel.deleteTool(tool.id, tool.name) },
                        )
                    }
                    item {
                        Spacer(Modifier.height(80.dp)) // Padding for FAB
                    }
                }
            }
        }
    }

    // Tool Test Dialog
    if (uiState.testState.toolName != null) {
        ToolTestDialog(
            testState = uiState.testState,
            onArgsChange = viewModel::setTestArgumentsJson,
            onExecute = { name, args -> viewModel.executeTest(name, args) },
            onDismiss = viewModel::closeTestDialog,
        )
    }

    // Create Custom Tool Dialog
    if (showCreateDialog) {
        CreateCustomToolDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, desc, tier, schema, url, method, headers, body ->
                viewModel.createCustomHttpTool(name, desc, tier, schema, url, method, headers, body)
                showCreateDialog = false
            },
        )
    }
}

@Composable
fun ToolCard(
    tool: ToolCatalogEntry,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tool_card_${tool.name}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    val icon = when (tool.source) {
                        ToolSource.BUILTIN -> Icons.Outlined.Build
                        ToolSource.CUSTOM, ToolSource.IMPORTED -> Icons.Outlined.Code
                        ToolSource.MCP -> Icons.Outlined.Extension
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = tool.name,
                        style = JarvisText.ConvTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Switch(
                    checked = tool.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .testTag("tool_switch_${tool.name}")
                        .semantics { contentDescription = "Toggle ${tool.name}" },
                )
            }

            Spacer(Modifier.height(Spacing.xs))

            // Badges row
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgeChip(
                    text = tool.source.wireName.uppercase(),
                    color = when (tool.source) {
                        ToolSource.BUILTIN -> MaterialTheme.colorScheme.secondary
                        ToolSource.CUSTOM -> MaterialTheme.colorScheme.tertiary
                        ToolSource.MCP -> MaterialTheme.colorScheme.primary
                        ToolSource.IMPORTED -> MaterialTheme.colorScheme.outline
                    },
                )
                BadgeChip(
                    text = tool.tier.wireName.replace("_", " ").uppercase(),
                    color = when (tool.tier) {
                        PermissionTier.READ_ONLY -> Color(0xFF2E7D32)
                        PermissionTier.REVERSIBLE_WRITE -> Color(0xFFF57C00)
                        PermissionTier.SENSITIVE -> MaterialTheme.colorScheme.error
                    },
                )
                BadgeChip(
                    text = "v${tool.version}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = tool.description,
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onTest,
                    modifier = Modifier.testTag("tool_test_button_${tool.name}"),
                ) {
                    Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Test")
                }

                if (tool.source == ToolSource.CUSTOM || tool.source == ToolSource.IMPORTED) {
                    Spacer(Modifier.width(Spacing.sm))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("tool_delete_button_${tool.name}"),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete ${tool.name}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = JarvisText.Metadata.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            ),
        )
    }
}

@Composable
fun ToolTestDialog(
    testState: ToolTestState,
    onArgsChange: (String) -> Unit,
    onExecute: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Test Tool: ${testState.toolName}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Input Arguments (JSON):",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                OutlinedTextField(
                    value = testState.argumentsJson,
                    onValueChange = onArgsChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                        .testTag("tool_test_args_input"),
                    textStyle = JarvisText.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    placeholder = { Text("{\"key\": \"value\"}") },
                )

                Spacer(Modifier.height(Spacing.md))

                if (testState.isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Executing tool...", style = JarvisText.Metadata)
                    }
                }

                if (testState.error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.padding(Spacing.sm)) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(Spacing.xs))
                            Text(testState.error, color = MaterialTheme.colorScheme.onErrorContainer, style = JarvisText.Metadata)
                        }
                    }
                }

                if (testState.result != null) {
                    val res = testState.result
                    val statusColor = if (res.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (res.success) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (res.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    if (res.success) "Success" else "Failed",
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    style = JarvisText.Metadata,
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                res.observationText,
                                style = JarvisText.Metadata.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    testState.toolName?.let { name ->
                        onExecute(name, testState.argumentsJson)
                    }
                },
                enabled = !testState.isRunning,
                modifier = Modifier.testTag("tool_test_run_button"),
            ) {
                Text("Execute")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
fun CreateCustomToolDialog(
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        tier: PermissionTier,
        schemaJson: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyTemplate: String?,
    ) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("GET") }
    var tier by rememberSaveable { mutableStateOf(PermissionTier.READ_ONLY) }
    var schemaJson by rememberSaveable {
        mutableStateOf(
            "{\n  \"type\": \"object\",\n  \"properties\": {},\n  \"required\": []\n}",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Declarative HTTP Tool") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tool Name (e.g. get_weather)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_tool_name_input"),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_tool_desc_input"),
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Endpoint URL (e.g. https://api.example.com/{city})") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_tool_url_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FilterChip(
                        selected = method == "GET",
                        onClick = { method = "GET" },
                        label = { Text("GET") },
                    )
                    FilterChip(
                        selected = method == "POST",
                        onClick = { method = "POST" },
                        label = { Text("POST") },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = tier == PermissionTier.READ_ONLY,
                        onClick = { tier = PermissionTier.READ_ONLY },
                        label = { Text("Read Only") },
                    )
                    FilterChip(
                        selected = tier == PermissionTier.SENSITIVE,
                        onClick = { tier = PermissionTier.SENSITIVE },
                        label = { Text("Sensitive") },
                    )
                }

                Text("Parameter Schema (JSON):", style = JarvisText.Metadata)
                OutlinedTextField(
                    value = schemaJson,
                    onValueChange = { schemaJson = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp)
                        .testTag("custom_tool_schema_input"),
                    textStyle = JarvisText.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onCreate(
                            name,
                            description,
                            tier,
                            schemaJson,
                            url,
                            method,
                            emptyMap(),
                            null,
                        )
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank(),
                modifier = Modifier.testTag("custom_tool_save_button"),
            ) {
                Text("Save Tool")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
