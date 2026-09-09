package com.jarvis.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color


object JarvisColors {
    object Dark {


        val canvas = Color(0xFF141417)
        val surface = Color(0xFF1C1C1F)
        val surfaceDeep = Color(0xFF0B0B0D)
        val surfaceAlt = Color(0xFF26262B)
        val surfaceCard = Color(0xFF1C1C1F)
        val paper = Color(0xFF1C1C1F)
        val sidebar = Color(0xFF141417)
        val sidebarActive = Color(0xFF26262B)
        val textPrimary = Color(0xFFF5F5F7)
        val textSecondary = Color(0xFF9A9AA2)
        val textTertiary = Color(0xFF6E6E76)
        val textDisabled = Color(0xFF57575F)
        val divider = Color(0xFF2A2A30)
        val codeBg = Color(0xFF1C1C1F)
        val codeFg = Color(0xFFF5F5F7)
        val codeHeader = Color(0xFF26262B)
        val codeBorder = Color(0xFF2A2A30)
        val codeInlineBg = Color(0xFF2A2A30)
    }

    object Light {
        val canvas = Color(0xFFFFFFFF)
        val surface = Color(0xFFF7F7F8)
        val surfaceAlt = Color(0xFFEFEFF1)
        val surfaceCard = Color(0xFFF7F7F8)
        val paper = Color(0xFFFFFFFF)
        val sidebar = Color(0xFFF7F7F8)
        val sidebarActive = Color(0xFFEFEFF1)
        val textPrimary = Color(0xFF111111)
        val textSecondary = Color(0xFF6E6E73)
        val textTertiary = Color(0xFF8E8E93)
        val textDisabled = Color(0xFFC7C7CC)
        val divider = Color(0xFFECECF0)



        val codeBg = Color(0xFF111111)
        val codeFg = Color(0xFFF5F5F7)
        val codeHeader = Color(0xFF1E1E22)
        val codeBorder = Color(0xFF2C2C30)
        val codeInlineBg = Color(0xFFEFEFF1)
    }

    /** Accent - the single blue primary from the reference (#3D63F6). */
    object Accent {
        val primary = Color(0xFF3D63F6)
        val primaryPressed = Color(0xFF2F4FE0)
        val primarySoft = Color(0xFFE9EDFF)
        val primarySoftDark = Color(0xFF20294A)


        val primaryDark = Color(0xFF7B93FF)
        val primaryDarkPressed = Color(0xFF93A7FF)
        val onPrimary = Color(0xFFFFFFFF)
        val onPrimaryDark = Color(0xFF0B0B0D)
    }

    /** Semantic - hues from the reference palette that read well on both modes. */
    object Semantic {
        val error = Color(0xFFE5484D)
        val warning = Color(0xFFE8A55A)
        val success = Color(0xFF22A06B)
    }

    /** Voice-mode sphere gradient - blue accent family for a distinctive mobile look. */
    object Voice {
        val blue1 = Color(0xFF3D63F6)
        val blue2 = Color(0xFF7C9BFF)
        val blue3 = Color(0xFFD6E0FF)
        val takeover = Color(0xFF111114)
    }
}

val LightJarvisColorScheme: ColorScheme =
    lightColorScheme(
        primary = JarvisColors.Accent.primary,
        onPrimary = JarvisColors.Accent.onPrimary,
        primaryContainer = JarvisColors.Accent.primarySoft,
        onPrimaryContainer = JarvisColors.Light.textPrimary,
        secondary = JarvisColors.Light.textSecondary,
        onSecondary = JarvisColors.Light.paper,
        secondaryContainer = JarvisColors.Light.surface,
        onSecondaryContainer = JarvisColors.Light.textPrimary,
        tertiary = JarvisColors.Semantic.success,
        onTertiary = Color(0xFFFFFFFF),
        background = JarvisColors.Light.canvas,
        onBackground = JarvisColors.Light.textPrimary,
        surface = JarvisColors.Light.canvas,
        onSurface = JarvisColors.Light.textPrimary,
        surfaceVariant = JarvisColors.Light.surfaceAlt,
        onSurfaceVariant = JarvisColors.Light.textSecondary,
        error = JarvisColors.Semantic.error,
        onError = JarvisColors.Light.paper,
        outline = JarvisColors.Light.divider,
        outlineVariant = JarvisColors.Light.divider,
        surfaceContainerLowest = JarvisColors.Light.paper,
        surfaceContainerLow = JarvisColors.Light.surface,
        surfaceContainer = JarvisColors.Light.surface,
        surfaceContainerHigh = JarvisColors.Light.surfaceAlt,
        surfaceContainerHighest = JarvisColors.Light.surfaceAlt,
    )

val DarkJarvisColorScheme: ColorScheme =
    darkColorScheme(
        primary = JarvisColors.Accent.primaryDark,
        onPrimary = JarvisColors.Accent.onPrimaryDark,
        primaryContainer = JarvisColors.Accent.primarySoftDark,
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
        surfaceVariant = JarvisColors.Dark.surfaceAlt,
        onSurfaceVariant = JarvisColors.Dark.textSecondary,
        error = JarvisColors.Semantic.error,
        onError = JarvisColors.Dark.canvas,
        outline = JarvisColors.Dark.divider,
        outlineVariant = JarvisColors.Dark.divider,
        surfaceContainerLowest = JarvisColors.Dark.surfaceDeep,
        surfaceContainerLow = JarvisColors.Dark.surface,
        surfaceContainer = JarvisColors.Dark.surface,
        surfaceContainerHigh = JarvisColors.Dark.surfaceAlt,
        surfaceContainerHighest = JarvisColors.Dark.surfaceAlt,
    )