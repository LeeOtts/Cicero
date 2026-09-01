package com.leeotts.cicero

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.Note
import com.leeotts.cicero.tools.Reminders
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Notes and reminders the assistant saved.
 *
 * These have always been persisted; until now there was no screen, so anything
 * Cicero remembered was invisible.
 */
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConversationRepository(app)

    // WhileSubscribed rather than Eagerly: this is one destination of five and
    // need not hold a cursor open for the whole session.
    val notes: StateFlow<List<Note>> = repository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private var lastDeleted: Note? = null

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addNote(trimmed, now = System.currentTimeMillis())
        }
    }

    /**
     * Edits the text only. A reminder keeps its alarm, because the PendingIntent
     * request code is derived from the note id, which does not change here.
     */
    fun edit(note: Note, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == note.text) return
        viewModelScope.launch { repository.updateNote(note.id, trimmed) }
    }

    fun setDone(note: Note, done: Boolean) {
        viewModelScope.launch {
            repository.setNoteDone(note.id, done)
            // A completed reminder should stop being a pending alarm.
            if (done && note.remindAt != null) Reminders.cancel(getApplication(), note.id)
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            lastDeleted = note
            repository.deleteNote(note.id)
            if (note.remindAt != null) Reminders.cancel(getApplication(), note.id)
            _messages.send(
                UiMessage(
                    text = getApplication<Application>().getString(R.string.notes_deleted),
                    actionLabel = getApplication<Application>().getString(R.string.action_undo),
                    action = ::restore,
                )
            )
        }
    }

    private fun restore() {
        val note = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            // insertNote honours the non-zero id, so the row comes back with the
            // same id the reminder request code was built from.
            repository.restoreNote(note)
        }
    }

    /**
     * Deletes every note. No undo - the confirmation dialog is the safety net -
     * so pending reminders are cancelled rather than left to fire against rows
     * that no longer exist.
     */
    fun clearAll() {
        viewModelScope.launch {
            repository.allNotes()
                .filter { it.remindAt != null }
                .forEach { Reminders.cancel(getApplication(), it.id) }
            repository.clearAllNotes()
            lastDeleted = null
            _messages.send(
                UiMessage(text = getApplication<Application>().getString(R.string.notes_cleared)),
            )
        }
    }
}
