package com.leeotts.cicero.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A thread of related turns, the way chats are grouped in a chat app.
 *
 * [brainId] records which backend answered, so the log doubles as a way to
 * compare Gemini against a local model on the same questions.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val title: String? = null,
    val brainId: String,
)

enum class Role { USER, ASSISTANT, TOOL }

@Entity(
    tableName = "turns",
    indices = [Index("conversationId"), Index("createdAt")],
)
data class Turn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: Role,
    val text: String,
    /** Absolute path to a captured photo, when one was taken this turn. */
    val photoPath: String? = null,
    val audioPath: String? = null,
    val toolCallsJson: String? = null,
    /**
     * Which backend produced this turn, for ASSISTANT and TOOL rows.
     *
     * Turn-level rather than thread-level because routing can hand a single
     * thread to more than one model. Null on USER turns, which no model wrote,
     * and on every row written before this column existed.
     */
    val brainId: String? = null,
    val createdAt: Long,
)

/**
 * Full-text mirror of [Turn.text], backing the search_log tool.
 *
 * Kept as a separate FTS4 table rather than making `turns` itself external
 * content, so ordinary queries stay simple and the index can be rebuilt.
 */
@Fts4
@Entity(tableName = "turns_fts")
data class TurnFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val text: String,
)

/**
 * A deleted thread, held just long enough for the undo snackbar.
 *
 * Not an entity - nothing persists it. It exists so a delete can be reversed
 * without the UI having to know that a thread is a conversation row plus turns
 * plus an FTS mirror.
 */
data class DeletedConversation(
    val conversation: Conversation,
    val turns: List<Turn>,
)

/** A free-standing note or reminder, not tied to a conversation. */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    /** Set when this is a reminder rather than a plain note. */
    val remindAt: Long? = null,
    val done: Boolean = false,
)
