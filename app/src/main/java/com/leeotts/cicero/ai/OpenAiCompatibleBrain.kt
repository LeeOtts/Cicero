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
 * Any server speaking the OpenAI chat-completions API.
 *
 * One implementation covers LM Studio, Ollama, llama.cpp server, vLLM, LocalAI
 * and OpenRouter — they all expose /v1/chat/completions.
 *
 * Capability flags are constructor arguments because they are a property of the
 * loaded *model*, not the protocol: a 7B text model and a vision model behind
 * the same LM Studio endpoint differ. Getting these wrong is how "set an alarm"
 * silently does nothing, so they are surfaced in settings rather than guessed.
 */
class OpenAiCompatibleBrain(
    baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    override val supportsVision: Boolean = false,
    override val supportsTools: Boolean = true,
    override val displayName: String = "Local ($model)",
) : Brain {

    override val id = "openai-compatible"
    override val acceptsAudio = false

    /** No OpenAI-compatible server offers a hosted search tool. */
    override val supportsWebSearch = false

    // Accept a base url with or without a trailing /v1 so either can be pasted.
    private val root = baseUrl.trimEnd('/').removeSuffix("/v1")

    private val authHeaders: Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun respond(
        system: String,
        history: List<Msg>,
        tools: List<ToolSpec>,
    ): Reply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildMessages(system, history))
            put("stream", false)
            if (tools.isNotEmpty() && supportsTools) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            }
                        })
                    }
                }
            }
        }

        return parse(
            Http.postJson(
                url = "$root/v1/chat/completions",
                body = body,
                headers = authHeaders,
                friendlyName = displayName,
            )
        )
    }

    private fun imagePart(image: Image) = buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") {
            val b64 = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            put("url", "data:${image.mimeType};base64,$b64")
        }
    }

    private fun buildMessages(system: String, history: List<Msg>) = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", system)
        })
        history.forEach { msg ->
            when (msg) {
                is Msg.User -> add(buildJsonObject {
                    put("role", "user")
                    val image = msg.image
                    if (image != null && supportsVision) {
                        putJsonArray("content") {
                            msg.text?.let {
                                add(buildJsonObject { put("type", "text"); put("text", it) })
                            }
                            add(imagePart(image))
                        }
                    } else {
                        put("content", msg.text.orEmpty())
                    }
                })

                is Msg.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", msg.text.orEmpty())
                    if (msg.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            msg.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        // Arguments are a JSON *string* here, not an object.
                                        put("arguments", call.arguments.toString())
                                    }
                                })
                            }
                        }
                    }
                })

                is Msg.ToolResult -> {
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", msg.callId)
                        put("content", msg.content)
                    })
                    // Tool messages are text-only here, so an image follows as a user turn.
                    val image = msg.image
                    if (image != null && supportsVision) {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") { add(imagePart(image)) }
                        })
                    }
                }
            }
        }
    }

    private fun parse(response: JsonObject): Reply {
        val message = response["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: return Reply(text = null)

        val text = message["content"]?.jsonPrimitive?.contentOrNullSafe()?.ifBlank { null }
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { el ->
            val fn = el.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            // Small local models frequently emit malformed argument JSON; treat a
            // parse failure as "no arguments" rather than losing the whole turn.
            val raw = fn["arguments"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val args = runCatching {
                Http.json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
            }.getOrElse { JsonObject(emptyMap()) }
            ToolCall(
                id = el.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe() ?: name,
                name = name,
                arguments = args,
            )
        }
        return Reply(text, calls)
    }

    override suspend fun testConnection(): Result<String> =
        ModelCatalog.ids(root, apiKey, friendlyName = displayName).map { ids ->
            when {
                ids.isEmpty() -> "Reachable, but no models are loaded."
                model in ids -> "Connected. Model $model is loaded."
                // Naming what IS loaded beats a bare failure — LM Studio model ids are long.
                else -> "Reachable, but $model is not loaded. Available: " +
                    ids.take(5).joinToString()
            }
        }

    companion object {
        /** LM Studio default port. Ollama uses 11434. */
        const val LM_STUDIO_DEFAULT = "http://localhost:1234"
    }
}
