package com.jarvis.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisIconTile
import com.jarvis.core.designsystem.JarvisListSection
import com.jarvis.core.designsystem.JarvisMark
import com.jarvis.core.designsystem.JarvisText
import com.jarvis.core.designsystem.Spacing


private const val PROJECT_HOME_URL: String = "https://github.com/Abhishek-karma/Jarvis"


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {

    val appVersion =
        LocalContext.current
            .let { ctx ->
                runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            }.orEmpty()
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
                text = "Version $appVersion — Agents, Voice & Multi-provider",
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
                            "LLM chat with streaming responses, markdown rendering, an agent " +
                            "that runs tools with your approval, on-device models, " +
                            "conversation history, and push-to-talk voice — all stored on " +
                            "your device.",
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
                        "The interface follows a calm, chat-first design language: a neutral " +
                            "canvas, a single blue accent, bubble-less assistant prose, and " +
                            "restrained, focused surfaces — ported to Jetpack Compose.",
                )
            }





            JarvisListSection(title = "Project") {
                ProjectSourceRow()
            }




            Spacer(modifier = Modifier.height(Spacing.huge))
            Text(
                text = "Made on Android · Compose · Hilt · Room",
                style = JarvisText.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xl),
            )
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


@Composable
private fun ProjectSourceRow() {
    val context = LocalContext.current
    val url = PROJECT_HOME_URL
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .clickable {
                    runCatching {
                        val intent =
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ContextCompat.startActivity(context, intent, null)
                    }
                }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarvisIconTile(Icons.Outlined.Code)
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
        ) {
            Text(
                text = "View source",
                style = JarvisText.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = url.removePrefix("https://").removePrefix("http://"),
                style = JarvisText.Metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,


            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.lgPlus),
        )
    }
}
