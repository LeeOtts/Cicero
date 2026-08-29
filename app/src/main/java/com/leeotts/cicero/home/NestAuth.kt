package com.leeotts.cicero.home

import com.leeotts.cicero.TAG
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the pasted refresh token into short-lived access tokens.
 *
 * Access tokens last an hour; the refresh token itself does not expire on a
 * timer once the OAuth consent screen is published to production. Left in
 * *Testing*, Google expires it after seven days - which presents as Cicero
 * losing the thermostat every week, so [NestGateway] says so in words rather
 * than just failing.
 */
class NestAuth(private val tokenUrl: String = GOOGLE_TOKEN_URL) {

    /**
     * One refresh at a time. A tool loop can run five rounds, and without this
     * a just-expired token would send five identical refreshes racing each
     * other, four of which are wasted.
     */
    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    @Volatile
    private var expiresAt = 0L

    /** @param force discard a token the server has just rejected. */
    suspend fun accessToken(config: NestConfig, force: Boolean = false): String = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force) cached?.takeIf { now < expiresAt }?.let { return@withLock it }

        val body = SdmHttp.postForm(
            tokenUrl,
            mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "refresh_token" to config.refreshToken,
                "grant_type" to "refresh_token",
            ),
        )

        val token = body["access_token"]?.jsonPrimitive?.content?.ifBlank { null }
            ?: throw SdmException(0, "Nest didn't give me an access token.")
        // A minute of slack, so a token cannot expire between this check and the
        // call that uses it.
        val lifetime = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        cached = token
        expiresAt = now + (lifetime - 60L).coerceAtLeast(0L) * 1000L
        Log.d(TAG, "nest: refreshed access token, good for ${lifetime}s")
        token
    }

    /** Drops the cached token, for when settings change under us. */
    fun forget() {
        cached = null
        expiresAt = 0L
    }

    companion object {
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}
