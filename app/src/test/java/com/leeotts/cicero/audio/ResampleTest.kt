package com.leeotts.cicero.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ResampleTest {

    @Test
    fun `empty stays empty`() {
        assertEquals(0, upsample2x(ShortArray(0)).size)
    }

    @Test
    fun `length doubles`() {
        assertEquals(1024, upsample2x(ShortArray(512)).size)
    }

    @Test
    fun `originals are preserved and midpoints interpolated`() {
        assertArrayEquals(
            shortArrayOf(0, 50, 100, 150, 200, 200),
            upsample2x(shortArrayOf(0, 100, 200)),
        )
    }

    @Test
    fun `the last sample is held rather than interpolated towards nothing`() {
        val out = upsample2x(shortArrayOf(10, 20))
        assertEquals(20, out[2].toInt())
        assertEquals(20, out[3].toInt())
    }

    @Test
    fun `negative samples interpolate without overflowing`() {
        assertArrayEquals(
            // Integer division truncates towards zero, hence 16383 rather than 16384.
            shortArrayOf(-32768, -16384, 0, 16383, 32767, 32767),
            upsample2x(shortArrayOf(-32768, 0, 32767)),
        )
    }
}
