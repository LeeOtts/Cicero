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
 * Google Gemini. The only backend that accepts raw audio, so it needs no
 * transcriber — the recording and the photo go up in one multimodal request.
 */
class GeminiBrain(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** Overridable so tests can point at a local mock server. */
    private val baseUrl: String = BASE,
) : Brain {

    override val id = "gemini"
    override val displayName = "Gemini ($model)"
    override val acceptsAudio = true
    override val supportsVision = true
    override val supportsTools = true
    override val supportsWebSearch = true

    override suspend fun respond(
        system: String,
        history: List<Msg>,
        tools: List<ToolSpec>,
    ): Reply {
        if (apiKey.isBlank()) throw BrainException("No Gemini API key is set.")

        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            put("contents", buildContents(history))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { t ->
                                add(buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", t.parameters)
                                })
                            }
                        }
                    })
                    // Grounding, as a second entry alongside the declarations.
                    // Gemini 3 models allow a built-in tool and function calling
                    // in one request; older ones did not, and older ones also
                    // spell this google_search_retrieval. Added only when the
                    // caller brought tools, so the auto-title never searches.
                    add(buildJsonObject { putJsonObject("google_search") { } })
                }
            }
        }

        val response = Http.postJson(
            url = "$baseUrl/models/$model:generateContent",
            body = body,
            headers = mapOf("x-goog-api-key" to apiKey),
            friendlyName = "Gemini",
        )
        return parse(response)
    }

    private fun buildContents(history: List<Msg>) = buildJsonArray {
        history.forEach { msg ->
            when (msg) {
                is Msg.User -> add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        msg.text?.let { add(buildJsonObject { put("text", it) }) }
                        msg.audio?.let { add(inlineData("audio/wav", it.wav)) }
                        msg.image?.let { add(inlineData(it.mimeType, it.bytes)) }
                    }
                })

                is Msg.Assistant -> add(buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        msg.text?.let { add(buildJsonObject { put("text", it) }) }
                        msg.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", call.name)
                                    put("args", call.arguments)
                                }
                            })
                        }
                    }
                })

                is Msg.ToolResult -> {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", msg.name)
                                    putJsonObject("response") { put("result", msg.content) }
                                }
                            })
                        }
                    })
                    // Images ride as a separate user part; functionResponse is JSON-only.
                    msg.image?.let { img ->
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(inlineData(img.mimeType, img.bytes)) }
                        })
                    }
                }
            }
        }
    }

    private fun inlineData(mime: String, bytes: ByteArray) = buildJsonObject {
        putJsonObject("inlineData") {
            put("mimeType", mime)
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun parse(response: JsonObject): Reply {
        val parts = response["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: return Reply(text = null)

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        parts.forEachIndexed { i, part ->
            val o = part.jsonObject
            o["text"]?.jsonPrimitive?.contentOrNullSafe()?.let { text.append(it) }
            o["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.content ?: return@let
                calls += ToolCall(
                    // Gemini doesn't issue call ids; synthesise a stable one.
                    id = "$name-$i",
                    name = name,
                    arguments = fc["args"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return Reply(text.toString().ifBlank { null }, calls)
    }

    override suspend fun testConnection(): Result<String> = runCatching {
        if (apiKey.isBlank()) throw BrainException("No Gemini API key is set.")
        Http.getRaw(
            url = "$baseUrl/models/$model",
            headers = mapOf("x-goog-api-key" to apiKey),
            friendlyName = "Gemini",
        )
        "Connected to $model"
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.7-flash"
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/** `jsonPrimitive.content` throws on JsonNull; this returns null instead. */
internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
