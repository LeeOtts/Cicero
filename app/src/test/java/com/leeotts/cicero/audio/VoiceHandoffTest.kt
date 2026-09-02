package com.leeotts.cicero.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may hold the microphone, and when.
 *
 * The rule this pins down was found on a phone: two "Hey Jarvis" utterances
 * five seconds apart, and the second was detected while the recognizer from
 * the first was still listening. Both were reading the same microphone.
 *
 * The speaker is arbitrated here for the same reason, found the same way: with
 * the gate open while Cicero talked, the service recorded the answer and scored
 * the models against Cicero's own voice.
 */
class VoiceHandoffTest {

    @Test
    fun `the wake word listens while the glasses are on and nothing else wants the microphone`() {
        assertTrue(
            shouldListenForWakeWord(
                glassesConnected = true,
                micHeld = false,
                listenRequested = false,
                speaking = false,
            ),
        )
    }

    @Test
    fun `nothing is listened for without the glasses`() {
        assertFalse(
            shouldListenForWakeWord(
                glassesConnected = false,
                micHeld = false,
                listenRequested = false,
                speaking = false,
            ),
        )
    }

    @Test
    fun `the recognizer holding the microphone stops the wake word`() {
        assertFalse(
            shouldListenForWakeWord(
                glassesConnected = true,
                micHeld = true,
                listenRequested = false,
                speaking = false,
            ),
        )
    }

    @Test
    fun `an unclaimed hand-off already stops the wake word`() {
        // The regression. Between the wake word firing and the Ask screen
        // existing, nobody holds the microphone yet - and if that gap leaves
        // the gate open, the service records straight through the exchange it
        // just handed over.
        assertFalse(
            shouldListenForWakeWord(
                glassesConnected = true,
                micHeld = false,
                listenRequested = true,
                speaking = false,
            ),
        )
    }

    @Test
    fun `a request is taken exactly once`() {
        val voice = VoiceHandoff()
        voice.requestListening()

        assertTrue("first caller should get it", voice.takeListenRequest())
        assertFalse("a recomposition must not start a second capture", voice.takeListenRequest())
    }

    @Test
    fun `there is nothing to take before the wake word fires`() {
        assertFalse(VoiceHandoff().takeListenRequest())
    }

    @Test
    fun `taking the request reopens the gate only once the microphone is released`() {
        val voice = VoiceHandoff()
        voice.requestListening()
        assertFalse(gateFor(voice))

        voice.takeListenRequest()
        voice.holdMicrophone(true)
        assertFalse("still busy: the recognizer has it now", gateFor(voice))

        voice.holdMicrophone(false)
        assertTrue("exchange over, the wake word may listen again", gateFor(voice))
    }

    @Test
    fun `taking the request leaves no gap for the recorder to reopen in`() {
        // The second regression, and the one that made the first look like the
        // user saying nothing. Clearing the request without claiming the
        // microphone left both flags false for as long as it took the
        // recognizer to start - about 80 ms on a phone - and the service used
        // that window to reopen its recorder over the top of it.
        val voice = VoiceHandoff()
        voice.requestListening()

        voice.takeListenRequest()

        assertTrue("the claim must land with the take", voice.micHeld.value)
        assertFalse("the recorder must not reopen mid-hand-off", gateFor(voice))
    }

    @Test
    fun `an abandoned hand-off frees the wake word without claiming the microphone`() {
        val voice = VoiceHandoff()
        voice.requestListening()

        // Nothing claimed it - a dismissed activity, or no recognizer at all.
        voice.abandonListenRequest()

        assertFalse("nobody is holding it, so nothing may say so", voice.micHeld.value)
        assertTrue(gateFor(voice))
    }

    @Test
    fun `abandoning does not disturb a hand-off already taken`() {
        val voice = VoiceHandoff()
        voice.requestListening()
        voice.takeListenRequest()

        // The service's safety net can fire late; it must not free a
        // microphone the recognizer is using.
        voice.abandonListenRequest()

        assertTrue(voice.micHeld.value)
        assertFalse(gateFor(voice))
    }

    @Test
    fun `the wake word does not listen while Cicero is talking`() {
        // The regression. Nothing holds the microphone during an answer - the
        // recognizer finished long before - so every other term says the gate
        // is free, and the service happily records Cicero talking to itself.
        assertFalse(
            shouldListenForWakeWord(
                glassesConnected = true,
                micHeld = false,
                listenRequested = false,
                speaking = true,
            ),
        )
    }

    @Test
    fun `a released speaker reopens the gate`() {
        val voice = VoiceHandoff()
        voice.holdSpeaker(true)
        assertFalse(gateFor(voice))

        voice.holdSpeaker(false)
        assertTrue("the answer is over, the wake word may listen again", gateFor(voice))
    }

    @Test
    fun `holding the speaker twice changes nothing`() {
        val voice = VoiceHandoff()
        voice.holdSpeaker(true)
        voice.holdSpeaker(true)

        assertTrue(voice.speaking.value)
        assertFalse(gateFor(voice))
    }

    @Test
    fun `an answer that arrives mid-hand-off keeps the gate shut on its own`() {
        // Both reasons at once, which is the shape that catches an
        // implementation that only ANDs speaking into an existing branch.
        val voice = VoiceHandoff()
        voice.requestListening()
        voice.takeListenRequest()
        voice.holdSpeaker(true)
        voice.holdMicrophone(false)

        assertFalse("still talking, so still busy", gateFor(voice))
    }

    @Test
    fun `the tail must stay shorter than the hand-off grace period`() {
        // Not arithmetic for its own sake. The service abandons an unclaimed
        // hand-off after the grace period; a tail longer than that would keep
        // the gate shut past the point where a wake word firing as an answer
        // ended had already been given up on.
        assertTrue(
            "SPEECH_TAIL_MS=$SPEECH_TAIL_MS must leave room inside $HANDOFF_GRACE_MS",
            SPEECH_TAIL_MS * 2 < HANDOFF_GRACE_MS,
        )
    }

    private fun gateFor(voice: VoiceHandoff) = shouldListenForWakeWord(
        glassesConnected = true,
        micHeld = voice.micHeld.value,
        listenRequested = voice.pendingListen.value,
        speaking = voice.speaking.value,
    )
}
