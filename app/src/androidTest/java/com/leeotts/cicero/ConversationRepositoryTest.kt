package com.leeotts.cicero

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.CiceroDatabase
import com.leeotts.cicero.data.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the threading rule, which is what makes the log read like a chat
 * rather than a flat list of one-off questions.
 */
@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTest {

    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from a clean database so runs do not contaminate each other.
        context.deleteDatabase("cicero.db")
        CiceroDatabase.get(context).clearAllTables()
        repository = ConversationRepository(context)
    }

    @Test
    fun turnsCloseTogetherShareAThread() = runBlocking {
        val t0 = 1_000_000_000L
        val first = repository.conversationFor(t0, "gemini")
        repository.addTurn(first, Role.USER, "what is that building", now = t0)

        // One minute later, well inside the five minute window.
        val second = repository.conversationFor(t0 + 60_000, "gemini")

        assertEquals("a follow-up should stay in the same thread", first, second)
    }

    @Test
    fun turnsFarApartStartNewThreads() = runBlocking {
        val t0 = 1_000_000_000L
        val first = repository.conversationFor(t0, "gemini")
        repository.addTurn(first, Role.USER, "first question", now = t0)

        // An hour later.
        val second = repository.conversationFor(t0 + 3_600_000, "gemini")

        assertNotEquals("an hour later should open a new thread", first, second)
    }

    /** Switching backend mid-thread would make the log misleading to compare. */
    @Test
    fun switchingBackendStartsANewThread() = runBlocking {
        val t0 = 1_000_000_000L
        val first = repository.conversationFor(t0, "gemini")
        repository.addTurn(first, Role.USER, "hello", now = t0)

        val second = repository.conversationFor(t0 + 1_000, "openai-compatible")

        assertNotEquals("a different brain should open a new thread", first, second)
    }

    @Test
    fun fullTextSearchFindsPastTurns() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.ASSISTANT, "The boiler pressure should be 1.5 bar", now = t0)
        repository.addTurn(id, Role.ASSISTANT, "Completely unrelated sentence", now = t0 + 1)

        val hits = repository.searchTurns("boiler")

        assertEquals(1, hits.size)
        assertTrue(hits[0].text.contains("boiler"))
    }

    /** FTS4 MATCH throws on some punctuation; a bad query must not crash a turn. */
    @Test
    fun searchSurvivesAwkwardQueries() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.ASSISTANT, "something searchable", now = t0)

        assertEquals(emptyList<Any>(), repository.searchTurns("   "))
        // Should return empty rather than throw. Wrapped in an assert so the
        // test function still returns Unit, which JUnit requires.
        assertEquals(emptyList<Any>(), repository.searchTurns("\"\"* ^&"))
    }

    @Test
    fun notesAreSearchable() = runBlocking {
        repository.addNote("part number is XR-4471", now = 1_000_000_000L)

        val hits = repository.searchNotes("XR-4471")

        assertEquals(1, hits.size)
    }

    // ----- deletion -----

    /**
     * The failure a plain `DELETE FROM conversations` would cause: the thread
     * disappears from the list while its text still answers searches, so the
     * assistant can quote a conversation the user believes they deleted.
     */
    @Test
    fun deletingAConversationAlsoClearsItsSearchIndex() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.ASSISTANT, "The boiler pressure should be 1.5 bar", now = t0)
        assertEquals(1, repository.searchTurns("boiler").size)

        repository.deleteConversation(id)

        assertEquals("deleted text must not stay searchable", 0, repository.searchTurns("boiler").size)
        assertEquals(emptyList<Any>(), repository.observeTurns(id).first())
        assertTrue(repository.observeConversations().first().none { it.id == id })
    }

    @Test
    fun deletingAConversationLeavesTheOthersAlone() = runBlocking {
        val t0 = 1_000_000_000L
        val doomed = repository.conversationFor(t0, "gemini")
        repository.addTurn(doomed, Role.ASSISTANT, "forget the kingfisher", now = t0)
        // An hour on, so this opens a thread of its own.
        val keeper = repository.conversationFor(t0 + 3_600_000, "gemini")
        repository.addTurn(keeper, Role.ASSISTANT, "remember the heron", now = t0 + 3_600_000)

        repository.deleteConversation(doomed)

        assertEquals(0, repository.searchTurns("kingfisher").size)
        assertEquals(1, repository.searchTurns("heron").size)
        assertTrue(repository.observeConversations().first().any { it.id == keeper })
    }

    /** Undo has to return the thread whole, searchable text included. */
    @Test
    fun restoringADeletedConversationBringsBackItsTurns() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.USER, "where did I park", now = t0)
        repository.addTurn(id, Role.ASSISTANT, "beside the chandlery", now = t0 + 1)

        val deleted = repository.deleteConversation(id)
        assertNotNull(deleted)
        repository.restoreConversation(deleted!!)

        assertEquals(2, repository.observeTurns(id).first().size)
        assertEquals(1, repository.searchTurns("chandlery").size)
        assertTrue(repository.observeConversations().first().any { it.id == id })
    }

    /**
     * Notes and conversations share one database, so the two bulk deletes have
     * to stop at each other's boundary - clearing history must not silently take
     * the user's notes with it.
     */
    @Test
    fun clearingHistoryLeavesNotesAlone() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.ASSISTANT, "the boiler again", now = t0)
        repository.addNote("part number is XR-4471", now = t0)

        repository.clearAllHistory()

        assertEquals(emptyList<Any>(), repository.observeConversations().first())
        assertEquals(0, repository.searchTurns("boiler").size)
        assertEquals("notes are not history", 1, repository.observeNotes().first().size)
    }

    @Test
    fun clearingNotesLeavesHistoryAlone() = runBlocking {
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(id, Role.ASSISTANT, "the boiler again", now = t0)
        repository.addNote("part number is XR-4471", now = t0)

        repository.clearAllNotes()

        assertEquals(emptyList<Any>(), repository.observeNotes().first())
        assertEquals("history is not notes", 1, repository.searchTurns("boiler").size)
    }

    /** Captures outlive their turns unless something deletes the files too. */
    @Test
    fun clearingHistoryRemovesCaptureFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val t0 = 1_000_000_000L
        val id = repository.conversationFor(t0, "gemini")
        repository.addTurn(
            id,
            Role.ASSISTANT,
            "a photo turn",
            photoJpeg = byteArrayOf(1, 2, 3),
            now = t0,
        )

        val path = repository.observeTurns(id).first().first().photoPath
        assertNotNull("the capture should have been written", path)
        assertTrue(File(path!!).exists())

        repository.clearAllHistory()

        assertTrue("the capture file should be gone", !File(path).exists())
    }
}
