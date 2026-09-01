package com.leeotts.cicero.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * One model a provider will accept, plus what it can do.
 *
 * The capability flags are only ever populated by providers that publish them -
 * OpenRouter does, and it matters, because picking a text-only model there is
 * otherwise indistinguishable from picking a vision one until the camera
 * silently stops working.
 */
data class ModelInfo(
    val id: String,
    val vision: Boolean = false,
    val tools: Boolean = false,
)

/**
 * What Settings knows about the models on one endpoint.
 *
 * Discovery fails for reasons the user has to see - server asleep, wrong port,
 * no tailnet, bad key - so a failure is a state carrying its message, not an
 * empty list that would read as "this server has no models".
 */
sealed interface ModelList {
    data object Idle : ModelList
    data object Loading : ModelList
    data class Loaded(val models: List<ModelInfo>) : ModelList {
        val ids: List<String> get() = models.map { it.id }
    }

    data class Failed(val message: String) : ModelList
}

/**
 * Reads the model list off whichever provider is selected.
 *
 * Model ids are long, unguessable and short-lived ("qwen2.5-7b-instruct-1m@q4_k_m"),
 * and one wrong character comes back as "not loaded" - so they are fetched from
 * the server that owns them rather than typed from memory or pinned in source.
 */
object ModelCatalog {

    /** Discovery for a catalog [Provider], in whichever dialect it speaks. */
    suspend fun list(
        provider: Provider,
        baseUrl: String,
        apiKey: String,
    ): Result<List<ModelInfo>> = runCatching {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        val headers = provider.auth.headers(apiKey) + provider.extraHeaders
        val who = provider.displayName
        when (provider.discovery) {
            Discovery.OPENAI -> parseOpenAi(get("$root/v1/models", headers, who))
            Discovery.OPENROUTER -> parseOpenRouter(get("$root/v1/models", headers, who))
            // Anthropic returns the OpenAI envelope but paginates. One page of
            // 100 is deliberate: nobody scrolls past that in a dropdown.
            Discovery.ANTHROPIC -> parseOpenAi(get("$root/v1/models?limit=100", headers, who))
            // The Gemini base already ends in /v1beta, so no /v1 is inserted.
            Discovery.GEMINI -> parseGemini(get("$root/models?pageSize=200", headers, who))
        }.sortedBy { it.id }
    }

    /**
     * The plain-id shape, kept for reachability checks that only want to know
     * whether one model is present.
     */
    suspend fun ids(
        baseUrl: String,
        apiKey: String = "",
        auth: AuthStyle = AuthStyle.BEARER,
        extraHeaders: Map<String, String> = emptyMap(),
        friendlyName: String = "the local server",
        httpClient: OkHttpClient = Http.client,
    ): Result<List<String>> = runCatching {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        parseOpenAi(
            get("$root/v1/models", auth.headers(apiKey) + extraHeaders, friendlyName, httpClient),
        )
            .map { it.id }
            .sorted()
    }

    private suspend fun get(
        url: String,
        headers: Map<String, String>,
        who: String,
        httpClient: OkHttpClient = Http.client,
    ): JsonObject =
        Http.json.parseToJsonElement(Http.getRaw(url, headers, who, httpClient)) as JsonObject

    /** `{"data":[{"id":"..."}]}` - OpenAI, and everyone who copied it. */
    private fun parseOpenAi(root: JsonObject): List<ModelInfo> =
        root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe() }
            ?.map { ModelInfo(it) }
            .orEmpty()

    /**
     * The OpenAI envelope, plus the capability data OpenRouter publishes per
     * model. Reading it here is what lets the constructed brain tell the truth
     * about vision and tools instead of guessing.
     */
    private fun parseOpenRouter(root: JsonObject): List<ModelInfo> =
        root["data"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            val modalities = o["architecture"]?.jsonObject?.get("input_modalities")
                ?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
            val params = o["supported_parameters"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
            ModelInfo(id = id, vision = "image" in modalities, tools = "tools" in params)
        }.orEmpty()

    /**
     * `{"models":[{"name":"models/gemini-3.7-flash", ...}]}`.
     *
     * The "models/" prefix MUST be stripped: [GeminiBrain] builds
     * "$baseUrl/models/$model:generateContent", so leaving it produces
     * ".../models/models/gemini...:generateContent" and a 404.
     */
    private fun parseGemini(root: JsonObject): List<ModelInfo> =
        root["models"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            // Without this filter the picker offers embedding models, which fail
            // with a confusing error only once a question has been asked.
            val methods = o["supportedGenerationMethods"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
            if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
            ModelInfo(id = name.removePrefix("models/"), vision = true, tools = true)
        }.orEmpty()
}
