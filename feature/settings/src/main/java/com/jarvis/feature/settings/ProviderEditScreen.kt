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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.core.common.ProviderType
import com.jarvis.core.designsystem.JarvisConfirmDialog
import com.jarvis.core.designsystem.JarvisDropdownItem
import com.jarvis.core.designsystem.JarvisDropdownMenu
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisLoader
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
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                    },
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {

            JarvisTextField(
                value = editState.name,
                onValueChange = viewModel::onNameChange,
                label = "Provider name",
                placeholder = "e.g. OpenAI, Groq, My Gateway",
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
                supportingText = "API root — do not add /v1",
            )


            JarvisTextField(
                value = editState.model,
                onValueChange = viewModel::onModelChange,
                label = "Model (optional)",
                placeholder = "e.g. gpt-4o-mini, llama3.2, qwen2.5",
                supportingText = "Sent with every chat. Blank auto-picks the server's first model.",
            )


            JarvisTextField(
                value = editState.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = "API key (optional)",
                placeholder = "sk-…",
                supportingText = "Gateways without auth are fine; cloud APIs need a key.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )


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
                    onCheckedChange = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        viewModel.onDefaultChange(it)
                    },
                )
            }


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
                Text(
                    selected.label(),
                    style = JarvisText.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse family menu" else "Expand family menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            JarvisDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
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
            "OpenAI, Groq, Mistral, xAI and any OpenAI-compatible chat endpoint."
        ProviderType.ANTHROPIC -> "Anthropic Messages API — Claude models with tool calling."
        ProviderType.GEMINI -> "Google Gemini generateContent — Gemini models with tool calling."
    }

private fun canonicalBaseUrl(type: ProviderType): String =
    when (type) {
        ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com"
        ProviderType.ANTHROPIC -> "https://api.anthropic.com"
        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    }
