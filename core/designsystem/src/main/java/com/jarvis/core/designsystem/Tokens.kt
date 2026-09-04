package com.jarvis.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing on the 8dp grid with the extended thread/list/editor margins: 12dp thread
 * insets, 16dp list insets, 20dp editor margins.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val huge = 32.dp
    val massive = 48.dp
}

/** Corner radii from the source specs: 4 inline-code, 12 code block, 18 bubble, 24 composer/sheet. */
object Radius {
    val codeInline = 4.dp
    val small = 10.dp
    val codeBlock = 12.dp
    val chip = 16.dp
    val bubble = 18.dp
    val input = 20.dp
    val composer = 24.dp
    val sheet = 24.dp
    val pill = 100.dp
}

/** Minimum tap target floor, independent of visual size. */
object TapTargets {
    val min = 48.dp
}

/**
 * Motion spec: send press 200ms scale, message slide-up 300ms ease-out, voice sphere
 * pulse 2s ease-in-out, cursor blink 300ms half-cycle, sheet rise 300ms spring.
 */
object Motion {
    const val sendPressMs = 200
    const val messageEnterMs = 300
    const val sheetRiseMs = 300
    const val fadeInMs = 250
    const val spherePulseMs = 2000
    const val cursorBlinkHalfMs = 300
    const val pressScale = 0.94f
}

/** Inter-based type scale; JetBrains Mono for code is handled by [JarvisText.Code]. */
@Deprecated("Use [JarvisText] — the named type ramp.")
object JarvisTypography {
    val display = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 36.sp)
    val title = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val body = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val caption = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val mono = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
}

/** Shape shortcuts used by bubbles, chips and code blocks. */
object JarvisShapes {
    val chip = RoundedCornerShape(Radius.chip)
    val codeBlock = RoundedCornerShape(Radius.codeBlock)
    val composer = RoundedCornerShape(Radius.composer)
    val pill = RoundedCornerShape(Radius.pill)
    val medium = RoundedCornerShape(Radius.codeBlock)
}
