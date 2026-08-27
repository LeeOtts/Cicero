package com.leeotts.cicero.ai

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Claude via the Messages API.
 *
 * Accepts text and images but **not audio**, so it always needs a [Transcriber]
 * in front of it. Strongest of the three at tool use.
 */
class ClaudeBrain(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** Overridable so tests can point at a local mock server. */
    private val baseUrl: String = BASE,
) : Brain {

    override val id = "claude"
    override val displayName = "Claude ($model)"
    override val acceptsAudio = false
    override val supportsVision = true
    override val supportsTools = true

    private val headers: Map<String, String>
        get() = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "content-type" to "application/json",
        )

    override suspend fun respond(
        system: String,
        history: List<Msg>,
        tools: List<ToolSpec>,
    ): Reply {
        if (apiKey.isBlank()) throw BrainException("No Claude API key is set.")

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            // Adaptive thinking at low effort: this is a spoken assistant, so
            // latency matters more than deep reasoning on most turns.
            putJsonObject("thinking") { put("type", "adaptive") }
            putJsonObject("output_config") { put("effort", "low") }
            put("messages", buildMessages(history))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.parameters)
                        })
                    }
                }
            }
        }

        return parse(
            Http.postJson(
                url = "$baseUrl/v1/messages",
                body = body,
                headers = headers,
                friendlyName = "Claude",
            )
        )
    }

    private fun imageBlock(image: Image) = buildJsonObject {
        put("type", "image")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", image.mimeType)
            put("data", Base64.encodeToString(image.bytes, Base64.NO_WRAP))
        }
    }

    private fun buildMessages(history: List<Msg>) = buildJsonArray {
        history.forEach { msg ->
            when (msg) {
                is Msg.User -> add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        msg.text?.let {
                            add(buildJsonObject { put("type", "text"); put("text", it) })
                        }
                        msg.image?.let { add(imageBlock(it)) }
                        // Audio is unreachable here; the transcriber runs first.
                    }
                })

                is Msg.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        msg.text?.let {
                            add(buildJsonObject { put("type", "text"); put("text", it) })
                        }
                        msg.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", call.arguments)
                            })
                        }
                    }
                })

                is Msg.ToolResult -> {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.callId)
                                put("content", msg.content)
                                if (msg.isError) put("is_error", true)
                            })
                        }
                    })
                    // Claude does allow images inside tool_result, but emitting a
                    // following user turn keeps all three backends on one code path.
                    msg.image?.let { img ->
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") { add(imageBlock(img)) }
                        })
                    }
                }
            }
        }
    }

    private fun parse(response: JsonObject): Reply {
        // stop_details is populated only on a refusal; guard before reading content.
        val stop = response["stop_reason"]?.jsonPrimitive?.contentOrNullSafe()
        if (stop == "refusal") {
            throw BrainException("Claude declined to answer that one.")
        }

        val content = response["content"]?.jsonArray ?: return Reply(text = null)
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()

        content.forEach { el ->
            val block = el.jsonObject
            when (block["type"]?.jsonPrimitive?.contentOrNullSafe()) {
                "text" -> block["text"]?.jsonPrimitive?.contentOrNullSafe()?.let(text::append)
                "tool_use" -> {
                    val name = block["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@forEach
                    calls += ToolCall(
                        id = block["id"]?.jsonPrimitive?.contentOrNullSafe() ?: name,
                        name = name,
                        arguments = block["input"]?.jsonObject ?: JsonObject(emptyMap()),
                    )
                }
                // "thinking" blocks are ignored: display defaults to omitted.
            }
        }
        return Reply(text.toString().ifBlank { null }, calls)
    }

    override suspend fun testConnection(): Result<String> = runCatching {
        if (apiKey.isBlank()) throw BrainException("No Claude API key is set.")
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Reply with the single word: ok")
                })
            })
        }
        Http.postJson("$baseUrl/v1/messages", body, headers, "Claude")
        "Connected to $model"
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        /** Cheaper and much faster; a sensible pick for a voice assistant. */
        const val FAST_MODEL = "claude-haiku-4-5"
        const val BASE = "https://api.anthropic.com"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 4096
    }
}
