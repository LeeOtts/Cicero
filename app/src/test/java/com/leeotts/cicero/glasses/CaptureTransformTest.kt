package com.leeotts.cicero.glasses

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The EXIF orientation table behind upright captures.
 *
 * Captures through the glasses arrived on their side because the frame carries
 * its orientation as a tag rather than in the pixels, and BitmapFactory drops
 * it. The mapping is the part worth pinning: Matrix itself is Android, but
 * getting one of these eight cases wrong is silent - the photo simply looks
 * wrong, and the model sees it that way too.
 */
class CaptureTransformTest {

    @Test
    fun `a normal capture is left alone`() {
        assertNull(transformFor(ExifInterface.ORIENTATION_NORMAL))
    }

    /**
     * The two values a camera writes when it has no idea, both of which used to
     * be indistinguishable from a real rotation if the table over-reached.
     */
    @Test
    fun `an undefined or unrecognised orientation is left alone`() {
        assertNull(transformFor(ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(transformFor(0))
        assertNull(transformFor(99))
    }

    @Test
    fun `the three plain rotations turn by their own angle`() {
        assertEquals(CaptureTransform(90f, mirrored = false), transformFor(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(CaptureTransform(180f, mirrored = false), transformFor(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(CaptureTransform(270f, mirrored = false), transformFor(ExifInterface.ORIENTATION_ROTATE_270))
    }

    /**
     * Mirrored orientations are rare off a camera, but a photo flipped
     * left-for-right reads as correct while showing the wrong thing - the one
     * failure here nobody would catch by eye.
     */
    @Test
    fun `the mirrored orientations are flipped as well as turned`() {
        assertEquals(
            CaptureTransform(0f, mirrored = true),
            transformFor(ExifInterface.ORIENTATION_FLIP_HORIZONTAL),
        )
        assertEquals(
            CaptureTransform(180f, mirrored = true),
            transformFor(ExifInterface.ORIENTATION_FLIP_VERTICAL),
        )
        assertEquals(
            CaptureTransform(90f, mirrored = true),
            transformFor(ExifInterface.ORIENTATION_TRANSPOSE),
        )
        assertEquals(
            CaptureTransform(270f, mirrored = true),
            transformFor(ExifInterface.ORIENTATION_TRANSVERSE),
        )
    }

    /** Every orientation the spec defines has to be accounted for, not just the ones seen. */
    @Test
    fun `all eight defined orientations are handled`() {
        val defined = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        // NORMAL is the only one of the eight that is deliberately a no-op.
        val transforms = defined.map { transformFor(it) }
        assertNull(transforms.first())
        assertFalse("no defined orientation past NORMAL should be ignored", transforms.drop(1).any { it == null })
    }
}
