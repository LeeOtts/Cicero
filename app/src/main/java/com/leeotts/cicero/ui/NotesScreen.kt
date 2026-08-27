package com.leeotts.cicero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leeotts.cicero.NotesViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.data.Note
import com.leeotts.cicero.ui.components.EmptyState
import com.leeotts.cicero.ui.components.SectionCard
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    if (notes.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.notes_empty_title),
            // There is no in-app way to create one, so say where they come from —
            // otherwise this screen just looks broken.
            body = stringResource(R.string.notes_empty_body),
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
        items(notes, key = { it.id }) { note ->
            NoteRow(
                note = note,
                stampOf = { stamp.format(Date(it)) },
                onToggle = { viewModel.setDone(note, it) },
                onDelete = { viewModel.delete(note) },
            )
        }
    }
}

@Composable
private fun NoteRow(
    note: Note,
    stampOf: (Long) -> String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = note.done, onCheckedChange = onToggle)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.done) scheme.onSurfaceVariant else scheme.onSurface,
                    textDecoration = if (note.done) TextDecoration.LineThrough else null,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (note.remindAt != null) {
                        Icon(
                            Icons.Filled.Alarm,
                            contentDescription = stringResource(R.string.a11y_reminder),
                            tint = scheme.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = stampOf(note.remindAt ?: note.createdAt),
                        style = TechnicalStyle,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.notes_delete),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
