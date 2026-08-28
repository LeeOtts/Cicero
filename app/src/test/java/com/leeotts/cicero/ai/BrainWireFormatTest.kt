package com.leeotts.cicero.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises each backend against a mock HTTP server, so both directions are
 * covered: the request body we build and the response we parse. No network.
 */
class BrainWireFormatTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/").toString().trimEnd('/')

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun lastRequestJson(): JsonObject =
        Http.json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject

    /** Names of the entries in a Claude/Gemini `tools` array, in order. */
    private fun JsonObject.toolTypes(): List<String> =
        this["tools"]?.jsonArray.orEmpty().map { entry ->
            val o = entry.jsonObject
            o["type"]?.jsonPrimitive?.content
                ?: o["name"]?.jsonPrimitive?.content
                ?: o.keys.first()
        }

    private val weatherTool = ToolSpec(
        name = "get_weather",
        description = "Look up the weather",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    // ---------- OpenAI-compatible (LM Studio, Ollama, ...) ----------

    @Test
    fun openAiCompatibleParsesPlainText() = runBlocking {
        enqueue("""{"choices":[{"message":{"role":"assistant","content":"Hello there"}}]}""")
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "local-model")

        val reply = brain.respond("sys", listOf(Msg.User(text = "hi")), emptyList())

        assertEquals("Hello there", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
    }

    @Test
    fun openAiCompatibleSendsSystemPromptAndModel() = runBlocking {
        enqueue("""{"choices":[{"message":{"content":"ok"}}]}""")
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "my-model")

        brain.respond("BE BRIEF", listOf(Msg.User(text = "hi")), emptyList())

        val body = lastRequestJson()
        assertEquals("my-model", body["model"]?.jsonPrimitive?.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("BE BRIEF", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("hi", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiCompatibleParsesToolCalls() = runBlocking {
        enqueue(
            """{"choices":[{"message":{"tool_calls":[
               {"id":"call_1","type":"function",
                "function":{"name":"get_weather","arguments":"{\"city\":\"Austin\"}"}}]}}]}"""
        )
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "m")

        val reply = brain.respond("sys", listOf(Msg.User(text = "weather?")), listOf(weatherTool))

        assertEquals(1, reply.toolCalls.size)
        assertEquals("get_weather", reply.toolCalls[0].name)
        assertEquals("call_1", reply.toolCalls[0].id)
        assertEquals("Austin", reply.toolCalls[0].arguments["city"]?.jsonPrimitive?.content)
    }

    /** Small local models emit broken argument JSON often; the turn must survive. */
    @Test
    fun openAiCompatibleSurvivesMalformedToolArguments() = runBlocking {
        enqueue(
            """{"choices":[{"message":{"tool_calls":[
               {"id":"c1","function":{"name":"get_weather","arguments":"{not json"}}]}}]}"""
        )
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "m")

        val reply = brain.respond("sys", listOf(Msg.User(text = "x")), listOf(weatherTool))

        assertEquals(1, reply.toolCalls.size)
        assertEquals("get_weather", reply.toolCalls[0].name)
        assertTrue("bad JSON should degrade to empty args", reply.toolCalls[0].arguments.isEmpty())
    }

    /** A model that cannot call tools must not be sent any. */
    @Test
    fun openAiCompatibleOmitsToolsWhenUnsupported() = runBlocking {
        enqueue("""{"choices":[{"message":{"content":"ok"}}]}""")
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "m", supportsTools = false)

        brain.respond("sys", listOf(Msg.User(text = "hi")), listOf(weatherTool))

        assertNull(lastRequestJson()["tools"])
    }

    @Test
    fun openAiCompatibleAcceptsBaseUrlWithOrWithoutV1Suffix() = runBlocking {
        enqueue("""{"choices":[{"message":{"content":"ok"}}]}""")
        val brain = OpenAiCompatibleBrain(baseUrl = url() + "/v1", model = "m")

        brain.respond("sys", listOf(Msg.User(text = "hi")), emptyList())

        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    // ---------- Gemini ----------

    @Test
    fun geminiParsesTextAndFunctionCall() = runBlocking {
        enqueue(
            """{"candidates":[{"content":{"parts":[
               {"text":"Looking now. "},
               {"functionCall":{"name":"look","args":{}}}]}}]}"""
        )
        val brain = GeminiBrain(apiKey = "k", model = "m", baseUrl = url())
        val reply = brain.respond("sys", listOf(Msg.User(text = "what is this?")), emptyList())

        assertEquals("Looking now. ", reply.text)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("look", reply.toolCalls[0].name)
    }

    // ---------- Claude ----------

    @Test
    fun claudeParsesTextAndToolUse() = runBlocking {
        enqueue(
            """{"stop_reason":"tool_use","content":[
               {"type":"text","text":"Let me check."},
               {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Austin"}}]}"""
        )
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())
        val reply = brain.respond("sys", listOf(Msg.User(text = "weather?")), emptyList())

        assertEquals("Let me check.", reply.text)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("toolu_1", reply.toolCalls[0].id)
        assertEquals("Austin", reply.toolCalls[0].arguments["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun claudeSurfacesRefusalAsSpokenError() = runBlocking {
        enqueue("""{"stop_reason":"refusal","content":[]}""")
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        val thrown = runCatching {
            brain.respond("sys", listOf(Msg.User(text = "x")), emptyList())
        }.exceptionOrNull()

        assertTrue(thrown is BrainException)
        assertTrue((thrown as BrainException).spokenMessage.contains("declined"))
    }

    // ---------- web search (server-side tools) ----------

    @Test
    fun claudeDeclaresWebSearchAlongsideClientTools() = runBlocking {
        enqueue("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""")
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        brain.respond("sys", listOf(Msg.User(text = "hi")), listOf(weatherTool))

        val types = lastRequestJson().toolTypes()
        assertTrue("client tool should still be declared", types.contains("get_weather"))
        assertTrue(
            "server-side search should be declared",
            types.contains(ClaudeBrain.WEB_SEARCH_TOOL),
        )
    }

    /** The auto-title and the settings probe carry no tools and must not search. */
    @Test
    fun claudeOmitsWebSearchWhenNoClientTools() = runBlocking {
        enqueue("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""")
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        brain.respond("sys", listOf(Msg.User(text = "hi")), emptyList())

        assertNull(lastRequestJson()["tools"])
    }

    @Test
    fun claudeSendsRefusalFallbacks() = runBlocking {
        enqueue("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""")
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        brain.respond("sys", listOf(Msg.User(text = "hi")), emptyList())

        val request = server.takeRequest()
        assertEquals(
            ClaudeBrain.FALLBACK_BETA,
            request.getHeader("anthropic-beta"),
        )
        val body = Http.json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("default", body["fallbacks"]?.jsonPrimitive?.content)
    }

    /**
     * A search Claude ran server-side must inform the answer without ever
     * reaching the client tool loop - server_tool_use is not a tool call.
     */
    @Test
    fun claudeIgnoresServerSearchBlocksAsToolCalls() = runBlocking {
        enqueue(
            """{"stop_reason":"end_turn","content":[
               {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search",
                "input":{"query":"euro 2024 winner"}},
               {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                {"type":"web_search_result","url":"https://example.com","title":"Result"}]},
               {"type":"text","text":"Spain won."}]}"""
        )
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        val reply = brain.respond("sys", listOf(Msg.User(text = "who won?")), listOf(weatherTool))

        assertEquals("Spain won.", reply.text)
        assertTrue("server tools must not surface as client calls", reply.toolCalls.isEmpty())
    }

    /**
     * On failure the result block's `content` is an OBJECT, not the usual list,
     * and it still arrives as HTTP 200. Indexing it as a list would throw.
     */
    @Test
    fun claudeSurvivesWebSearchErrorBlock() = runBlocking {
        enqueue(
            """{"stop_reason":"end_turn","content":[
               {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1",
                "content":{"type":"web_search_tool_result_error",
                           "error_code":"max_uses_exceeded"}},
               {"type":"text","text":"I could not check that."}]}"""
        )
        val brain = ClaudeBrain(apiKey = "k", model = "m", baseUrl = url())

        val reply = brain.respond("sys", listOf(Msg.User(text = "x")), listOf(weatherTool))

        assertEquals("I could not check that.", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
    }

    @Test
    fun geminiDeclaresGoogleSearchAlongsideFunctionDeclarations() = runBlocking {
        enqueue("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        val brain = GeminiBrain(apiKey = "k", model = "m", baseUrl = url())

        brain.respond("sys", listOf(Msg.User(text = "hi")), listOf(weatherTool))

        val types = lastRequestJson().toolTypes()
        assertTrue("declarations entry expected", types.contains("functionDeclarations"))
        assertTrue("grounding entry expected", types.contains("google_search"))
    }

    @Test
    fun geminiOmitsGoogleSearchWhenNoTools() = runBlocking {
        enqueue("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        val brain = GeminiBrain(apiKey = "k", model = "m", baseUrl = url())

        brain.respond("sys", listOf(Msg.User(text = "hi")), emptyList())

        assertNull(lastRequestJson()["tools"])
    }

    /** No OpenAI-compatible server hosts a search tool, so it must say so. */
    @Test
    fun localBackendReportsNoWebSearch() {
        assertFalse(OpenAiCompatibleBrain(baseUrl = url(), model = "m").supportsWebSearch)
        assertTrue(ClaudeBrain(apiKey = "k", model = "m", baseUrl = url()).supportsWebSearch)
        assertTrue(GeminiBrain(apiKey = "k", model = "m", baseUrl = url()).supportsWebSearch)
    }

    // ---------- error mapping ----------

    @Test
    fun httpErrorsBecomeSpeakableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val brain = OpenAiCompatibleBrain(baseUrl = url(), model = "m")

        val thrown = runCatching {
            brain.respond("sys", listOf(Msg.User(text = "x")), emptyList())
        }.exceptionOrNull()

        assertTrue(thrown is BrainException)
        assertTrue((thrown as BrainException).spokenMessage.contains("API key"))
    }
}
