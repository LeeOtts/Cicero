package com.leeotts.cicero.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The half of speaking that fails without making a sound.
 *
 * Cicero spent months reading every answer into a muted stream. Nothing threw,
 * nothing logged anything anyone read, and the engine was in perfect health the
 * whole time - TextToSpeech reports refusal by RETURNING -1, and the code
 * wrapped that in a runCatching which had nothing to catch. So what is pinned
 * down here is the return codes, the volume, and who gets told: every failure
 * in this file is one the user can only find out about by being shown.
 */
class SpeakerTest {

    private class FakeSynthesizer(private val readyOnStart: Int? = null) : Synthesizer {

        var listener: Synthesizer.Listener? = null
        val spoken = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val languages = mutableListOf<Locale>()
        var stopped = false
        var shutdown = false

        /** What speak() hands back. The value nothing used to look at. */
        var speakResult = SYNTH_SUCCESS

        /** Per-locale, so a ladder can be walked rung by rung. */
        var languageResults: (Locale) -> Int = { 0 }

        override fun start(listener: Synthesizer.Listener) {
            this.listener = listener
            // Some engines report themselves from inside their own constructor.
            readyOnStart?.let { listener.onReady(it) }
        }

        override fun setLanguage(locale: Locale): Int {
            languages += locale
            return languageResults(locale)
        }

        override fun speak(text: String, utteranceId: String): Int {
            spoken += text
            ids += utteranceId
            return speakResult
        }

        override fun stop() {
            stopped = true
        }

        override fun shutdown() {
            shutdown = true
        }

        fun ready(status: Int = SYNTH_SUCCESS) = listener?.onReady(status)

        /** Also how a flushed utterance reports itself. */
        fun done(id: String) = listener?.onDone(id)

        fun failed(id: String, code: Int = SYNTH_ERROR) = listener?.onError(id, code)
    }

    private class FakeSynthesizers(readyOnStart: Int? = null) : Synthesizers {
        val created = mutableListOf<FakeSynthesizer>()
        private val readyOnStart = readyOnStart
        override fun create(): Synthesizer =
            FakeSynthesizer(readyOnStart).also { created += it }
    }

    private class FakeFocus(var granted: Boolean = true) : AudioFocus {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean {
            requests++
            return granted
        }

        override fun abandon() {
            abandons++
        }
    }

    private class FakeVolume(var level: Int? = 7) : AudioVolume {
        override fun level(): Int? = level
    }

    /**
     * Pinned, because the locale ladder is walked against whatever the machine
     * running the tests happens to be set to. en-GB gives three distinct rungs.
     */
    private val realDefault = Locale.getDefault()

    @Before
    fun fixLocale() = Locale.setDefault(Locale.UK)

    @After
    fun restoreLocale() = Locale.setDefault(realDefault)

    private fun speaker(
        synths: FakeSynthesizers = FakeSynthesizers(),
        focus: FakeFocus = FakeFocus(),
        volume: FakeVolume = FakeVolume(),
    ) = Speaker(synths, focus, volume)

    // --- the bug this was all for -------------------------------------------

    @Test
    fun `a muted assistant stream is reported on screen, because it cannot be reported out loud`() {
        val synths = FakeSynthesizers()
        val volume = FakeVolume(level = 0)
        val speaker = speaker(synths = synths, volume = volume)
        synths.created.single().ready()

        speaker.speak("Twelve degrees and raining.")

        val problem = speaker.problem.value
        assertNotNull("a silence nobody is told about is the whole bug", problem)
        assertTrue(
            "the message has to say which volume, or it sends the user to the wrong slider",
            problem!!.contains("assistant volume"),
        )
    }

