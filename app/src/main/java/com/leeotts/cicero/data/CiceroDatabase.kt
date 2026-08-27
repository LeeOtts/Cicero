package com.leeotts.cicero.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
}

@Database(
    entities = [Conversation::class, Turn::class, TurnFts::class, Note::class],
    version = 1,
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
            ).build().also { instance = it }
        }
    }
}
