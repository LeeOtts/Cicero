package com.leeotts.cicero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.leeotts.cicero.R
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.ui.components.EmptyState
import com.leeotts.cicero.ui.components.SectionCard
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The conversation list.
 *
 * The detail half used to live here behind an `if (openId == null)`, which meant
 * the system Back button left the app instead of returning to this list. It is
 * now [ThreadScreen] on its own route, so the back stack does that work.
 */
@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conversations.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.history_empty_title),
            body = stringResource(R.string.history_empty_body),
            modifier = modifier,
        )
        return
    }

    val pattern = stringResource(R.string.format_date_time)
    val stamp = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        items(conversations, key = { it.id }) { conversation ->
            SectionCard(onClick = { onOpen(conversation.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Text(
                            text = conversation.title
                                ?: stringResource(R.string.untitled_conversation),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.meta_pair,
                                stamp.format(Date(conversation.startedAt)),
                                conversation.brainId,
                            ),
                            style = TechnicalStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(conversation.id) }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.history_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
