package com.leeotts.cicero.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP + JSON plumbing for every brain.
 *
 * One uniform client rather than three provider SDKs, so the pluggable design
 * stays genuinely uniform and adds no per-provider dependency weight.
 */
object Http {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Generous timeouts: a local model on a laptop can take a long time to
     * produce a first token, and a cold LM Studio load is slower still.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** POSTs [body] and parses the response as a JSON object. */
    suspend fun postJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap(),
        friendlyName: String,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonMedia))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw BrainException("I couldn't reach $friendlyName.", e)
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw BrainException(explain(it.code, friendlyName, text))
            try {
                json.parseToJsonElement(text) as JsonObject
            } catch (e: Exception) {
                throw BrainException("$friendlyName sent a response I couldn't read.", e)
            }
        }
    }

    /** GET returning the raw body, used for reachability checks. */
    suspend fun getRaw(
        url: String,
        headers: Map<String, String> = emptyMap(),
        friendlyName: String,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw BrainException("I couldn't reach $friendlyName.", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw BrainException(explain(it.code, friendlyName, text))
            text
        }
    }

    /** Turns an HTTP status into something worth hearing out loud. */
    private fun explain(code: Int, who: String, body: String): String = when (code) {
        401, 403 -> "$who rejected my API key."
        404 -> "$who couldn't find that model or endpoint."
        429 -> "$who is rate limiting me. Try again in a moment."
        in 500..599 -> "$who had a server error."
        else -> "$who returned error $code." + body.take(200).let { if (it.isBlank()) "" else " $it" }
    }
}
