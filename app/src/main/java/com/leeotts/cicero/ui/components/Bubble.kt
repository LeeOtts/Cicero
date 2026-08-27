package com.leeotts.cicero.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.leeotts.cicero.ui.theme.Radius
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle

/** Side, colour and corner shape for one speaker's turn. */
data class BubbleStyle(
    val container: Color,
    val content: Color,
    val alignment: Alignment.Horizontal,
    val shape: RoundedCornerShape,
)

/** You: purple, right-hand side. */
@Composable
fun userBubbleStyle() = BubbleStyle(
    container = MaterialTheme.colorScheme.primaryContainer,
    content = MaterialTheme.colorScheme.onPrimaryContainer,
    alignment = Alignment.End,
    shape = Radius.userBubble,
)

/** Cicero: neutral surface, left-hand side — or the error colours when it failed. */
@Composable
fun assistantBubbleStyle(isError: Boolean = false) = BubbleStyle(
    container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    },
    content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    },
    alignment = Alignment.Start,
    shape = Radius.assistantBubble,
)

/**
 * Tool output: the warm bronze, so a tool call is obviously neither you nor
 * Cicero speaking — it is the machinery in between.
 */
@Composable
fun toolBubbleStyle() = BubbleStyle(
    container = MaterialTheme.colorScheme.tertiaryContainer,
    content = MaterialTheme.colorScheme.onTertiaryContainer,
    alignment = Alignment.Start,
    shape = Radius.assistantBubble,
)

/**
 * One turn in a transcript, live or saved.
 *
 * Never full width: the ragged right edge is most of what makes a transcript
 * read as a conversation rather than a list.
 */
@Composable
fun Bubble(
    text: String,
    style: BubbleStyle,
    modifier: Modifier = Modifier,
    footer: String? = null,
    textStyle: TextStyle? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = style.alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxBubbleWidth)
                .background(style.container, style.shape)
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = textStyle ?: MaterialTheme.typography.bodyMedium,
                    color = style.content,
                )
            }
            content()
        }
        if (footer != null) {
            Text(
                text = footer,
                style = TechnicalStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs, start = Space.xs, end = Space.xs),
            )
        }
    }
}

private val MaxBubbleWidth = 300.dp
