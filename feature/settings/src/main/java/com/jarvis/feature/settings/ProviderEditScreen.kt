package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderType
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisLoader
import com.jarvis.core.designsystem.JarvisShapes
import com.jarvis.core.designsystem.JarvisText
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

    // Load provider on first composition if editing an existing one
    LaunchedEffect(providerId) {
        if (providerId != null) {
            viewModel.loadProvider(providerId)
        } else {
            viewModel.resetForNew()
        }
    }

    // A verified-and-saved provider is done: leave the editor (the toast lands on the
    // providers list, which has its own snackbar host).
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!editState.isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
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
                    .padding(horizontal = Spacing.lg)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Name
            OutlinedTextField(
                value = editState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Provider name") },
                placeholder = { Text("e.g. OpenAI, Ollama, My Server") },
                singleLine = true,
                shape = JarvisShapes.input,
                modifier = Modifier.fillMaxWidth(),
            )

            // Provider family — sets the adapter and defaults the base URL to the family root.
            ProviderFamilyPicker(
                selected = editState.type,
                onSelected = viewModel::onTypeChange,
            )

            // Base URL
            OutlinedTextField(
                value = editState.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text(canonicalBaseUrl(editState.type)) },
                supportingText = { Text("API root — do not add /v1") },
                singleLine = true,
                shape = JarvisShapes.input,
                modifier = Modifier.fillMaxWidth(),
            )

            // Model (optional)
            OutlinedTextField(
                value = editState.model,
                onValueChange = viewModel::onModelChange,
                label = { Text("Model (optional)") },
                placeholder = { Text("e.g. gpt-4o-mini, llama3.2, qwen2.5") },
                supportingText = {
                    Text("Sent with every chat. Blank auto-picks the server's first model.")
                },
                singleLine = true,
                shape = JarvisShapes.input,
                modifier = Modifier.fillMaxWidth(),
            )

            // API Key (optional for local servers)
            OutlinedTextField(
                value = editState.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key (optional)") },
                placeholder = { Text("sk-…") },
                supportingText = {
                    Text("Local servers like Ollama and LM Studio don't need one.")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = JarvisShapes.input,
                modifier = Modifier.fillMaxWidth(),
            )

            // Set as default toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Set as default",
                        style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        "This provider will be used for new conversations",
                        style = JarvisText.Metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = editState.isDefault,
                    onCheckedChange = viewModel::onDefaultChange,
                )
            }

            // Error / success feedback
            editState.verificationError?.let { error ->
                Text(
                    text = error,
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (editState.verificationSuccess) {
                Text(
                    text = "✓ Verified and saved",
                    style = JarvisText.Metadata,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Save / verify button
            Button(
                onClick = viewModel::verifyAndSave,
                enabled = !editState.isVerifying,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (editState.isVerifying) {
                    JarvisLoader(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (editState.isNew) "Verify & Save" else "Verify & Update")
                }
            }

            Spacer(modifier = Modifier.height(Spacing.huge))
        }
    }

    // Delete confirmation — the app's shared destructive-action dialog.
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

/**
 * Provider-family picker. A compact dropdown that drives the Base URL default and the
 * adapter [ProviderManager.adapterFor] dispatches to. Tapping a family that differs from
 * the current one resets the URL to that family's canonical root unless the user has
 * already typed a custom one (handled in [SettingsViewModel.onTypeChange]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFamilyPicker(
    selected: ProviderType,
    onSelected: (ProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val families = ProviderType.entries

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Provider family",
            style = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selected.label(),
                onValueChange = {},
                label = { Text("Family") },
                placeholder = { Text("Select a provider family") },
                singleLine = true,
                shape = JarvisShapes.input,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                },
                readOnly = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                families.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                type.label(),
                                style = JarvisText.BodyMedium,
                            )
                        },
                        onClick = {
                            onSelected(type)
                            expanded = false
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            selected.description(),
            style = JarvisText.Metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ProviderType.label(): String =
    when (this) {
        ProviderType.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        ProviderType.ANTHROPIC -> "Anthropic"
        ProviderType.GEMINI -> "Gemini"
    }

private fun ProviderType.description(): String =
    when (this) {
        ProviderType.OPENAI_COMPATIBLE ->
            "OpenAI, Groq, Mistral, xAI, LM Studio, Ollama and any /v1 SSE endpoint."
        ProviderType.ANTHROPIC -> "Anthropic Messages API — Claude models with tool calling."
        ProviderType.GEMINI -> "Google Gemini generateContent — Gemini models with tool calling."
    }

private fun canonicalBaseUrl(type: ProviderType): String =
    when (type) {
        ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com"
        ProviderType.ANTHROPIC -> "https://api.anthropic.com"
        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    }