    @Test
    fun `a muted assistant stream still reaches the engine, in case it is unmuted mid-answer`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths, volume = FakeVolume(level = 0))
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("Twelve degrees and raining.")

        assertEquals(listOf("Twelve degrees and raining."), synth.spoken)
    }

    @Test
    fun `a volume that cannot be read is not treated as a mute`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths, volume = FakeVolume(level = null))
        synths.created.single().ready()

        speaker.speak("Twelve degrees.")

        assertNull("unknown is not zero, and guessing would cry wolf", speaker.problem.value)
    }

    // --- the return code nothing used to check ------------------------------

    @Test
    fun `an engine that refuses the words does not leave the stop button showing`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()
        synth.speakResult = SYNTH_ERROR

        speaker.speak("Twelve degrees.")

        assertFalse("refusal is a return code, not an exception", speaker.speaking.value)
        assertNotNull(speaker.problem.value)
        assertEquals("and the ducking has to come back up too", 1, focus.abandons)
    }

    @Test
    fun `speaking is true before the first word plays, not after`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        synths.created.single().ready()

        speaker.speak("Twelve degrees.")

        // onStart lands a few hundred ms later. The wake-word gate reads this
        // flag, and a gate that shuts after the first word is not a gate.
        assertTrue(speaker.speaking.value)
    }

    @Test
    fun `an utterance flushed by the next one does not mark Cicero silent`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("The first answer.")
        speaker.speak("The second answer.")
        // The flushed utterance reports itself done AFTER its replacement
        // started. Without the id guard this is where Cicero goes quiet while
        // still talking - and the wake word starts listening to it.
        synth.done(synth.ids.first())

        assertTrue(speaker.speaking.value)

        synth.done(synth.ids.last())
        assertFalse(speaker.speaking.value)
    }

    @Test
    fun `an error part-way through says so and gives up the flag`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        synth.ready()
        speaker.speak("Twelve degrees.")

        synth.failed(synth.ids.single(), code = -5)

        assertFalse(speaker.speaking.value)
        assertTrue(speaker.problem.value!!.contains("-5"))
    }

    // --- startup ------------------------------------------------------------

    @Test
    fun `a listener that fires from inside the constructor does not find a half-built speaker`() {
        // The platform is allowed to report itself ready before the object
        // wrapping it has finished being built.
        val synths = FakeSynthesizers(readyOnStart = SYNTH_SUCCESS)
        val speaker = speaker(synths = synths)

        speaker.speak("Twelve degrees.")

        assertEquals(listOf("Twelve degrees."), synths.created.single().spoken)
    }

    @Test
    fun `an answer asked for before the engine is ready is spoken once it arrives`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()

        // The engine takes a moment to bind, and the first answer of a session
        // very often arrives inside that window.
        speaker.speak("Twelve degrees.")
        assertEquals(emptyList<String>(), synth.spoken)

        synth.ready()
        assertEquals(listOf("Twelve degrees."), synth.spoken)
    }

    @Test
    fun `an engine that never starts says so rather than holding the answer forever`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        speaker.speak("Twelve degrees.")

        synth.ready(status = SYNTH_ERROR)

        assertEquals(emptyList<String>(), synth.spoken)
        assertNotNull("otherwise this is exactly the silence we started with", speaker.problem.value)
    }

    @Test
    fun `a cue is not held for an engine that has not bound yet`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()

        speaker.speakCue("Having a look.")
        synth.ready()

        // By the time the engine binds, the thing it announced has happened.
        assertEquals(emptyList<String>(), synth.spoken)
    }

    @Test
    fun `a cue that is refused does not put anything on screen`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        synth.ready()
        synth.speakResult = SYNTH_ERROR

        speaker.speakCue("Having a look.")

        assertNull("a missing cue is a disappointment, not a fault", speaker.problem.value)
    }

    // --- language -----------------------------------------------------------

    @Test
    fun `a locale the engine has no data for falls back rather than going silent`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        // LANG_MISSING_DATA for en-GB, but plain English is installed. The old
        // code threw this result away and spoke into the gap.
        synth.languageResults = { if (it == Locale.UK) LANGUAGE_MISSING_DATA else 0 }

        synth.ready()
        speaker.speak("Twelve degrees.")

        assertEquals(listOf(Locale.UK, Locale.forLanguageTag("en")), synth.languages)
        assertEquals(listOf("Twelve degrees."), synth.spoken)
        assertNull(speaker.problem.value)
    }

    @Test
    fun `an engine with no voice at all still speaks, in whatever voice it has`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        synth.languageResults = { LANGUAGE_NOT_SUPPORTED }

        synth.ready()
        assertNotNull("the wrong accent is worth explaining", speaker.problem.value)

        speaker.speak("Twelve degrees.")
        assertEquals(
            "an assistant with the wrong accent beats one with no voice",
            listOf("Twelve degrees."),
            synth.spoken,
        )
    }

    @Test
    fun `the language is settled once, not before every answer`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("One.")
        speaker.speak("Two.")
        speaker.speak("Three.")

        // It used to be a binder round trip before every single answer, with
        // the result discarded each time.
        assertEquals(listOf(Locale.UK), synth.languages)
    }

    // --- focus --------------------------------------------------------------

    @Test
    fun `focus is taken before the words and given back when they finish`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("Twelve degrees.")
        assertEquals(1, focus.requests)
        assertEquals(0, focus.abandons)

        synth.done(synth.ids.single())
        assertEquals("music has to come back up", 1, focus.abandons)
    }

    @Test
    fun `focus refused does not silence the answer`() {
        val synths = FakeSynthesizers()
        val speaker = speaker(synths = synths, focus = FakeFocus(granted = false))
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("Twelve degrees.")

        // Going quiet for a reason the user cannot see is the failure being
        // fixed here, not a policy to adopt somewhere else.
        assertEquals(listOf("Twelve degrees."), synth.spoken)
    }

    @Test
    fun `focus refused is not held, so it is never abandoned on someone else's behalf`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus(granted = false)
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("Twelve degrees.")
        synth.done(synth.ids.single())

        assertEquals(0, focus.abandons)
    }

    @Test
    fun `two answers back to back take focus once and give it back once`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("The first answer.")
        speaker.speak("The second answer.")
        synth.done(synth.ids.first())
        synth.done(synth.ids.last())

        assertEquals("no thrashing the stream open and shut", 1, focus.requests)
        assertEquals(1, focus.abandons)
    }

    // --- ending -------------------------------------------------------------

    @Test
    fun `stopping clears the flag and releases focus, though onStop never fires for an utterance that never started`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()
        speaker.speak("A very long answer.")

        speaker.stop()

        assertTrue(synth.stopped)
        assertFalse(speaker.speaking.value)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a stopped utterance reporting itself late does not release focus twice`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()
        speaker.speak("A very long answer.")
        speaker.stop()

        synth.done(synth.ids.single())

        assertEquals(1, focus.abandons)
    }

    @Test
    fun `shutting down releases focus, so a cleared view model does not leave music ducked`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()
        speaker.speak("An answer interrupted by the screen closing.")

        speaker.shutdown()

        assertTrue(synth.shutdown)
        assertFalse(speaker.speaking.value)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `blank text is not spoken and does not take focus`() {
        val synths = FakeSynthesizers()
        val focus = FakeFocus()
        val speaker = speaker(synths = synths, focus = focus)
        val synth = synths.created.single()
        synth.ready()

        speaker.speak("   ")

        assertEquals(emptyList<String>(), synth.spoken)
        assertEquals(0, focus.requests)
        assertFalse(speaker.speaking.value)
    }
}
