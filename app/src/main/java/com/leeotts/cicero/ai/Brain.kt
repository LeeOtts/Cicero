package com.leeotts.cicero.ai

import kotlinx.serialization.json.JsonObject

/** A tool the assistant may call. [parameters] is a JSON Schema object. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    /**
     * Said aloud while this tool runs, on the tools slow enough to be worth
     * announcing. Null on the quick ones - narrating an alarm being set takes
     * longer than setting it.
     *
     * A phrase, never the tool's name. It is read to someone wearing glasses,
     * and it has to survive a model asking for a tool that does not exist.
     */
    val progressPhrase: String? = null,
)

/** A tool invocation requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** An image, carried as raw bytes plus its mime type. */
data class Image(val bytes: ByteArray, val mimeType: String = "image/jpeg") {
    // ByteArray needs structural equality spelled out.
    override fun equals(other: Any?) = other is Image &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/** Recorded speech, as a WAV container. */
data class Audio(val wav: ByteArray, val sampleRate: Int) {
    override fun equals(other: Any?) = other is Audio &&
        sampleRate == other.sampleRate && wav.contentEquals(other.wav)
    override fun hashCode() = 31 * wav.contentHashCode() + sampleRate
}

/**
 * One entry of conversation history, in a provider-neutral shape. Each brain
 * translates these into its own wire format.
 */
sealed interface Msg {
    data class User(
        val text: String? = null,
        val audio: Audio? = null,
        val image: Image? = null,
    ) : Msg

    data class Assistant(
        val text: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : Msg

    /**
     * The outcome of running a [ToolCall].
     *
     * [image] is deliberately separate from [content]: the three providers encode
     * images in tool results incompatibly (or not at all), so every adapter emits
     * the image as a following *user* message instead, which all three support.
     */
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val image: Image? = null,
        val isError: Boolean = false,
    ) : Msg
}

/** What a brain returns for one turn. */
data class Reply(
    val text: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/** Raised for any backend failure, with a message safe to speak aloud. */
class BrainException(val spokenMessage: String, cause: Throwable? = null) :
    Exception(spokenMessage, cause)

/**
 * A swappable assistant backend.
 *
 * The capability flags exist so callers can degrade *honestly* rather than
 * silently: a model that cannot see should say so, and a model that cannot call
 * tools must not be allowed to quietly swallow "set an alarm for 7am".
 */
interface Brain {
    val id: String
    val displayName: String

    /** True only for Gemini today; everything else needs a [Transcriber] first. */
    val acceptsAudio: Boolean
    val supportsVision: Boolean
    val supportsTools: Boolean

    /**
     * True when the backend can look things up on the web for itself. This is a
     * server-side tool on both Gemini and Claude - declared in the request and
     * resolved before the reply comes back - so it never reaches the client tool
     * loop. A local model has no such thing, and must say so rather than
     * answering a question about today from training data.
     */
    val supportsWebSearch: Boolean

    suspend fun respond(
        system: String,
        history: List<Msg>,
        tools: List<ToolSpec>,
    ): Reply

    /** Cheap reachability/credential check for the settings screen. */
    suspend fun testConnection(): Result<String>
}
