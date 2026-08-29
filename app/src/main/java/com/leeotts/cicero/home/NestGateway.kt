package com.leeotts.cicero.home

import com.leeotts.cicero.TAG
import android.util.Log
import com.leeotts.cicero.ai.BrainException
import kotlinx.serialization.json.JsonObject

/**
 * Everything Cicero does with a Nest account, with no Android types in sight so
 * a JVM test can drive the whole path against a MockWebServer.
 *
 * [NestController] is the thin Android wrapper that supplies the config.
 */
class NestGateway(
    private val client: SdmClient = SdmClient(),
    private val auth: NestAuth = NestAuth(),
    private val config: suspend () -> NestConfig,
) {

    private var cached: ThermostatState? = null
    private var cachedAt = 0L

    suspend fun isConfigured(): Boolean = config().isConfigured

    /**
     * The account's thermostat, or null if it has none.
     *
     * Cached hard, because SDM allows a thermostat only five calls a minute and
     * a hundred an hour - shared across every app touching it, not just this
     * one. A five round tool loop that re-read the device each round could
     * exhaust that by itself.
     */
    suspend fun thermostat(force: Boolean = false): ThermostatState? {
        val now = System.currentTimeMillis()
        if (!force) cached?.takeIf { now - cachedAt < CACHE_MS }?.let { return it }

        val cfg = require()
        val state = authed(cfg) { token -> client.thermostat(cfg.projectId, token) }
        cached = state
        cachedAt = now
        return state
    }

    /** Runs one trait command, then drops the cache so the next read is truthful. */
    suspend fun execute(state: ThermostatState, command: String, params: JsonObject) {
        val cfg = require()
        authed(cfg) { token -> client.execute(state.deviceId, command, params, token) }
        Log.d(TAG, "nest: $command")
        cached = null
    }

    /** Backs the Settings "Test connection" button. */
    suspend fun test(): Result<String> = runCatching {
        when (val state = thermostat(force = true)) {
            null -> "connected, but there is no thermostat on that account."
            else -> {
                val reading = state.display(state.ambientC)
                "found the ${state.label}" + (reading?.let { ", currently $it ${state.unit}." } ?: ".")
            }
        }
    }

    private suspend fun require(): NestConfig = config().also {
        if (!it.isConfigured) throw BrainException(NOT_SET_UP)
    }

    /**
     * Runs [block] with a live access token, refreshing once if the server says
     * the one we held is no good. A single retry on purpose: a second 401 means
     * the credential is wrong rather than stale, and looping on it would just
     * spend the rate limit.
     */
    private suspend fun <T> authed(cfg: NestConfig, block: suspend (String) -> T): T = try {
        block(token(cfg))
    } catch (e: SdmException) {
        if (e.code != 401) throw BrainException(e.spoken, e)
        try {
            block(token(cfg, force = true))
        } catch (retry: SdmException) {
            throw BrainException(EXPIRED, retry)
        }
    }

    private suspend fun token(cfg: NestConfig, force: Boolean = false): String = try {
        auth.accessToken(cfg, force)
    } catch (e: SdmException) {
        // invalid_grant comes back as a 400. Nearly always an OAuth consent
        // screen left in Testing, which Google expires after seven days.
        if (e.code == 400) throw BrainException(EXPIRED, e) else throw BrainException(e.spoken, e)
    }

    companion object {
        /** Comfortably inside the hundred-an-hour device budget. */
        const val CACHE_MS = 60_000L

        const val NOT_SET_UP =
            "I'm not connected to your Nest account yet. Set it up in Cicero's settings."

        const val EXPIRED =
            "My Nest connection has expired. Paste a fresh refresh token in Cicero's settings, " +
                "and check the OAuth consent screen is published rather than in testing."
    }
}
