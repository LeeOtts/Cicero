package com.leeotts.cicero.ai.oauth

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.leeotts.cicero.ai.BrainException
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.Http
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Where a finished sign-in lands.
 *
 * `replay = 1` is load-bearing. The app is routinely killed while the Custom Tab
 * is in front, so the callback activity can run *before* the ViewModel that
 * wants the code exists. Without replay the code would be delivered to nobody.
 */
object OAuthResult {
    val codes = MutableSharedFlow<String?>(replay = 1)

    fun deliver(code: String?) {
        codes.tryEmit(code)
    }

    /** Called once a code has been spent, so a later collector does not re-run it. */
    fun clear() {
        codes.resetReplayCache()
    }
}

/**
 * Signing in to OpenRouter, which is the only sanctioned way this app can offer
 * "log in with your account" at all.
 *
 * Consumer subscriptions elsewhere cannot authorise a third party: Anthropic
 * forbids using Free/Pro/Max OAuth tokens outside Claude Code and claude.ai,
 * Google closed the same door on Gemini CLI, and the OpenAI equivalent is
 * unsanctioned and liable to go the same way. OpenRouter instead mints a real,
 * revocable API key scoped to the user's own account.
 */
class OpenRouterAuth(private val settings: BrainSettings) {

    /** Sends the user to OpenRouter. The answer arrives via [OAuthResult]. */
    suspend fun begin(activity: Activity) {
        val verifier = Pkce.verifier()
        settings.putPendingAuth(verifier)
        val url = "$AUTH_URL?callback_url=$REDIRECT_URI" +
            "&code_challenge=${Pkce.challenge(verifier)}" +
            "&code_challenge_method=S256"
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(activity, url.toUri())
        } catch (e: ActivityNotFoundException) {
            settings.clearPendingAuth()
            throw BrainException("There is no browser to sign in with. Paste a key instead.", e)
        }
    }

    /**
     * Trades the code for a real key.
     *
     * No Authorization header: the code plus the verifier *is* the proof, which
     * is the whole point of PKCE.
     */
    suspend fun complete(code: String): String {
        val verifier = settings.takePendingAuth()
            ?: throw BrainException("That sign-in took too long. Start it again.")
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }
        val key = Http.postJson(url = KEYS_URL, body = body, friendlyName = "OpenRouter")["key"]
            ?.jsonPrimitive?.content?.trim().orEmpty()
        if (key.isBlank()) throw BrainException("OpenRouter did not send a key back.")
        return key
    }

    companion object {
        /**
         * Reverse-DNS per RFC 8252 section 7.1, and deliberately NOT "cicero://".
         * That scheme has a scheme-only filter on MainActivity which the Meta AI
         * app uses to return after DAT registration; it matches every cicero URI,
         * so reusing it here would raise an app chooser mid-sign-in.
         */
        const val REDIRECT_URI = "com.leeotts.cicero://oauth/openrouter"
        private const val AUTH_URL = "https://openrouter.ai/auth"
        private const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
    }
}
