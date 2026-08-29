package com.leeotts.cicero.home

import com.leeotts.cicero.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An SDM call that failed, carrying the status so a 401 can be retried once. */
internal class SdmException(val code: Int, val spoken: String) : Exception(spoken)

/**
 * HTTP for the Nest calls.
 *
 * Deliberately not folded into [Http]. That client's 180 second read timeout is
 * right for a model that thinks before its first token and badly wrong for a
 * thermostat read sitting inside a five round tool loop - a hung call there
 * would hold the assistant silent for three minutes. The token endpoint also
 * wants form fields, which Http.postJson cannot express.
 *
 * The connection pool and dispatcher come from Http.client via newBuilder(), so
 * this is one more timeout policy rather than one more client.
 */
internal object SdmHttp {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getJson(url: String, token: String): JsonObject =
        call(Request.Builder().url(url).get().bearer(token).build())

    suspend fun postJson(url: String, body: JsonObject, token: String): JsonObject = call(
        Request.Builder()
            .url(url)
            .post(Http.json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonMedia))
            .bearer(token)
            .build(),
    )

    /** The OAuth token endpoint takes form fields, not JSON. */
    suspend fun postForm(url: String, fields: Map<String, String>): JsonObject = call(
        Request.Builder()
            .url(url)
            .post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
            .build(),
    )

    private fun Request.Builder.bearer(token: String) = addHeader("Authorization", "Bearer $token")

    private suspend fun call(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw SdmException(0, "I couldn't reach your Nest account.")
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw SdmException(it.code, explain(it.code, text))
            // A command that succeeds answers with an empty body, not "{}".
            if (text.isBlank()) return@use JsonObject(emptyMap())
            try {
                Http.json.parseToJsonElement(text) as JsonObject
            } catch (e: Exception) {
                throw SdmException(it.code, "Nest sent a response I couldn't read.")
            }
        }
    }

    /** Phrased to be heard, not read: these reach the user through text to speech. */
    private fun explain(code: Int, body: String): String = when (code) {
        401 -> "My Nest connection has expired."
        403 -> "Nest refused that. Check the thermostat is still shared with the project."
        404 -> "I couldn't find that device in your Nest account."
        429 -> "Nest is rate limiting me. Try again in a minute."
        in 500..599 -> "Nest had a server error."
        else -> "Nest returned error $code." + body.take(120).let { if (it.isBlank()) "" else " $it" }
    }
}
