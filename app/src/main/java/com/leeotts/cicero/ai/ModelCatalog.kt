package com.leeotts.cicero.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What Settings knows about the models on one endpoint.
 *
 * Discovery fails for reasons the user has to see — server asleep, wrong port,
 * no tailnet — so a failure is a state carrying its message, not an empty list
 * that would read as "this server has no models".
 */
sealed interface ModelList {
    data object Idle : ModelList
    data object Loading : ModelList
    data class Loaded(val ids: List<String>) : ModelList
    data class Failed(val message: String) : ModelList
}

/**
 * Reads /v1/models off an OpenAI-compatible server.
 *
 * Model ids are long and unguessable ("qwen2.5-7b-instruct-1m@q4_k_m"), and one
 * wrong character comes back as "not loaded" — so the ids are fetched from the
 * server that owns them rather than typed from memory.
 */
object ModelCatalog {

    suspend fun ids(
        baseUrl: String,
        apiKey: String = "",
        friendlyName: String = "the local server",
    ): Result<List<String>> = runCatching {
        // Accept a base url with or without a trailing /v1, as the brains do.
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        val raw = Http.getRaw(
            url = "$root/v1/models",
            headers = if (apiKey.isBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer $apiKey"),
            friendlyName = friendlyName,
        )
        (Http.json.parseToJsonElement(raw) as JsonObject)["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNullSafe() }
            .orEmpty()
            .sorted()
    }
}
