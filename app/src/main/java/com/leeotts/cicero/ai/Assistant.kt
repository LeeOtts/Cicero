package com.leeotts.cicero.ai

import android.util.Log
import com.leeotts.cicero.TAG

/** The outcome of running one tool call. */
data class ToolOutcome(
    val content: String,
    val image: Image? = null,
    val isError: Boolean = false,
)

/** Something the assistant can actually do. Phase 7 fills these in. */
interface Tool {
    val spec: ToolSpec
    suspend fun run(arguments: kotlinx.serialization.json.JsonObject): ToolOutcome
}

/**
 * Drives one turn: transcribe if needed, ask the brain, run any tools it asks
 * for, and loop until it answers in words.
 *
 * The loop is shared by all three backends — only the wire format differs, and
 * that lives in each [Brain].
 */
class Assistant(
    private val brain: Brain,
    private val transcriber: Transcriber,
    private val tools: List<Tool> = emptyList(),
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {

    data class Result(
        val spoken: String,
        val transcript: String?,
        val history: List<Msg>,
        /**
         * The last image any tool produced this turn - the one that actually
         * informed the answer, and what the caller persists with the turn.
         */
        val photo: Image? = null,
    )

    private val byName = tools.associateBy { it.spec.name }

    /**
     * Backends that can search know their own tools and need no telling. The
     * ones that cannot do need telling, or "what is it going to cost today"
     * comes back as a confident answer from training data.
     */
    private val effectiveSystem: String =
        if (brain.supportsWebSearch) systemPrompt else "$systemPrompt\n\n$NO_WEB_SEARCH"


    /**
     * @param audio the recorded utterance, or null when [text] is supplied directly
     * @param priorHistory earlier turns, for follow-up questions
     */
    suspend fun ask(
        audio: Audio? = null,
        text: String? = null,
        priorHistory: List<Msg> = emptyList(),
    ): Result {
        val transcript = when {
            text != null -> text
            audio == null -> throw BrainException("I did not catch anything to answer.")
            brain.acceptsAudio -> null // the brain hears it itself
            else -> transcriber.transcribe(audio)
        }

        val history = priorHistory.toMutableList()
        history += Msg.User(
            text = transcript,
            audio = if (brain.acceptsAudio) audio else null,
        )

        // Only advertise tools the backend can actually use. A model that cannot
        // call tools must not be handed them and left to improvise.
        val advertised = if (brain.supportsTools) tools.map { it.spec } else emptyList()
        var lastImage: Image? = null

        repeat(MAX_TOOL_ROUNDS) { round ->
            val reply = brain.respond(effectiveSystem, history, advertised)

            if (!reply.wantsTools) {
                val spoken = reply.text?.trim().orEmpty().ifBlank {
                    "I did not get an answer back."
                }
                history += Msg.Assistant(text = spoken)
                return Result(spoken, transcript, history, lastImage)
            }

            history += Msg.Assistant(reply.text, reply.toolCalls)
            Log.d(TAG, "round $round: running ${reply.toolCalls.map { it.name }}")

            reply.toolCalls.forEach { call ->
                val tool = byName[call.name]
                val outcome = if (tool == null) {
                    // Hallucinated tool name. Say so rather than silently dropping it.
                    ToolOutcome("There is no tool called ${call.name}.", isError = true)
                } else {
                    runCatching { tool.run(call.arguments) }.getOrElse { e ->
                        Log.e(TAG, "tool ${call.name} failed", e)
                        ToolOutcome(e.message ?: "That action failed.", isError = true)
                    }
                }
                outcome.image?.let { lastImage = it }
                history += Msg.ToolResult(
                    callId = call.id,
                    name = call.name,
                    content = outcome.content,
                    // Drop images the backend cannot see rather than sending bytes
                    // it will choke on.
                    image = outcome.image?.takeIf { brain.supportsVision },
                    isError = outcome.isError,
                )
            }
        }

        // Ran out of rounds without a spoken answer.
        val spoken = "I got stuck working on that."
        history += Msg.Assistant(text = spoken)
        return Result(spoken, transcript, history, lastImage)
    }

    companion object {
        /** Enough for look-then-answer plus a couple of follow-on actions. */
        const val MAX_TOOL_ROUNDS = 5

        val NO_WEB_SEARCH = """
            You cannot search the web. If an answer depends on current
            information - news, prices, opening hours, timetables, anything that
            changes - say plainly that you cannot look it up, rather than
            guessing from what you were trained on.
        """.trimIndent()

        val DEFAULT_SYSTEM_PROMPT = """
            You are a hands-free assistant running on a pair of smart glasses.

            Your answers are SPOKEN ALOUD, so:
            - Keep them to one or two short sentences unless asked for detail.
            - Never use markdown, bullet points, headings, or emoji.
            - Write numbers and units the way a person would say them.

            The user is wearing the glasses and may be walking or busy. The audio
            you receive is narrowband phone-call quality from a beamforming
            microphone, so it may be imperfect. If you genuinely cannot tell what
            was said, say so briefly rather than guessing.

            Use the look tool when the question is about what the user can see.
            If an image is too dark or blurry to answer from, say that plainly
            instead of inventing detail.
        """.trimIndent()
    }
}
