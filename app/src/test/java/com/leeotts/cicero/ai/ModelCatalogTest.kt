package com.leeotts.cicero.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Model discovery, in each of the four dialects the catalog knows about. */
class ModelCatalogTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/").toString().trimEnd('/')

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun `the openai shape reads ids out of data`() = runBlocking {
        enqueue("""{"data":[{"id":"gpt-b"},{"id":"gpt-a"}]}""")
        val models = ModelCatalog.list(Providers.OPENAI, url(), "k").getOrThrow()
        assertEquals(listOf("gpt-a", "gpt-b"), models.map { it.id })
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `the anthropic shape sends the version header`() = runBlocking {
        enqueue("""{"data":[{"id":"claude-opus-5"}]}""")
        val models = ModelCatalog.list(Providers.CLAUDE, url(), "k").getOrThrow()
        assertEquals(listOf("claude-opus-5"), models.map { it.id })

        val request = server.takeRequest()
        assertEquals("k", request.getHeader("x-api-key"))
        assertEquals(ClaudeBrain.API_VERSION, request.getHeader("anthropic-version"))
    }

    /**
     * GeminiBrain builds "$baseUrl/models/$model:generateContent", so a model id
     * that still carries the prefix produces ".../models/models/gemini..." and a
     * 404 that only appears once a question has been asked.
     */
    @Test
    fun `the gemini shape strips the models prefix`() = runBlocking {
        enqueue(
            """{"models":[{"name":"models/gemini-3.7-flash",
               "supportedGenerationMethods":["generateContent"]}]}"""
        )
        val models = ModelCatalog.list(Providers.GEMINI, url(), "k").getOrThrow()
        assertEquals(listOf("gemini-3.7-flash"), models.map { it.id })
        assertEquals("k", server.takeRequest().getHeader("x-goog-api-key"))
    }

    /** Otherwise the picker offers embedding models, which fail confusingly. */
    @Test
    fun `the gemini shape drops models that cannot generate`() = runBlocking {
        enqueue(
            """{"models":[
               {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
               {"name":"models/gemini-3.7-flash","supportedGenerationMethods":["generateContent"]}]}"""
        )
        val models = ModelCatalog.list(Providers.GEMINI, url(), "k").getOrThrow()
        assertEquals(listOf("gemini-3.7-flash"), models.map { it.id })
    }

    /**
     * OpenRouter publishes per-model capabilities, and reading them is what lets
     * the constructed brain tell the truth about vision and tools rather than
     * guess - which is how "set an alarm" silently does nothing.
     */
    @Test
    fun `the openrouter shape reads vision and tool support`() = runBlocking {
        enqueue(
            """{"data":[
               {"id":"vendor/sees","architecture":{"input_modalities":["text","image"]},
                "supported_parameters":["tools","temperature"]},
               {"id":"vendor/blind","architecture":{"input_modalities":["text"]},
                "supported_parameters":["temperature"]}]}"""
        )
        val models = ModelCatalog.list(Providers.OPENROUTER, url(), "k").getOrThrow()
            .associateBy { it.id }

        assertTrue(models.getValue("vendor/sees").vision)
        assertTrue(models.getValue("vendor/sees").tools)
        assertTrue(!models.getValue("vendor/blind").vision)
        assertTrue(!models.getValue("vendor/blind").tools)
    }

    @Test
    fun `a missing capability block does not lose the model`() = runBlocking {
        enqueue("""{"data":[{"id":"vendor/plain"}]}""")
        val models = ModelCatalog.list(Providers.OPENROUTER, url(), "").getOrThrow()
        assertEquals(listOf("vendor/plain"), models.map { it.id })
    }

    /**
     * A failure has to stay a failure. An empty list would read in the picker as
     * "this server has no models", which is a different problem with a different
     * fix.
     */
    @Test
    fun `a server error fails rather than returning nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        val result = ModelCatalog.list(Providers.OPENAI, url(), "k")
        assertTrue(result.isFailure)
    }

    @Test
    fun `a base url with a version suffix is not doubled`() = runBlocking {
        enqueue("""{"data":[{"id":"m"}]}""")
        ModelCatalog.ids("${url()}/v1").getOrThrow()
        assertEquals("/v1/models", server.takeRequest().path)
    }
}
