package com.leeotts.cicero.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture lifecycle, driven through a fake engine.
 *
 * This is the half that can be wrong without a microphone: which callbacks are
 * allowed to land, and when the button goes back to idle. The recognizer itself
 * is Android's and is not under test here.
 */
class SpeechRecognizerHelperTest {

    private class FakeEngine : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var stopped = false
        var destroyed = false

        override fun start(listener: SpeechEngine.Listener) {
            this.listener = listener
        }

        override fun stop() {
            stopped = true
        }

        /** Only one engine may hold the recognition service at a time. */
        val live: Boolean get() = !destroyed

        override fun destroy() {
            destroyed = true
        }

        /** Stands in for a partial or final result arriving from the recognizer. */
        fun say(text: String) = listener?.onTranscript(text)

        fun finish() = listener?.onFinished()
    }

    private class FakeEngines(override var available: Boolean = true) : SpeechEngines {
        val created = mutableListOf<FakeEngine>()
        override fun create(): SpeechEngine = FakeEngine().also { created += it }
    }

    private fun helper(engines: FakeEngines) = SpeechRecognizerHelper(engines)

    @Test
    fun `words arrive as they are spoken, not only at the end`() {
        val engines = FakeEngines()
        val helper = helper(engines)
        val heard = mutableListOf<String>()

        helper.start { heard += it }
        val engine = engines.created.single()
        engine.say("what")
        engine.say("what am")
        engine.say("what am I looking at")

        assertEquals(listOf("what", "what am", "what am I looking at"), heard)
    }

    @Test
    fun `listening is true while capturing and false once finished`() {
        val engines = FakeEngines()
        val helper = helper(engines)

        assertFalse(helper.listening.value)
        helper.start {}
        assertTrue(helper.listening.value)

        engines.created.single().finish()
        assertFalse(helper.listening.value)
    }

    @Test
    fun `a second start while listening is ignored`() {
        val engines = FakeEngines()
        val helper = helper(engines)

        helper.start {}
        helper.start {}

        assertEquals(1, engines.created.size)
    }

    @Test
    fun `nothing starts when no recognizer is available`() {
        val engines = FakeEngines(available = false)
        val helper = helper(engines)

        helper.start {}

        assertTrue(engines.created.isEmpty())
        assertFalse(helper.listening.value)
    }

    @Test
    fun `stopping keeps what was already transcribed`() {
        val engines = FakeEngines()
        val helper = helper(engines)
        var text: String? = null

        helper.start { text = it }
        engines.created.single().say("set a timer for ten minutes")
        helper.cancel()

        assertEquals("set a timer for ten minutes", text)
        assertFalse(helper.listening.value)
    }

    /**
     * The user owns the field the moment they tap stop. A late result landing
     * on top of an edit is the reason stopping releases the engine outright.
     */
    @Test
    fun `a stopped capture cannot overwrite an edit made afterwards`() {
        val engines = FakeEngines()
        val helper = helper(engines)
        var text: String? = null

        helper.start { text = it }
        val engine = engines.created.single()
        engine.say("remind me to call bob")
        helper.cancel()

        text = "remind me to call Rob"
        engine.say("remind me to call bob")

        assertEquals("remind me to call Rob", text)
        assertTrue(engine.destroyed)
    }

    @Test
    fun `stopping frees the recognizer so the next tap can start one`() {
        val engines = FakeEngines()
        val helper = helper(engines)

        helper.start {}
        helper.cancel()
        helper.start {}

        assertEquals(2, engines.created.size)
        assertTrue(engines.created[0].destroyed)
        assertTrue(helper.listening.value)
    }

    /**
     * The platform arbitrates one recognition session at a time and answers
     * the loser with ERROR_RECOGNIZER_BUSY, so a second live engine is the one
     * thing that must never happen.
     */
    @Test
    fun `only one engine is ever live at a time`() {
        val engines = FakeEngines()
        val helper = helper(engines)

        helper.start {}
        helper.cancel()
        helper.start {}
        helper.cancel()
        helper.start {}

        assertEquals(1, engines.created.count { it.live })
    }

    /**
     * The regression this suite exists for: stop, start again, and the first
     * engine's late result must not overwrite the second capture's words.
     */
    @Test
    fun `a superseded capture cannot overwrite the live one's text`() {
        val engines = FakeEngines()
        val helper = helper(engines)
        var text: String? = null

        helper.start { text = it }
        val first = engines.created[0]
        helper.cancel()

        helper.start { text = it }
        val second = engines.created[1]
        second.say("what's the weather")

        // The abandoned engine reports in late, as a real one does.
        first.say("stale words")

        assertEquals("what's the weather", text)
    }

    /** The same race, on the listening flag rather than the text. */
    @Test
    fun `a superseded capture finishing does not stop the live one`() {
        val engines = FakeEngines()
        val helper = helper(engines)

        helper.start {}
        val first = engines.created[0]
        helper.cancel()

        helper.start {}
        assertTrue(helper.listening.value)

        first.finish()

        assertTrue("the new capture is still listening", helper.listening.value)
        assertTrue("the abandoned engine is released", first.destroyed)
        assertFalse(engines.created[1].destroyed)
    }

    @Test
    fun `destroy releases the engine and silences late callbacks`() {
        val engines = FakeEngines()
        val helper = helper(engines)
        var text: String? = null

        helper.start { text = it }
        val engine = engines.created.single()
        helper.destroy()

        engine.say("too late")

        assertTrue(engine.destroyed)
        assertFalse(helper.listening.value)
        assertNull(text)
    }

    @Test
    fun `an engine that throws on start does not leave the button stuck listening`() {
        val engines = object : SpeechEngines {
            override val available = true
            override fun create(): SpeechEngine = object : SpeechEngine {
                override fun start(listener: SpeechEngine.Listener) = error("no recognizer")
                override fun stop() = Unit
                override fun destroy() = Unit
            }
        }
        val helper = SpeechRecognizerHelper(engines)

        helper.start {}

        assertFalse(helper.listening.value)
    }
}
