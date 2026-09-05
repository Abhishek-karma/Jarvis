package com.jarvis.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing on the 8dp grid. */
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

/** Corner radii. */
object Radius {
    val codeInline = 4.dp
    val small = 8.dp
    val codeBlock = 12.dp
    val card = 12.dp
    val chip = 16.dp
    val bubble = 18.dp
    val composer = 24.dp
    val sheet = 24.dp
    val pill = 100.dp
}

/** Minimum tap target — 48dp Android. */
object TapTargets {
    val min = 48.dp
}

/** Motion timing. */
object Motion {
    const val SPHERE_PULSE_MS = 2000
    const val CURSOR_BLINK_HALF_MS = 300
    const val PRESS_SCALE = 0.94f
}

/** Shape shortcuts used by bubbles, chips, cards and code blocks. */
object JarvisShapes {
    val chip = RoundedCornerShape(Radius.chip)
    val codeBlock = RoundedCornerShape(Radius.codeBlock)
    val composer = RoundedCornerShape(Radius.composer)
    val pill = RoundedCornerShape(Radius.pill)
    val card = RoundedCornerShape(Radius.card)
    val input = RoundedCornerShape(Radius.small) // text fields, inputs
}
