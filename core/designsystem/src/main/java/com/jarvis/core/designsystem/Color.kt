package com.jarvis.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw token values — Jarvis's mobile-first palette derived from the Claude.com
 * web design language (design.md), adapted for Android:
 *
 *  - Canvas is warm cream `#FAF9F5` (light) / dark warm-ink `#141413` (dark).
 *  - Accent is warm coral `#CC785C` — the signature Anthropic coral, used on
 *    primary CTAs, the brand wordmark, and active indicators.
 *  - Dark navy `#181715` carries product surfaces (code blocks, mockups, footers).
 *  - Light cream `#EFE9DE` for feature card backgrounds.
 *  - Typography runs slab-serif display (Copernicus substitute) + humanist sans body.
 */
object JarvisColors {
    object Dark {
        val canvas = Color(0xFF141413)
        val surface = Color(0xFF181715)
        val surfaceAlt = Color(0xFF252220)
        val surfaceCard = Color(0xFF1E1C1A)
        val paper = Color(0xFF181715)
        val sidebar = Color(0xFF141413)
        val sidebarActive = Color(0xFF252220)
        val textPrimary = Color(0xFFF5F0E8)
        val textSecondary = Color(0xFFB8B0A4)
        val textTertiary = Color(0xFF8A8278)
        val textDisabled = Color(0xFF5A544C)
        val divider = Color(0xFF2A2624)
        val codeBg = Color(0xFF0E0D0C)
        val codeFg = Color(0xFFF5F0E8)
        val codeHeader = Color(0xFF252220)
        val codeBorder = Color(0xFF2A2624)
        val codeInlineBg = Color(0xFF252220)
    }

    object Light {
        val canvas = Color(0xFFFAF9F5)
        val surface = Color(0xFFF5F0E8)
        val surfaceAlt = Color(0xFFEFE9DE)
        val surfaceCard = Color(0xFFEFE9DE)
        val paper = Color(0xFFFCFCFA)
        val sidebar = Color(0xFFF5F0E8)
        val sidebarActive = Color(0xFFEFE9DE)
        val textPrimary = Color(0xFF141413)
        val textSecondary = Color(0xFF5A544C)
        val textTertiary = Color(0xFF8A8278)
        val textDisabled = Color(0xFFB8B0A4)
        val divider = Color(0xFFE0DAD0)
        val codeBg = Color(0xFF181715)
        val codeFg = Color(0xFFF5F0E8)
        val codeHeader = Color(0xFF252220)
        val codeBorder = Color(0xFF2A2624)
        val codeInlineBg = Color(0xFFEFE9DE)
    }

    /** Accent — warm coral primary, slightly muted, never cyan/blue. */
    object Accent {
        val orange = Color(0xFFCC785C)
        val orangePressed = Color(0xFFA9583E)
        val orangeSoft = Color(0xFFF0E0D8)
        val orangeSoftDark = Color(0xFF5C2E1E)
        val onOrange = Color(0xFFFFFFFF)
    }

    /** Semantic — clean, accessible hues that read well on cream. */
    object Semantic {
        val error = Color(0xFFB33A3A)
        val warning = Color(0xFFB8860B)
        val success = Color(0xFF2E7D32)
    }

    /** Voice-mode sphere gradient — coral to warm gold for a distinctive mobile look. */
    object Voice {
        val blue1 = Color(0xFFCC785C)
        val blue2 = Color(0xFFE8A55A)
        val blue3 = Color(0xFFF0D8B8)
        val takeover = Color(0xFF141413)
    }
}

val LightJarvisColorScheme: ColorScheme =
    lightColorScheme(
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
        surfaceVariant = JarvisColors.Light.surfaceAlt,
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

val DarkJarvisColorScheme: ColorScheme =
    darkColorScheme(
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
        surfaceVariant = JarvisColors.Dark.surfaceAlt,
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
