package com.leeotts.cicero.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The probe behind automatic address switching.
 *
 * [LocalEndpoint.resolve] itself needs a Context for the network id, so what is
 * pinned here is the part that decides the answer: the same
 * `ModelCatalog.ids(... Http.probeClient)` call it makes, against a server that
 * does and does not answer.
 */
class LocalEndpointProbeTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun url() = server.url("/").toString().trimEnd('/')

    private suspend fun probe(baseUrl: String): Boolean =
        ModelCatalog.ids(baseUrl = baseUrl, httpClient = Http.probeClient).isSuccess

    @Test
    fun `a server answering the models call is reachable`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"llama"}]}"""),
        )
        assertTrue(probe(url()))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    /**
     * The case that matters off the home network: nothing is listening on that
     * address at all. It has to come back false rather than throwing, because
     * a failed probe is an answer.
     */
    @Test
    fun `a dead address is not reachable`() = runBlocking {
        val dead = url()
        server.shutdown()
        assertFalse(probe(dead))
    }

    /**
     * Something is listening, but it is not a model server - a router's admin
     * page on a recycled LAN IP, say. Reachable must mean "serving models", or
     * the fallback never fires and every question fails instead.
     */
    @Test
    fun `a port that answers with the wrong thing is not reachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        assertFalse(probe(url()))
    }

    @Test
    fun `the probe client fails fast rather than waiting out the long timeout`() = runBlocking {
        // The blackhole address is chosen so connect stalls rather than being
        // refused: 10.255.255.1 is routable-looking but unroutable in practice.
        val started = System.currentTimeMillis()
        val reachable = probe("http://10.255.255.1:1234")
        val elapsed = System.currentTimeMillis() - started

        assertFalse(reachable)
        // Http.client would take 15s here. Generous bound so a slow CI box does
        // not fail it, while still catching a probe that used the wrong client.
        assertTrue("probe took ${elapsed}ms, expected the short timeout", elapsed < 8_000)
    }
}
