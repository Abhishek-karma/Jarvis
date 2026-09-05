package com.jarvis.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Root theme — warm cream canvas + coral accent identity.
 * Dynamic color is intentionally disabled.
 */
@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkJarvisColorScheme else LightJarvisColorScheme,
        typography =
            androidx.compose.material3.Typography(
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
