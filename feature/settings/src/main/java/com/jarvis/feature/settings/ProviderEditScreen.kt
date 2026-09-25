package com.jarvis.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderType
import com.jarvis.core.designsystem.JarvisColors
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.JarvisTextField
import com.jarvis.core.designsystem.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    providerId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val editState by viewModel.editState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(providerId) {
        if (providerId != null) {
            viewModel.loadProvider(providerId)
        } else {
            viewModel.resetForNew()
        }
    }

    LaunchedEffect(editState.verificationSuccess) {
        val savedId = editState.providerId
        if (editState.verificationSuccess && savedId != null) {
            onSaved(savedId)
        }
    }

    Scaffold(
        topBar = {
            JarvisHeader(
                title = if (editState.isNew) "Add Provider" else "Edit Provider",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("provider_edit_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!editState.isNew) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.testTag("provider_delete_action"),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete provider",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
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
            JarvisListSection(title = "PROVIDER CONFIGURATION") {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    JarvisTextField(
                        value = editState.name,
                        onValueChange = viewModel::onNameChange,
                        label = "Provider name",
                        placeholder = "e.g. OpenAI, Groq, OpenRouter",
                    )

                    ProviderFamilyPicker(
                        selected = editState.type,
                        onSelected = viewModel::onTypeChange,
                    )

                    JarvisTextField(
                        value = editState.baseUrl,
                        onValueChange = viewModel::onBaseUrlChange,
                        label = "Base URL",
                        placeholder = canonicalBaseUrl(editState.type),
                        supportingText = "API root endpoint without /v1",
                    )

                    JarvisTextField(
                        value = editState.model,
                        onValueChange = viewModel::onModelChange,
                        label = "Default model name",
                        placeholder = defaultModelPlaceholder(editState.type),
                        supportingText = "Specific model tag or alias. Leaving blank uses server default.",
                    )

                    Column(modifier = Modifier.fillMaxWidth()) {
                        JarvisTextField(
                            value = editState.apiKey,
                            onValueChange = viewModel::onApiKeyChange,
                            label = "API key",
                            placeholder = "sk-…",
                            supportingText = "Saved securely on device only.",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        )
                        if (editState.apiKey.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.xs),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (showApiKey) "Hide API key" else "Show API key",
                                    style = JarvisText.Metadata.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(JarvisShapes.pill)
                                        .clickable { showApiKey = !showApiKey }
                                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                )
                            }
                        }
                    }
                }
            }

            JarvisListSection(title = "DEFAULT ROUTING") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JarvisIconTile(
                        icon = Icons.Outlined.Star,
                        tinted = editState.isDefault,
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Set as default provider",
                            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Primary cloud model used for new chats and agent tasks",
                            style = JarvisText.Metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = editState.isDefault,
                        onCheckedChange = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            viewModel.onDefaultChange(it)
                        },
                        modifier = Modifier.semantics { contentDescription = "Set as default provider" },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                editState.verificationError?.let { error ->
                    Surface(
                        shape = JarvisShapes.card,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.smPlus),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = error,
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (editState.verificationSuccess) {
                    Surface(
                        shape = JarvisShapes.card,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.smPlus),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Connection verified and provider saved successfully.",
                                style = JarvisText.Metadata,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Button(
                    onClick = viewModel::verifyAndSave,
                    enabled = !editState.isVerifying,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisColors.Accent.primary,
                        contentColor = JarvisColors.Accent.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("verify_and_save_button"),
                ) {
                    if (editState.isVerifying) {
                        JarvisLoader(
                            color = JarvisColors.Accent.onPrimary,
                            size = 20.dp,
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Verifying endpoint…", style = JarvisText.Button)
                    } else {
                        Text(
                            text = if (editState.isNew) "Verify & Save Provider" else "Verify & Update Provider",
                            style = JarvisText.Button,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.huge))
        }
    }

    if (showDeleteDialog) {
        JarvisConfirmDialog(
            title = "Delete provider",
            message = "This will permanently remove the provider and its API key.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteCurrentProvider()
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun ProviderFamilyPicker(
    selected: ProviderType,
    onSelected: (ProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val families = ProviderType.entries
    val shape = RoundedCornerShape(14.dp)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Provider family",
            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            expanded = true
                        }
                        .padding(horizontal = Spacing.lg, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JarvisIconTile(
                    icon = Icons.Outlined.Hub,
                    tinted = true,
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        selected.label(),
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        selected.description(),
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse family menu" else "Expand family menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            JarvisDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                minimumWidth = 280.dp,
            ) {
                families.forEachIndexed { index, type ->
                    JarvisDropdownItem(
                        text = type.label(),
                        dividerBelow = index < families.lastIndex,
                        onClick = {
                            onSelected(type)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun ProviderType.label(): String =
    when (this) {
        ProviderType.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        ProviderType.ANTHROPIC -> "Anthropic"
        ProviderType.GEMINI -> "Google Gemini"
    }

private fun ProviderType.description(): String =
    when (this) {
        ProviderType.OPENAI_COMPATIBLE ->
            "OpenAI, Groq, Mistral, xAI, OpenRouter, and custom OpenAI APIs"
        ProviderType.ANTHROPIC -> "Anthropic Messages API — Claude 3.5 Sonnet, Haiku"
        ProviderType.GEMINI -> "Gemini API — Gemini 2.0 Flash, 1.5 Pro"
    }

private fun canonicalBaseUrl(type: ProviderType): String =
    when (type) {
        ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com"
        ProviderType.ANTHROPIC -> "https://api.anthropic.com"
        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    }

private fun defaultModelPlaceholder(type: ProviderType): String =
    when (type) {
        ProviderType.OPENAI_COMPATIBLE -> "e.g. gpt-4o-mini, llama-3.3-70b-versatile"
        ProviderType.ANTHROPIC -> "e.g. claude-3-5-sonnet-20241022"
        ProviderType.GEMINI -> "e.g. gemini-2.0-flash"
    }
