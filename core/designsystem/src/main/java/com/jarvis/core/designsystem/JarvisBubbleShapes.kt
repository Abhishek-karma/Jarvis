package com.jarvis.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** User-message silhouette: a soft pill — never a bubble with a tail. */
object JarvisBubbleShapes {
    /** Right-aligned user message: 18dp on every corner. */
    val user: Shape = RoundedCornerShape(Radius.bubble)
}
