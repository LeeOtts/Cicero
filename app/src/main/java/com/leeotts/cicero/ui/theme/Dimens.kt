package com.leeotts.cicero.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing scale. Screens use [Space.lg] gutters and [Space.md] between cards. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val card = 12.dp
    val bubble = 16.dp

    /** The corner nearest the speaker is tightened, so bubbles read as tails. */
    val bubbleTail = 4.dp

    val userBubble = RoundedCornerShape(bubble, bubble, bubbleTail, bubble)
    val assistantBubble = RoundedCornerShape(bubble, bubble, bubble, bubbleTail)
}
