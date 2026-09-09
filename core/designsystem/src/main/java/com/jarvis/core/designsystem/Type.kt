package com.jarvis.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Font families — sans for all UI and prose, mono for code. */
object JarvisFont {
    val sans: FontFamily = FontFamily.Default
    val serif: FontFamily = FontFamily.Serif
    val mono: FontFamily = FontFamily.Monospace
}

/** Named type ramp. */
object JarvisText {

    val Display =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.5).sp,
        )


    val ConvTitle =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            letterSpacing = (-0.1).sp,
        )


    val H1 =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.3).sp,
        )
    val H2 =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 25.sp,
            letterSpacing = (-0.2).sp,
        )
    val H3 =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            letterSpacing = (-0.1).sp,
        )


    val AssistantBody =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 27.sp,
        )
    val AssistantBodyBold = AssistantBody.copy(fontWeight = FontWeight.SemiBold)
    val AssistantBodyItalic = AssistantBody.copy(fontStyle = FontStyle.Italic)


    val Body =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    val BodyMedium =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )


    val SenderLabel =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
    val Metadata =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    val Caption =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )


    val SectionHeader =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )


    val Chip =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    val Button =
        TextStyle(
            fontFamily = JarvisFont.sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 15.sp,
        )


    val Code =
        TextStyle(
            fontFamily = JarvisFont.mono,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
    val CodeLabel =
        TextStyle(
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
