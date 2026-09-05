package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing

/**
 * About page — the calm, typographic credits/privacy screen. Serif display per the
 * Claude identity layer; grouped cards shared with Settings via [JarvisListSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            JarvisHeader(
                title = "About",
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
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Spacing.huge))

            JarvisMark(size = 48.dp)

            Text(
                text = "Jarvis",
                style = JarvisText.Display,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = Spacing.lg),
            )
            Text(
                text = "Version 0.1 · MVP — Foundation & Chat",
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )

            Spacer(modifier = Modifier.height(Spacing.huge))

            JarvisListSection(title = "What this is") {
                AboutBody(
                    modifier = Modifier.padding(Spacing.lg),
                    text =
                        "A local-first personal AI assistant for Android. Multi-provider " +
                            "LLM chat with streaming responses, markdown rendering, conversation " +
                            "history, and push-to-talk voice — all stored on your device.",
                )
            }
            JarvisListSection(title = "Privacy") {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    AboutRow("API keys are stored in EncryptedSharedPreferences (AES-256-GCM, Keystore-backed)")
                    AboutRow("Keys are sent only to their own provider endpoint over TLS")
                    AboutRow("No analytics on message content, ever")
                    AboutRow("No permission is requested before the screen that needs it")
                }
            }
            JarvisListSection(title = "Design") {
                AboutBody(
                    modifier = Modifier.padding(Spacing.lg),
                    text =
                        "The interface follows the ChatGPT and Claude design systems from " +
                            "awesome-ios-design-md (Meliwat), ported to Jetpack Compose: a " +
                            "monochrome canvas, a single terracotta accent, and serif assistant " +
                            "prose.",
                )
            }
            Spacer(modifier = Modifier.height(Spacing.huge))
        }
    }
}

@Composable
private fun AboutBody(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = JarvisText.BodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun AboutRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        JarvisMark(size = 14.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = JarvisText.BodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
