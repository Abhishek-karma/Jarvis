package com.jarvis.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Root theme — light/dark follows the system setting. The identity is the warm
 * cream canvas + coral accent + dark navy product surfaces trinity from
 * design.md, adapted for mobile:
 *
 *  - Light: warm cream `#FAF9F5` canvas, warm-ink `#141413` text, coral `#CC785C` accent.
 *  - Dark: warm-ink `#141413` canvas, warm cream `#F5F0E8` text, coral `#CC785C` accent.
 *
 * Dynamic color is deliberately NOT enabled — the identity is fixed regardless of wallpaper.
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
