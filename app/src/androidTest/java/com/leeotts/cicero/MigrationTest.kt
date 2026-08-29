package com.leeotts.cicero

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.data.CiceroDatabase
import com.leeotts.cicero.data.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the schema bump rather than hoping for it.
 *
 * The database has no destructive fallback on purpose - it is the user's log -
 * so a migration that does not apply cleanly is a crash on launch after an
 * update, which is exactly the failure nobody notices until it ships.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CiceroDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsExistingTurnsAndAddsBrainId() {
        val conversationId: Long
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO conversations (startedAt, endedAt, title, brainId) " +
                    "VALUES (1000, 2000, 'A thread', 'gemini')"
            )
            conversationId = 1L
            db.execSQL(
                "INSERT INTO turns (conversationId, role, text, createdAt) " +
                    "VALUES ($conversationId, 'USER', 'what time is it', 1500)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        db.query("SELECT text, brainId FROM turns").use { cursor ->
            assertTrue("the pre-upgrade turn was lost", cursor.moveToFirst())
            assertEquals("what time is it", cursor.getString(0))
            // Nullable on purpose: nothing recorded which model answered before.
            assertTrue(cursor.isNull(1))
            assertEquals(1, cursor.count)
        }
    }

    @Test
    fun migrate1To2_acceptsABrainIdOnNewRows() {
        helper.createDatabase(DB, 1).close()
        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        db.execSQL(
            "INSERT INTO conversations (startedAt, endedAt, brainId) VALUES (1, 2, 'openrouter')"
        )
        db.execSQL(
            "INSERT INTO turns (conversationId, role, text, brainId, createdAt) " +
                "VALUES (1, 'ASSISTANT', 'half past four', 'openrouter', 3)"
        )
        db.query("SELECT brainId FROM turns").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("openrouter", cursor.getString(0))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
