package com.leeotts.cicero.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBufferTest {

    private fun ramp(from: Int, count: Int) =
        ShortArray(count) { (from + it).toShort() }

    @Test
    fun `a short read yields no frame and is held`() {
        val buffer = FrameBuffer(512)
        assertTrue(buffer.offer(ramp(0, 300)).isEmpty())
        assertEquals(300, buffer.buffered)
    }

    @Test
    fun `a hundred millisecond read yields three frames and carries the rest`() {
        // What the phone source actually delivers: 1600 samples at 16 kHz.
        val buffer = FrameBuffer(512)
        val frames = buffer.offer(ramp(0, 1600))
        assertEquals(3, frames.size)
        assertEquals(1600 - 3 * 512, buffer.buffered)
    }

    @Test
    fun `no sample is dropped or duplicated across a frame boundary`() {
        // The failure this guards against is silent: a detector fed subtly
        // corrupted audio just gets worse, it does not crash.
        val buffer = FrameBuffer(512)
        val collected = mutableListOf<Short>()
        var next = 0
        repeat(7) {
            val chunk = ramp(next, 300)
            next += 300
            buffer.offer(chunk).forEach { frame -> collected += frame.toList() }
        }
        val expected = ramp(0, collected.size)
        assertArrayEquals(expected, collected.toShortArray())
    }

    @Test
    fun `frames are exactly the requested length`() {
        val buffer = FrameBuffer(512)
        buffer.offer(ramp(0, 5000)).forEach { assertEquals(512, it.size) }
    }

    @Test
    fun `count limits how much of the array is consumed`() {
        // AudioRecord.read returns how many samples it filled, which is not
        // necessarily the size of the buffer handed to it.
        val buffer = FrameBuffer(4)
        val frames = buffer.offer(shortArrayOf(1, 2, 3, 4, 9, 9, 9), count = 4)
        assertEquals(1, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames[0])
        assertEquals(0, buffer.buffered)
    }

    @Test
    fun `reset drops the remainder`() {
        val buffer = FrameBuffer(512)
        buffer.offer(ramp(0, 300))
        buffer.reset()
        assertEquals(0, buffer.buffered)
    }
}
