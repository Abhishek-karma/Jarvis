package com.jarvis.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw token values — Jarvis's redesigned palette:
 *
 *  - Canvas is cool off-white `#F5F6F8` (light) / dark slate `#121820` (dark).
 *  - Text is deep slate (`#1E2630` light / `#E4E7EC` dark) for crisp contrast.
 *  - Accent is deep indigo `#3E4C8A` (light) / soft indigo `#7C8FD8` (dark).
 *  - Code blocks are dark slate in both themes for consistency.
 *  - Voice-mode sphere uses indigo-to-lavender gradient for a distinctive look.
 */
object JarvisColors {
    object Dark {
        val canvas = Color(0xFF121820)
        val surface = Color(0xFF1C2430)
        val surfaceAlt = Color(0xFF253040)
        val paper = Color(0xFF1C2430)
        val sidebar = Color(0xFF161E28)
        val sidebarActive = Color(0xFF253040)
        val textPrimary = Color(0xFFE4E7EC)
        val textSecondary = Color(0xFFA0AAB8)
        val textTertiary = Color(0xFF6E7A8A)
        val textDisabled = Color(0xFF4A5568)
        val divider = Color(0xFF2A3444)
        val codeBg = Color(0xFF0E141C)
        val codeFg = Color(0xFFE4E7EC)
        val codeHeader = Color(0xFF253040)
        val codeBorder = Color(0xFF2A3444)
        val codeInlineBg = Color(0xFF253040)
        val sendDisabled = Color(0xFF253040)
    }

    object Light {
        val canvas = Color(0xFFF5F6F8)
        val surface = Color(0xFFEAECF0)
        val surfaceAlt = Color(0xFFDDE1E8)
        val paper = Color(0xFFFCFCFD)
        val sidebar = Color(0xFFF0F2F5)
        val sidebarActive = Color(0xFFE2E6EE)
        val textPrimary = Color(0xFF1E2630)
        val textSecondary = Color(0xFF4A5568)
        val textTertiary = Color(0xFF6E7A8A)
        val textDisabled = Color(0xFFA0AAB8)
        val divider = Color(0xFFD0D5DD)
        val codeBg = Color(0xFF121820)
        val codeFg = Color(0xFFE4E7EC)
        val codeHeader = Color(0xFF253040)
        val codeBorder = Color(0xFF2A3444)
        val codeInlineBg = Color(0xFFE2E6EE)
        val sendDisabled = Color(0xFFDDE1E8)
    }

    /** Accent — deep indigo signature for interactive elements. */
    object Accent {
        val orange = Color(0xFF3E4C8A) // primary indigo
        val orangePressed = Color(0xFF2C376A)
        val orangeSoft = Color(0xFFD9DFF0) // light active chip fill
        val orangeSoftDark = Color(0xFF2C376A) // dark active chip fill
        val onOrange = Color(0xFFFFFFFF)
    }

    /** Semantic — clean, accessible hues. */
    object Semantic {
        val error = Color(0xFFB33A3A)
        val warning = Color(0xFFB8860B)
        val success = Color(0xFF2E7D32)
    }

    /** Voice-mode sphere gradient — indigo to lavender. */
    object Voice {
        val blue1 = Color(0xFF3E4C8A)
        val blue2 = Color(0xFF7C8FD8)
        val blue3 = Color(0xFFC4CEEF)
        val takeover = Color(0xFF0E141C)
    }
}

val LightJarvisColorScheme: ColorScheme = lightColorScheme(
    primary = JarvisColors.Accent.orange,
    onPrimary = JarvisColors.Accent.onOrange,
    primaryContainer = JarvisColors.Accent.orangeSoft,
    onPrimaryContainer = JarvisColors.Light.textPrimary,
    secondary = JarvisColors.Light.textSecondary,
    onSecondary = JarvisColors.Light.paper,
    secondaryContainer = JarvisColors.Light.surface,
    onSecondaryContainer = JarvisColors.Light.textPrimary,
    tertiary = JarvisColors.Semantic.success,
    onTertiary = Color(0xFFFBF9F4),
    background = JarvisColors.Light.canvas,
    onBackground = JarvisColors.Light.textPrimary,
    surface = JarvisColors.Light.canvas,
    onSurface = JarvisColors.Light.textPrimary,
    surfaceVariant = JarvisColors.Light.surface,
    onSurfaceVariant = JarvisColors.Light.textSecondary,
    error = JarvisColors.Semantic.error,
    onError = JarvisColors.Light.paper,
    outline = JarvisColors.Light.divider,
    outlineVariant = JarvisColors.Light.divider,
    surfaceContainerLowest = JarvisColors.Light.paper,
    surfaceContainerLow = JarvisColors.Light.paper,
    surfaceContainer = JarvisColors.Light.surface,
    surfaceContainerHigh = JarvisColors.Light.surfaceAlt,
    surfaceContainerHighest = JarvisColors.Light.surfaceAlt,
)

val DarkJarvisColorScheme: ColorScheme = darkColorScheme(
    primary = JarvisColors.Accent.orange,
    onPrimary = JarvisColors.Dark.canvas,
    primaryContainer = JarvisColors.Accent.orangeSoftDark,
    onPrimaryContainer = JarvisColors.Dark.textPrimary,
    secondary = JarvisColors.Dark.textSecondary,
    onSecondary = JarvisColors.Dark.canvas,
    secondaryContainer = JarvisColors.Dark.surface,
    onSecondaryContainer = JarvisColors.Dark.textPrimary,
    tertiary = JarvisColors.Semantic.success,
    onTertiary = JarvisColors.Dark.canvas,
    background = JarvisColors.Dark.canvas,
    onBackground = JarvisColors.Dark.textPrimary,
    surface = JarvisColors.Dark.canvas,
    onSurface = JarvisColors.Dark.textPrimary,
    surfaceVariant = JarvisColors.Dark.surface,
    onSurfaceVariant = JarvisColors.Dark.textSecondary,
    error = JarvisColors.Semantic.error,
    onError = JarvisColors.Dark.canvas,
    outline = JarvisColors.Dark.divider,
    outlineVariant = JarvisColors.Dark.divider,
    surfaceContainerLowest = JarvisColors.Dark.canvas,
    surfaceContainerLow = JarvisColors.Dark.surface,
    surfaceContainer = JarvisColors.Dark.surface,
    surfaceContainerHigh = JarvisColors.Dark.surfaceAlt,
    surfaceContainerHighest = JarvisColors.Dark.surfaceAlt,
)
