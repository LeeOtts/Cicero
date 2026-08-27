package com.leeotts.cicero.ui

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.leeotts.cicero.R
import com.leeotts.cicero.data.Role
import com.leeotts.cicero.data.Turn
import com.leeotts.cicero.ui.components.Bubble
import com.leeotts.cicero.ui.components.EmptyState
import com.leeotts.cicero.ui.components.assistantBubbleStyle
import com.leeotts.cicero.ui.components.toolBubbleStyle
import com.leeotts.cicero.ui.components.userBubbleStyle
import com.leeotts.cicero.ui.theme.Radius
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One saved conversation, in the same bubbles the live Ask screen uses — so a
 * thread you come back to reads the way it did when you had it.
 *
 * Up is the app bar's job, so there is no in-content back button.
 */
@Composable
fun ThreadScreen(
    turns: List<Turn>,
    modifier: Modifier = Modifier,
) {
    if (turns.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.thread_empty_title),
            body = stringResource(R.string.thread_empty_body),
            modifier = modifier,
        )
        return
    }

    val pattern = stringResource(R.string.format_time)
    val stamp = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        items(turns, key = { it.id }) { turn ->
            Bubble(
                text = turn.text,
                style = when (turn.role) {
                    Role.USER -> userBubbleStyle()
                    Role.ASSISTANT -> assistantBubbleStyle()
                    Role.TOOL -> toolBubbleStyle()
                },
                footer = stringResource(
                    R.string.meta_pair,
                    stringResource(roleLabel(turn.role)),
                    stamp.format(Date(turn.createdAt)),
                ),
                // Tool output is machine text; the mono face says so without a label.
                textStyle = if (turn.role == Role.TOOL) TechnicalStyle else null,
            ) {
                // Decoded lazily per row; captures are small JPEGs.
                turn.photoPath?.let { path ->
                    val bitmap = remember(path) {
                        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.a11y_captured_photo),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.bubbleTail)),
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun roleLabel(role: Role): Int = when (role) {
    Role.USER -> R.string.role_user
    Role.ASSISTANT -> R.string.role_assistant
    Role.TOOL -> R.string.role_tool
}
