package com.leeotts.cicero.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun roleToString(role: Role): String = role.name
    @TypeConverter fun stringToRole(value: String): Role =
        runCatching { Role.valueOf(value) }.getOrDefault(Role.USER)
}

@Dao
interface CiceroDao {

    // ----- conversations -----

    @Insert suspend fun insertConversation(conversation: Conversation): Long
    @Update suspend fun updateConversation(conversation: Conversation)

    @Query("SELECT * FROM conversations ORDER BY endedAt DESC")
    fun observeConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): Conversation?

    /** The newest thread, used to decide whether a new turn joins it. */
    @Query("SELECT * FROM conversations ORDER BY endedAt DESC LIMIT 1")
    suspend fun latestConversation(): Conversation?

    // ----- turns -----

    @Insert suspend fun insertTurnRow(turn: Turn): Long
    @Insert suspend fun insertFts(fts: TurnFts)

    /** Keeps the FTS mirror in step with the turn it indexes. */
    @Transaction
    suspend fun insertTurn(turn: Turn): Long {
        val id = insertTurnRow(turn)
        if (turn.text.isNotBlank()) insertFts(TurnFts(rowId = id, text = turn.text))
        return id
    }

    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeTurns(conversationId: Long): Flow<List<Turn>>

    @Query("SELECT COUNT(*) FROM turns WHERE conversationId = :conversationId")
    suspend fun turnCount(conversationId: Long): Int

    @Query("SELECT * FROM turns WHERE conversationId = :conversationId")
    suspend fun turnsFor(conversationId: Long): List<Turn>

    @Query("SELECT * FROM turns")
    suspend fun allTurns(): List<Turn>

    @Query("DELETE FROM turns_fts WHERE rowid = :rowId")
    suspend fun deleteFts(rowId: Long)

    @Query("DELETE FROM turns_fts")
    suspend fun deleteAllFts()

    @Query("DELETE FROM turns WHERE conversationId = :conversationId")
    suspend fun deleteTurnsForConversation(conversationId: Long)

    @Query("DELETE FROM turns")
    suspend fun deleteAllTurnRows()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationRow(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversationRows()

    /**
     * Deletes one thread - turns, FTS mirror and all - and hands back everything
     * it held so [restoreConversation] can put it right back.
     *
     * There is no ON DELETE CASCADE on turns.conversationId (see [Turn]), and
     * `turns_fts` is a hand-maintained mirror rather than an external-content
     * table, so both are cleared here. Deleting only the conversation row would
     * leave orphaned turns and a search index still matching text the user
     * believes is gone.
     */
    @Transaction
    suspend fun deleteConversation(id: Long): DeletedConversation? {
        val conversation = conversation(id) ?: return null
        val turns = turnsFor(id)
        turns.forEach { deleteFts(it.id) }
        deleteTurnsForConversation(id)
        deleteConversationRow(id)
        return DeletedConversation(conversation, turns)
    }

    /**
     * Puts a deleted thread back, ids and all.
     *
     * @Insert with autoGenerate honours a non-zero id, so the thread returns
     * under the id its turns already point at, and insertTurn rebuilds the FTS
     * rows on the way through.
     */
    @Transaction
    suspend fun restoreConversation(deleted: DeletedConversation) {
        insertConversation(deleted.conversation)
        deleted.turns.forEach { insertTurn(it) }
    }

    /** Wipes every thread. Not undoable, so callers may delete capture files. */
    @Transaction
    suspend fun deleteAllConversations() {
        deleteAllFts()
        deleteAllTurnRows()
        deleteAllConversationRows()
    }

    /**
     * Full-text search across every turn, newest first.
     *
     * FTS4 `MATCH` is the whole point of the mirror table: it makes
     * "what did I note about the boiler" answerable.
     */
    @Query(
        """
        SELECT turns.* FROM turns
        JOIN turns_fts ON turns.id = turns_fts.rowid
        WHERE turns_fts MATCH :query
        ORDER BY turns.createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchTurns(query: String, limit: Int = 20): List<Turn>

    // ----- notes -----

    @Insert suspend fun insertNote(note: Note): Long

    /** Open items first, then whichever timestamp matters for the kind of note. */
    @Query("SELECT * FROM notes ORDER BY done ASC, COALESCE(remindAt, createdAt) DESC")
    fun observeNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE text LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchNotes(query: String, limit: Int = 20): List<Note>

    @Query("UPDATE notes SET text = :text WHERE id = :id")
    suspend fun updateNoteText(id: Long, text: String)

    @Query("UPDATE notes SET done = :done WHERE id = :id")
    suspend fun setNoteDone(id: Long, done: Boolean)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<Note>

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()
}

/**
 * Adds Turn.brainId, so a thread records which model answered each turn rather
 * than only which one it started with.
 *
 * Nullable, so existing rows need no default and simply read as unknown.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE turns ADD COLUMN brainId TEXT")
    }
}

@Database(
    entities = [Conversation::class, Turn::class, TurnFts::class, Note::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CiceroDatabase : RoomDatabase() {
    abstract fun dao(): CiceroDao

    companion object {
        @Volatile private var instance: CiceroDatabase? = null

        fun get(context: Context): CiceroDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CiceroDatabase::class.java,
                "cicero.db",
            )
                // No destructive fallback: this database is the user's log, and
                // losing it silently on an upgrade would be worse than crashing.
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
