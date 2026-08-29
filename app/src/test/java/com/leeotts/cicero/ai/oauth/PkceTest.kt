package com.leeotts.cicero.ai.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    /**
     * The worked example from RFC 7636 Appendix B.
     *
     * If this passes, the hash, the encoding and the charset are all right at
     * once - which no amount of eyeballing the code establishes.
     */
    @Test
    fun `challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier length is inside the legal range`() {
        val verifier = Pkce.verifier()
        assertTrue("was ${verifier.length}", verifier.length in 43..128)
    }

    /**
     * Guards against a slip back to standard base64. RFC 7636 restricts the
     * verifier to the unreserved set, and "+", "/" and "=" would all survive a
     * round trip locally while being mangled in a query string.
     */
    @Test
    fun `verifier uses only unreserved characters`() {
        repeat(20) {
            val verifier = Pkce.verifier()
            assertTrue(verifier, verifier.matches(Regex("[A-Za-z0-9\\-._~]+")))
        }
    }

    @Test
    fun `challenge uses only unreserved characters`() {
        assertTrue(Pkce.challenge(Pkce.verifier()).matches(Regex("[A-Za-z0-9\\-._~]+")))
    }

    @Test
    fun `verifiers differ between calls`() {
        assertNotEquals(Pkce.verifier(), Pkce.verifier())
    }
}
