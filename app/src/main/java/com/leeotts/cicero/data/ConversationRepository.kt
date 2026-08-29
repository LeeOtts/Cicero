package com.leeotts.cicero.data

import android.content.Context
import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.Brain
import com.leeotts.cicero.ai.BrainException
import com.leeotts.cicero.ai.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the conversation log and, crucially, the rule that decides what counts
 * as one conversation.
 */
class ConversationRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = CiceroDatabase.get(appContext).dao()

    fun observeConversations(): Flow<List<Conversation>> = dao.observeConversations()
    fun observeTurns(conversationId: Long): Flow<List<Turn>> = dao.observeTurns(conversationId)
    fun observeNotes(): Flow<List<Note>> = dao.observeNotes()

    /**
     * Returns the thread a turn arriving at [now] belongs to.
     *
     * Threading rule: a turn joins the current thread if it lands within
     * [THREAD_WINDOW_MS] of the last one AND the same backend is selected.
     * Otherwise it opens a new thread.
     *
     * [brainId] is the provider the *user* chose, not the one that answered.
     * Those differ once per-task routing is on, and keying on the answering
     * model would split a single conversation into one-turn threads every time
     * a follow-up routed differently. Which model actually answered is recorded
     * per turn instead, on [Turn.brainId].
     */
    suspend fun conversationFor(now: Long, brainId: String): Long {
        val latest = dao.latestConversation()
        if (latest != null &&
            latest.brainId == brainId &&
            now - latest.endedAt <= THREAD_WINDOW_MS
        ) {
            return latest.id
        }
        return dao.insertConversation(
            Conversation(startedAt = now, endedAt = now, brainId = brainId)
        )
    }

    suspend fun addTurn(
        conversationId: Long,
        role: Role,
        text: String,
        photoJpeg: ByteArray? = null,
        toolCallsJson: String? = null,
        brainId: String? = null,
        now: Long,
    ): Long {
        val path = photoJpeg?.let { savePhoto(it, now) }
        val id = dao.insertTurn(
            Turn(
                conversationId = conversationId,
                role = role,
                text = text,
                photoPath = path,
                toolCallsJson = toolCallsJson,
                brainId = brainId,
                createdAt = now,
            )
        )
        dao.conversation(conversationId)?.let { dao.updateConversation(it.copy(endedAt = now)) }
        return id
    }

    /**
     * Names a thread from its first exchange, once, using whichever backend is
     * active. Failure is silent on purpose: a missing title must never break the
     * thing the user actually asked for.
     */
    suspend fun ensureTitled(conversationId: Long, brain: Brain, firstQuestion: String) {
        val conversation = dao.conversation(conversationId) ?: return
        if (conversation.title != null) return
        val title = runCatching {
            brain.respond(
                system = "Reply with a short title of at most five words for the topic below. " +
                    "No quotes, no punctuation at the end, no preamble.",
                history = listOf(Msg.User(text = firstQuestion)),
                tools = emptyList(),
            ).text?.trim()?.take(60)
        }.getOrElse { e ->
            if (e is BrainException) Log.d(TAG, "auto-title skipped: ${e.spokenMessage}")
            null
        }
        dao.updateConversation(
            conversation.copy(title = title?.ifBlank { null } ?: firstQuestion.take(60))
        )
    }

    suspend fun searchTurns(query: String, limit: Int = 20): List<Turn> {
        // FTS4 MATCH rejects bare punctuation and treats some characters as
        // operators; quoting turns the whole thing into a literal phrase.
        val safe = query.replace("\"", " ").trim()
        if (safe.isBlank()) return emptyList()
        return runCatching { dao.searchTurns("\"$safe\"", limit) }.getOrElse {
            Log.e(TAG, "FTS query failed for '$safe'", it)
            emptyList()
        }
    }

    suspend fun searchNotes(query: String, limit: Int = 20): List<Note> =
        dao.searchNotes(query, limit)

    suspend fun addNote(text: String, remindAt: Long? = null, now: Long): Long =
        dao.insertNote(Note(text = text, createdAt = now, remindAt = remindAt))

    suspend fun updateNote(id: Long, text: String) = dao.updateNoteText(id, text)

    suspend fun setNoteDone(id: Long, done: Boolean) = dao.setNoteDone(id, done)

    suspend fun deleteNote(id: Long) = dao.deleteNote(id)

    /**
     * Puts a deleted note back. @PrimaryKey(autoGenerate = true) honours a
     * non-zero id, so the row returns under its original id - which the reminder
     * PendingIntent request code is derived from.
     */
    suspend fun restoreNote(note: Note): Long = dao.insertNote(note)

    /**
     * Captures arrive already JPEG-encoded from the look tool, so the bytes are
     * written straight through: decoding to a Bitmap only to re-compress would
     * lose quality for nothing.
     */
    private suspend fun savePhoto(jpeg: ByteArray, now: Long): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.filesDir, "captures").apply { mkdirs() }
                val file = File(dir, "capture-$now.jpg")
                file.outputStream().use { it.write(jpeg) }
                file.absolutePath
            }.getOrElse {
                Log.e(TAG, "failed to save capture", it)
                null
            }
        }

    companion object {
        /** Follow-ups within five minutes stay in the same thread. */
        const val THREAD_WINDOW_MS = 5 * 60 * 1000L
    }
}
