package com.leeotts.cicero.ai.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Proof Key for Code Exchange, RFC 7636.
 *
 * The OAuth redirect comes back on a custom URL scheme, which any app on the
 * device is free to claim. PKCE is what makes that safe: an intercepted code is
 * worthless without the verifier, and the verifier never leaves this process.
 * For that reason the challenge method must always be S256 and must never be
 * softened to "plain".
 */
object Pkce {

    /** 32 random bytes, which encode to 43 characters - inside RFC 7636's 43..128. */
    fun verifier(random: SecureRandom = SecureRandom()): String =
        encode(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * java.util.Base64, deliberately, NOT android.util.Base64.
     *
     * This module sets `testOptions.unitTests.isReturnDefaultValues = true`, so
     * the Android one returns null in a JVM unit test and every assertion here
     * would pass for the wrong reason.
     */
    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
