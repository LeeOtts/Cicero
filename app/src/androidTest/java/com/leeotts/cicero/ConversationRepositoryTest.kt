package com.leeotts.cicero

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.CiceroDatabase
import com.leeotts.cicero.data.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
}
