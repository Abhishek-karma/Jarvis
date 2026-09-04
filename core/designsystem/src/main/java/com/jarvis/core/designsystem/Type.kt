package com.jarvis.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Named type ramp: the platform sans for UI chrome and user messages, a serif for
 * assistant prose, monospace for code. Weights 400/500/600/700, display capped at 32sp.
 */
object JarvisFont {
    /** UI chrome and user messages. */
    val sans: FontFamily = FontFamily.Default

    /** Assistant message body and headings. */
    val serif: FontFamily = FontFamily.Serif

    /** Code. */
    val mono: FontFamily = FontFamily.Monospace
}

/** Named ramp — pt → sp 1:1. */
object JarvisText {
    // Display (about / empty state)
    val Display = TextStyle(
        fontFamily = JarvisFont.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    )

    // Screen & conversation titles (chrome)
    val ConvTitle = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp,
    )

    // Markdown headings inside assistant responses (serif)
    val H1 = TextStyle(
        fontFamily = JarvisFont.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.2).sp,
    )
    val H2 = TextStyle(
        fontFamily = JarvisFont.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    )
    val H3 = TextStyle(
        fontFamily = JarvisFont.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    )

    // Assistant body — 1.55× leading, non-negotiable (Claude signature)
    val AssistantBody = TextStyle(
        fontFamily = JarvisFont.serif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    )
    val AssistantBodyBold = AssistantBody.copy(fontWeight = FontWeight.SemiBold)
    val AssistantBodyItalic = AssistantBody.copy(fontStyle = FontStyle.Italic)

    // User messages / general body (sans)
    val Body = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    val BodyMedium = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    // Metadata
    val SenderLabel = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )
    val Metadata = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val Caption = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

    // Sidebar section headers — sentence case, no tracking
    val SectionHeader = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )

    // Chip / button
    val Chip = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )
    val Button = TextStyle(
        fontFamily = JarvisFont.sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 15.sp,
    )

    // Code
    val Code = TextStyle(
        fontFamily = JarvisFont.mono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
    val CodeLabel = TextStyle(
        fontFamily = JarvisFont.mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}

/** Material 3 mapping so stock components (bars, buttons, fields) inherit the ramp. */
object JarvisMaterialTypography {
    val displayLarge = JarvisText.Display
    val displayMedium = JarvisText.H1
    val titleLarge = JarvisText.ConvTitle
    val titleMedium = JarvisText.Body.copy(fontWeight = FontWeight.SemiBold)
    val titleSmall = JarvisText.BodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val bodyLarge = JarvisText.Body
    val bodyMedium = JarvisText.BodyMedium
    val bodySmall = JarvisText.Metadata
    val labelLarge = JarvisText.Button
    val labelMedium = JarvisText.SenderLabel
    val labelSmall = JarvisText.Caption
}
