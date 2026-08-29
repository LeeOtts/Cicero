package com.leeotts.cicero.tools

import android.content.Context
import android.text.format.DateUtils
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.util.hasNotificationAccess
import kotlinx.serialization.json.JsonObject

/**
 * Reads back recent incoming messages.
 *
 * This is the one thing the glasses cannot do on their own: Meta AI will read a
 * message only in the short window after it announces it, and has no command for
 * recalling anything older. [MessageLog] keeps the history; this hands it to the
 * brain in a form that is meant to be spoken.
 */
class ReadMessagesTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "read_messages",
        description = "Read the user's recent incoming messages — texts, WhatsApp, Messenger. " +
            "Use for 'read my messages', 'what did Sam say', 'did I miss anything'. " +
            "Only sees messages that arrived while Cicero was running.",
        parameters = Schemas.obj(
            "sender" to Schemas.string("Only messages from this person. Omit for everyone."),
            "count" to Schemas.integer("How many messages to read back. Defaults to 3."),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        if (!context.hasNotificationAccess()) {
            return ToolOutcome(
                "I need Notification Access to read your messages. " +
                    "Turn it on for Cicero in Android settings.",
                isError = true,
            )
        }

        val sender = arguments.str("sender")
        val count = arguments.int("count")?.coerceIn(1, MAX_READ) ?: DEFAULT_READ

        val messages = MessageLog.recent(count, sender)
        if (messages.isEmpty()) {
            // Distinguish "wrong name" from "nothing has arrived" — otherwise the
            // brain guesses, and the user retries a question that cannot work.
            val known = MessageLog.senders()
            return ToolOutcome(
                when {
                    sender != null && known.isNotEmpty() ->
                        "Nothing from $sender. Recent messages are from ${known.joinToString(", ")}."
                    sender != null -> "Nothing from $sender, and no messages at all yet."
                    else -> "No messages have come in since Cicero started running."
                },
            )
        }

        return ToolOutcome(messages.joinToString(" ") { describe(it) })
    }

    private fun describe(message: CapturedMessage): String {
        val ago = DateUtils.getRelativeTimeSpanString(
            message.at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        return "${message.sender} on ${message.app} $ago: ${message.body}."
    }

    private companion object {
        const val DEFAULT_READ = 3
        const val MAX_READ = 20
    }
}
