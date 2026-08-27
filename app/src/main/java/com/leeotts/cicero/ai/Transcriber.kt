package com.leeotts.cicero.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Turns recorded speech into text for backends that cannot hear. */
interface Transcriber {
    suspend fun transcribe(audio: Audio): String
}

/**
 * Used with Gemini, which takes the audio directly. Never actually called —
 * the caller checks [Brain.acceptsAudio] first — but it keeps the wiring
 * uniform so there is no null Transcriber to forget about.
 */
object NoOpTranscriber : Transcriber {
    override suspend fun transcribe(audio: Audio): String =
        throw BrainException("This backend takes audio directly and needs no transcription.")
}

/**
 * Whisper over HTTP, using the OpenAI-compatible /v1/audio/transcriptions shape
 * that whisper.cpp server, faster-whisper-server and LocalAI all implement.
 *
 * Runs on the same machine as the local LLM, so nothing leaves the tailnet.
 */
class WhisperTranscriber(
    baseUrl: String,
    private val model: String = DEFAULT_MODEL,
    private val apiKey: String = "",
) : Transcriber {

    private val root = baseUrl.trimEnd('/').removeSuffix("/v1")

    override suspend fun transcribe(audio: Audio): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                audio.wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            // The glasses mic is narrowband and beamformed; naming the language
            // stops Whisper wasting effort on detection and mis-detecting.
            .addFormDataPart("language", "en")
            .build()

        val request = Request.Builder()
            .url("$root/v1/audio/transcriptions")
            .post(body)
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .build()

        val response = try {
            Http.client.newCall(request).execute()
        } catch (e: IOException) {
            throw BrainException("I could not reach the transcription server.", e)
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw BrainException("Transcription failed with error ${it.code}.")
            }
            val parsed = runCatching {
                (Http.json.parseToJsonElement(text) as JsonObject)["text"]
                    ?.jsonPrimitive?.contentOrNullSafe()
            }.getOrNull()

            parsed?.trim()?.takeIf { t -> t.isNotBlank() }
                ?: throw BrainException("I could not make out what you said.")
        }
    }

    companion object {
        const val DEFAULT_MODEL = "whisper-1"
    }
}
