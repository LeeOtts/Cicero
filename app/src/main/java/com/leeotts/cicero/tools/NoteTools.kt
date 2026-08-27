package com.leeotts.cicero.tools

import android.service.notification.NotificationListenerService
import com.leeotts.cicero.ai.Image
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.glasses.GlassesController
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exists only so the system will grant Notification Access, which is the gate
 * for enumerating other apps' media sessions. It intentionally does nothing
 * with the notifications themselves.
 */
class CiceroNotificationListener : NotificationListenerService()

class SaveNoteTool(
    private val repository: ConversationRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {

    override val spec = ToolSpec(
        name = "save_note",
        description = "Save a short note the user wants to remember and find later.",
        parameters = Schemas.obj(
            "text" to Schemas.string("The note to save"),
            required = listOf("text"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val text = arguments.str("text")
            ?: return ToolOutcome("I did not catch what to note down.", isError = true)
        repository.addNote(text, now = now())
        return ToolOutcome("Noted.")
    }
}

/**
 * Searches past conversations and notes. This is what makes the log useful
 * rather than merely present.
 */
class SearchLogTool(private val repository: ConversationRepository) : Tool {

    override val spec = ToolSpec(
        name = "search_log",
        description = "Search everything the user has previously said, been told, or noted down.",
        parameters = Schemas.obj(
            "query" to Schemas.string("What to search for"),
            required = listOf("query"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.str("query")
            ?: return ToolOutcome("I need something to search for.", isError = true)

        val turns = repository.searchTurns(query, limit = 8)
        val notes = repository.searchNotes(query, limit = 8)
        if (turns.isEmpty() && notes.isEmpty()) {
            return ToolOutcome("I could not find anything about that.")
        }

        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val lines = buildList {
            notes.forEach { add("Note (${stamp.format(Date(it.createdAt))}): ${it.text}") }
            turns.forEach {
                add("${it.role.name.lowercase()} (${stamp.format(Date(it.createdAt))}): ${it.text}")
            }
        }
        return ToolOutcome(lines.joinToString("\n").take(2000))
    }
}

/**
 * Captures a photo from the glasses.
 *
 * The session is opened only for the capture and torn down straight after,
 * because an open DAT session suspends Meta AI's own features.
 */
class LookTool(private val glasses: GlassesController) : Tool {

    override val spec = ToolSpec(
        name = "look",
        description = "Take a photo through the glasses camera to see what the user is looking at. " +
            "Use this whenever the question is about the user's surroundings.",
        parameters = Schemas.empty,
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val bitmap = try {
            glasses.capture()
        } finally {
            glasses.release()
        } ?: return ToolOutcome(
            "I could not get a picture from the glasses. " +
                "They may be disconnected, folded, or not being worn.",
            isError = true,
        )

        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return ToolOutcome(
            content = "Photo captured; it is attached.",
            image = Image(jpeg, "image/jpeg"),
        )
    }
}
