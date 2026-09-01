package com.leeotts.cicero.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointerTest {

    /** 32 ms at 16 kHz, matching a Porcupine frame. */
    private fun silence() = ShortArray(512)
    private fun speech() = ShortArray(512) { if (it % 2 == 0) 4000 else -4000 }

    private fun feed(endpointer: Endpointer, frame: ShortArray, times: Int) {
        repeat(times) { endpointer.offer(frame) }
    }

    @Test
    fun `silence alone is abandoned rather than sent for transcription`() {
        // A false wake with nothing after it must not cost a network round trip.
        val endpointer = Endpointer()
        feed(endpointer, silence(), 200)
        assertEquals(Endpointer.State.ABANDONED, endpointer.state)
    }

    @Test
    fun `speech then silence ends the utterance`() {
        val endpointer = Endpointer()
        feed(endpointer, speech(), 20)
        assertEquals(Endpointer.State.SPEAKING, endpointer.state)
        feed(endpointer, silence(), 40)
        assertEquals(Endpointer.State.DONE, endpointer.state)
    }

    @Test
    fun `a brief pause mid-sentence does not end it`() {
        val endpointer = Endpointer()
        feed(endpointer, speech(), 10)
        feed(endpointer, silence(), 10) // ~320 ms, under the 900 ms budget
        feed(endpointer, speech(), 10)
        assertEquals(Endpointer.State.SPEAKING, endpointer.state)
    }

    @Test
    fun `the hard cap ends a long utterance rather than recording forever`() {
        val endpointer = Endpointer()
        feed(endpointer, speech(), 1000)
        assertEquals(Endpointer.State.DONE, endpointer.state)
    }

    @Test
    fun `state does not change once finished`() {
        val endpointer = Endpointer()
        feed(endpointer, speech(), 20)
        feed(endpointer, silence(), 40)
        assertEquals(Endpointer.State.DONE, endpointer.state)
        feed(endpointer, speech(), 20)
        assertEquals(Endpointer.State.DONE, endpointer.state)
    }

    @Test
    fun `speech starting late still counts`() {
        val endpointer = Endpointer()
        feed(endpointer, silence(), 40) // ~1.3 s, under the 3 s leading limit
        feed(endpointer, speech(), 10)
        assertEquals(Endpointer.State.SPEAKING, endpointer.state)
    }
}
