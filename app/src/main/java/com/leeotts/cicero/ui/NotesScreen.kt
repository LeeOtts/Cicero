package com.leeotts.cicero.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    // Two pieces of state rather than one nullable Note, because "closed" and
    // "open on a new note" are both the absence of a note being edited.
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }

    fun close() {
        editorOpen = false
        editing = null
    }

    Box(modifier.fillMaxSize()) {
        if (notes.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.notes_empty_title),
                body = stringResource(R.string.notes_empty_body),
            )
        } else {
            val pattern = stringResource(R.string.format_date_time)
            val stamp = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                items(notes, key = { it.id }) { note ->
                    NoteRow(
                        note = note,
                        stampOf = { stamp.format(Date(it)) },
                        onToggle = { viewModel.setDone(note, it) },
                        onEdit = {
                            editing = note
                            editorOpen = true
                        },
                        onDelete = { viewModel.delete(note) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                editing = null
                editorOpen = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.notes_add))
        }
    }

    if (editorOpen) {
        val target = editing
        NoteEditor(
            initial = target?.text.orEmpty(),
            isNew = target == null,
            onDismiss = ::close,
            onSave = { text ->
                if (target == null) viewModel.add(text) else viewModel.edit(target, text)
                close()
            },
        )
    }
}

/**
 * Deliberately a dialog rather than a destination: a note is a sentence, and
 * pushing a screen onto the back stack for one line of text is more ceremony
 * than the thing deserves.
 */
@Composable
private fun NoteEditor(
    initial: String,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.notes_new_title else R.string.notes_edit_title,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.notes_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NoteRow(
    note: Note,
    stampOf: (Long) -> String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = note.done, onCheckedChange = onToggle)

            Column(
                modifier = Modifier
                    .weight(1f)
                    // The text is the edit affordance; the checkbox and the bin
                    // keep their own hit targets either side of it.
                    .clickable(onClick = onEdit)
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
