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
}
