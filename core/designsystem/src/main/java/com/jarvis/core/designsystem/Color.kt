package com.jarvis.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Raw token values — the full Claude identity layer ported from
 * `design/claude/DESIGN-android.md §1`. The app reads like warm paper, never cold chrome:
 *
 *  - Canvas is cream `#F8F4ED` (light) / warm near-black `#1F1B16` (dark).
 *  - Text is warm ink (`#2D2520` light / `#E8E0D2` dark) — never pure black or white.
 *  - Claude Orange `#D97757` is the single signature accent.
 *  - Code blocks are warm-dark in both themes — never blue-tinted.
 *  - The voice-mode takeover keeps ChatGPT's pulsing blue sphere (its flagship moment).
 */
object JarvisColors {
    object Dark {
        val canvas = Color(0xFF1F1B16) // warm near-black, orange-undertoned
        val surface = Color(0xFF2A2520) // Dark Surface 1: pills, chips, rows
        val surfaceAlt = Color(0xFF3A332C) // Dark Surface 2: pressed / elevated
        val paper = Color(0xFF2A2520) // input fill, raised surfaces
        val sidebar = Color(0xFF2A2520) // history drawer
        val sidebarActive = Color(0xFF3A332C)
        val textPrimary = Color(0xFFE8E0D2) // warm cream type
        val textSecondary = Color(0xFFB5AB9E) // graphite-warm
        val textTertiary = Color(0xFF8A7E72)
        val textDisabled = Color(0xFF6E6357)
        val divider = Color(0xFF3A332C)
        val codeBg = Color(0xFF26221D) // warm code surface, slightly raised off canvas
        val codeFg = Color(0xFFE8E0D2)
        val codeHeader = Color(0xFF3A332C)
        val codeBorder = Color(0xFF3A332C)
        val codeInlineBg = Color(0xFF3A332C)
        val sendDisabled = Color(0xFF3A332C)
    }

    object Light {
        val canvas = Color(0xFFF8F4ED) // cream paper
        val surface = Color(0xFFF0EAE0) // Surface Warm 1: user pill, chips, callouts
        val surfaceAlt = Color(0xFFE8E0D2) // Surface Warm 2: pressed / chip fills
        val paper = Color(0xFFFBF9F4) // Paper White: input fill, raised surfaces
        val sidebar = Color(0xFFF0EAE0) // history drawer
        val sidebarActive = Color(0xFFE8E0D2)
        val textPrimary = Color(0xFF2D2520) // warm ink, not pure black
        val textSecondary = Color(0xFF5A4F44) // graphite-warm
        val textTertiary = Color(0xFF8A7E72) // stone-warm
        val textDisabled = Color(0xFFB5AB9E) // bone-warm
        val divider = Color(0xFFDDD2BD) // divider sand
        val codeBg = Color(0xFF1F1B16) // warm-dark code block on the cream canvas
        val codeFg = Color(0xFFE8E0D2)
        val codeHeader = Color(0xFF3A332C)
        val codeBorder = Color(0xFF3A332C)
        val codeInlineBg = Color(0xFFE8E0D2)
        val sendDisabled = Color(0xFFE8E0D2)
    }

    /** Claude Orange — the single signature accent (send, cursor, logomark, links). */
    object Accent {
        val orange = Color(0xFFD97757)
        val orangePressed = Color(0xFFBE6242)
        val orangeSoft = Color(0xFFF2DDD0) // active chip / "Thinking…" fill (light)
        val orangeSoftDark = Color(0xFF4A352A) // active chip fill (dark)
        val onOrange = Color(0xFFFBF9F4) // Paper White glyph on orange
    }

    /** Semantic — warm-toned, never pure. */
    object Semantic {
        val error = Color(0xFFC16654) // terracotta red
        val warning = Color(0xFFD49952) // warm amber
        val success = Color(0xFF6B9D5E) // sage
    }

    /** Voice-mode sphere gradient (ChatGPT voice takeover — kept for its flagship moment). */
    object Voice {
        val blue1 = Color(0xFF3B82F6)
        val blue2 = Color(0xFF60A5FA)
        val blue3 = Color(0xFF93C5FD)
        val takeover = Color(0xFF1B1713) // warm near-black behind the sphere
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
