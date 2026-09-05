package com.jarvis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jarvis.core.designsystem.JarvisHeader
import com.jarvis.core.designsystem.JarvisListRow
import com.jarvis.core.designsystem.JarvisListSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAbout: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            JarvisHeader(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Only settings that actually work are listed — no "coming soon" rows
        // teasing features the build can't open.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            JarvisListSection(title = "Models") {
                JarvisListRow(
                    title = "Providers",
                    subtitle = "Add or manage LLM providers",
                    onClick = onOpenProviders,
                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }

            JarvisListSection(title = "About") {
                JarvisListRow(
                    title = "About Jarvis",
                    subtitle = "Version, privacy, credits",
                    onClick = onOpenAbout,
                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }
        }
    }
}
