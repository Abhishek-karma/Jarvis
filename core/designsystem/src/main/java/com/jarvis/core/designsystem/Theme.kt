package com.jarvis.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Root theme — ported from `design/chatgpt/DESIGN-android.md` (light + dark switch on
 * the system setting; charcoal #212121 dark, never true black) with Claude's warm
 * accent scheme from `design/claude/DESIGN-android.md`. Dynamic color is deliberately
 * NOT enabled — the identity is fixed regardless of wallpaper.
 */
@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkJarvisColorScheme else LightJarvisColorScheme,
        typography = androidx.compose.material3.Typography(
            displayLarge = JarvisMaterialTypography.displayLarge,
            displayMedium = JarvisMaterialTypography.displayMedium,
            titleLarge = JarvisMaterialTypography.titleLarge,
            titleMedium = JarvisMaterialTypography.titleMedium,
            titleSmall = JarvisMaterialTypography.titleSmall,
            bodyLarge = JarvisMaterialTypography.bodyLarge,
            bodyMedium = JarvisMaterialTypography.bodyMedium,
            bodySmall = JarvisMaterialTypography.bodySmall,
            labelLarge = JarvisMaterialTypography.labelLarge,
            labelMedium = JarvisMaterialTypography.labelMedium,
            labelSmall = JarvisMaterialTypography.labelSmall,
        ),
        content = content,
    )
}
