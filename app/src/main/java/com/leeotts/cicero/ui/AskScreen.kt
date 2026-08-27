package com.leeotts.cicero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leeotts.cicero.Exchange
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.components.Bubble
import com.leeotts.cicero.ui.components.EmptyState
import com.leeotts.cicero.ui.components.assistantBubbleStyle
import com.leeotts.cicero.ui.components.userBubbleStyle
import com.leeotts.cicero.ui.theme.Radius
import com.leeotts.cicero.ui.theme.Space

@Composable
fun AskScreen(
    exchanges: List<Exchange>,
    busy: Boolean,
    backendLabel: String,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the conversation down as it grows, and again when the thinking
    // bubble appears or resolves.
    LaunchedEffect(exchanges.size, busy) {
        val last = exchanges.size - if (busy) 0 else 1
        if (last >= 0) listState.animateScrollToItem(maxOf(last, 0))
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (exchanges.isEmpty() && !busy) {
                EmptyState(
                    title = stringResource(R.string.ask_empty_title),
                    body = stringResource(R.string.ask_empty_body, backendLabel),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    itemsIndexed(exchanges) { _, exchange ->
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Bubble(
                                text = exchange.question,
                                style = userBubbleStyle(),
                            )
                            Bubble(
                                text = exchange.answer,
                                style = assistantBubbleStyle(isError = exchange.isError),
                                footer = exchange.backend,
                            )
                        }
                    }

                    if (busy) {
                        item { ThinkingBubble() }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .imePadding()
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text(stringResource(R.string.ask_placeholder)) },
                    enabled = !busy,
                    maxLines = 4,
                    shape = RoundedCornerShape(Radius.bubble),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { onAsk(question); question = "" },
                    enabled = !busy && question.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.ask_send),
                    )
                }
            }
            if (exchanges.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ask_new_thread)) }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(Radius.bubble),
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium)
    }
}
