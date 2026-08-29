package com.leeotts.cicero.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val routed = BrainConfig(
        providerId = Providers.GEMINI.id,
        routingEnabled = true,
        roles = mapOf(
            // Groq is deliberately the fast one here: it cannot see, which is
            // what the vision-escalation cases below turn on.
            TaskRole.FAST to Target(Providers.GROQ.id, "llama-3.3-70b-versatile"),
            TaskRole.DEEP to Target(Providers.CLAUDE.id, ClaudeBrain.DEFAULT_MODEL),
        ),
    )

    @Test
    fun `short commands are fast`() {
        assertEquals(TaskRole.FAST, Router.roleFor("set a timer for ten minutes", 0))
        assertEquals(TaskRole.FAST, Router.roleFor("what time is it", 0))
        assertEquals(TaskRole.FAST, Router.roleFor("turn the lights off", 0))
    }

    @Test
    fun `reasoning markers are deep`() {
        assertEquals(TaskRole.DEEP, Router.roleFor("why is the sky blue", 0))
        assertEquals(TaskRole.DEEP, Router.roleFor("compare these two options", 0))
        assertEquals(TaskRole.DEEP, Router.roleFor("explain how a diesel engine works", 0))
    }

    @Test
    fun `a long question is deep however it is worded`() {
        val long = List(40) { "word" }.joinToString(" ")
        assertEquals(TaskRole.DEEP, Router.roleFor(long, 0))
    }

    @Test
    fun `a thread that has run a while is deep`() {
        assertEquals(TaskRole.FAST, Router.roleFor("and the other one", 0))
        assertEquals(TaskRole.DEEP, Router.roleFor("and the other one", 8))
    }

    @Test
    fun `blank input does not crash and stays cheap`() {
        assertEquals(TaskRole.FAST, Router.roleFor(null, 0))
        assertEquals(TaskRole.FAST, Router.roleFor("   ", 0))
    }

    @Test
    fun `routing off always yields the user's own choice`() {
        val manual = routed.copy(routingEnabled = false)
        val routing = Router.route(manual, "why is the sky blue", 0)
        assertEquals(Providers.GEMINI.id, routing.target.providerId)
        assertEquals("why is the sky blue", routing.text)
    }

    @Test
    fun `an explicit provider wins and is stripped from the question`() {
        val routing = Router.route(routed, "ask Claude what the weather is", 0)
        assertEquals(Providers.CLAUDE.id, routing.target.providerId)
        assertEquals("what the weather is", routing.text)
    }

    @Test
    fun `a comma after the provider name is not left behind`() {
        val routing = Router.route(routed, "ask OpenAI, what is the capital of Peru", 0)
        assertEquals(Providers.OPENAI.id, routing.target.providerId)
        assertEquals("what is the capital of Peru", routing.text)
    }

    @Test
    fun `a bare provider name with nothing after it is not an override`() {
        val routing = Router.route(routed, "ask Claude", 0)
        assertEquals("ask Claude", routing.text)
    }

    /**
     * The one misroute that would cost a capability rather than a little
     * quality: ToolRegistry drops LookTool entirely for a blind backend, so
     * routing a camera question to one would silently disable the glasses.
     */
    @Test
    fun `a camera question never lands on a blind provider`() {
        val routing = Router.route(routed, "what am I looking at", 0)
        assertTrue(routing.target.provider.vision)
        assertEquals(Providers.GEMINI.id, routing.target.providerId)
    }

    @Test
    fun `a non-camera question is left with the fast provider`() {
        val routing = Router.route(routed, "set a timer for ten minutes", 0)
        assertEquals(Providers.GROQ.id, routing.target.providerId)
    }

    @Test
    fun `camera wording is recognised but ordinary wording is not`() {
        assertTrue(Router.looksVisual("read this label for me"))
        assertTrue(Router.looksVisual("what colour is this"))
        assertTrue(!Router.looksVisual("set a timer"))
        assertTrue(!Router.looksVisual(null))
    }
}
