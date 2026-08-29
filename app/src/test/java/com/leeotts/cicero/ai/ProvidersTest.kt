package com.leeotts.cicero.ai

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The catalog is data, so these are the tests that keep it honest: every entry
 * has to be complete, unique, and actually buildable into a [Brain].
 */
class ProvidersTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/").toString().trimEnd('/')

    /**
     * These three strings are written into Conversation.brainId and Turn.brainId
     * and predate the catalog. Renaming one to something tidier would orphan
     * every history row the user already has, and the threading rule would
     * mis-fire on the first turn after the upgrade.
     */
    @Test
    fun `legacy brain ids are pinned`() {
        assertEquals("gemini", Providers.GEMINI.id)
        assertEquals("claude", Providers.CLAUDE.id)
        assertEquals("openai-compatible", Providers.LOCAL.id)
    }

    @Test
    fun `provider ids are unique`() {
        val ids = Providers.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every provider is fully specified`() {
        Providers.all.forEach { p ->
            assertTrue("${p.id} has no display name", p.displayName.isNotBlank())
            assertTrue("${p.id} signup url", p.signupUrl.startsWith("https://"))
            // Only the self-hosted entry may ship without an address or a model,
            // because the user supplies both.
            if (!p.userEditableUrl) {
                assertTrue("${p.id} base url", p.baseUrl.startsWith("https://"))
                assertTrue("${p.id} default model", p.defaultModel.isNotBlank())
            }
        }
    }

    /**
     * Base urls are stored as everything *before* /v1, which
     * OpenAiCompatibleBrain appends. A stray suffix here would produce
     * ".../v1/v1/chat/completions" and a 404 that only shows up on device.
     */
    @Test
    fun `openai-wire base urls do not carry a version suffix`() {
        Providers.all.filter { it.wire == Wire.OPENAI }.forEach { p ->
            assertTrue("${p.id} ends with /v1", !p.baseUrl.endsWith("/v1"))
        }
    }

    @Test
    fun `every provider builds a brain that reports its own id`() {
        val config = BrainConfig()
        Providers.all.forEach { p ->
            val brain = BrainFactory.brain(config, Target(p.id, "some-model"))
            assertEquals(p.id, brain.id)
            assertTrue("${p.id} display name", brain.displayName.isNotBlank())
        }
    }

    @Test
    fun `an unknown provider id falls back rather than throwing`() {
        assertEquals(Providers.DEFAULT.id, Providers.byId("something-retired").id)
        assertEquals(Providers.DEFAULT.id, Providers.byId(null).id)
    }

    // ---------- auth styles ----------

    @Test
    fun `bearer is the default and carries the key`() {
        assertEquals(
            mapOf("Authorization" to "Bearer abc"),
            AuthStyle.BEARER.headers("abc"),
        )
    }

    /** Anthropic rejects a request that omits the version header. */
    @Test
    fun `the anthropic style sends the version alongside the key`() {
        val headers = AuthStyle.X_API_KEY.headers("abc")
        assertEquals("abc", headers["x-api-key"])
        assertEquals(ClaudeBrain.API_VERSION, headers["anthropic-version"])
    }

    @Test
    fun `a blank key sends no auth header at all`() {
        AuthStyle.entries.forEach { style ->
            assertTrue(style.name, style.headers("").isEmpty())
        }
    }

    // ---------- what actually goes over the wire ----------

    @Test
    fun `an openai-wire provider sends bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(CHAT_REPLY))
        OpenAiCompatibleBrain(url(), "m", apiKey = "sk-test").respond("s", emptyList(), emptyList())
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a provider can override the auth style and add its own headers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(CHAT_REPLY))
        OpenAiCompatibleBrain(
            baseUrl = url(),
            model = "m",
            apiKey = "sk-test",
            auth = AuthStyle.X_API_KEY,
            extraHeaders = mapOf("X-Title" to "Cicero"),
        ).respond("s", emptyList(), emptyList())

        val request = server.takeRequest()
        assertEquals("sk-test", request.getHeader("x-api-key"))
        assertEquals("Cicero", request.getHeader("X-Title"))
        assertNull(request.getHeader("Authorization"))
    }

    /**
     * OpenRouter runs a search server-side for any model named with ":online",
     * which is the only way an OpenAI-shaped endpoint can honestly claim it.
     * Without this the assistant is told to say it cannot look things up.
     */
    @Test
    fun `the online suffix turns on web search`() {
        val config = BrainConfig()
        val plain = BrainFactory.brain(config, Target(Providers.OPENROUTER.id, "openai/gpt-5.1"))
        val online = BrainFactory.brain(
            config,
            Target(Providers.OPENROUTER.id, "openai/gpt-5.1:online"),
        )
        assertTrue(!plain.supportsWebSearch)
        assertTrue(online.supportsWebSearch)
    }

    private companion object {
        const val CHAT_REPLY =
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
    }
}
