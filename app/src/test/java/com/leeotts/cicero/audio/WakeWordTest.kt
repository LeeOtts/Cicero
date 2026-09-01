package com.leeotts.cicero.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffering, driven through fake models.
 *
 * This is the half that fails silently: get the chunk size, the run-up, the
 * 76-frame window or the 16-embedding stride wrong and the detector still runs,
 * still consumes audio, and simply never fires. The three graphs are
 * openWakeWord's and are not under test here - only the plumbing between them.
 */
class WakeWordTest {

    /** Every frame carries its own id in all bins, so windows are identifiable. */
    private class FakeModels(var score: Float = 0f) : WakeWordModels {
        val melInputs = mutableListOf<FloatArray>()
        val embedWindows = mutableListOf<Array<FloatArray>>()
        val classified = mutableListOf<Array<FloatArray>>()
        var closed = false

        private var nextFrame = 0
        private var nextEmbedding = 0

        /** Mirrors the real graph: 160 samples per frame, after the 480 run-up. */
        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            melInputs += samples.copyOf()
            val frames = (samples.size - 480) / 160
            return Array(frames) {
                val id = nextFrame++.toFloat()
                FloatArray(32) { id }
            }
        }

        override fun embed(window: Array<FloatArray>): FloatArray {
            embedWindows += Array(window.size) { window[it].copyOf() }
            val id = nextEmbedding++.toFloat()
            return FloatArray(96) { id }
        }

        override fun classify(features: Array<FloatArray>): Float {
            classified += Array(features.size) { features[it].copyOf() }
            return score
        }

        override fun close() {
            closed = true
        }
    }

    private fun detector(
        models: FakeModels,
        threshold: Float = 0.5f,
        vadThreshold: Float = 150f,
        hangoverChunks: Int = 13,
    ) = WakeWordDetector(models, threshold, vadThreshold, hangoverChunks)

    /** RMS 1000, comfortably over the gate. */
    private fun loud(n: Int = WAKE_WORD_CHUNK) = ShortArray(n) { 1000 }

    private fun quiet(n: Int = WAKE_WORD_CHUNK) = ShortArray(n) { 0 }

    private fun WakeWordDetector.feedChunks(count: Int, pcm: () -> ShortArray): Boolean {
        var fired = false
        repeat(count) { if (feed(pcm())) fired = true }
        return fired
    }

    @Test
    fun `audio is chunked at 1280 whatever size it arrives in`() {
        val models = FakeModels()
        val detector = detector(models)

        // 300 does not divide 1280, so every boundary lands mid-read.
        repeat(13) { detector.feed(loud(300)) }

        assertEquals(3, models.melInputs.size)
    }

    @Test
    fun `a partial chunk waits rather than running short`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feed(loud(WAKE_WORD_CHUNK - 1))

        assertTrue(models.melInputs.isEmpty())
    }

    @Test
    fun `each chunk reaches the melspectrogram with 480 samples of run-up`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feed(loud())

        assertEquals(480 + WAKE_WORD_CHUNK, models.melInputs.single().size)
    }

    @Test
    fun `the run-up is the tail of the previous chunk`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feed(ShortArray(WAKE_WORD_CHUNK) { 1000 })
        detector.feed(ShortArray(WAKE_WORD_CHUNK) { 2000 })

        val second = models.melInputs[1]
        assertTrue("run-up should be the previous chunk", second.take(480).all { it == 1000f })
        assertTrue("body should be the current chunk", second.drop(480).all { it == 2000f })
    }

    @Test
    fun `the first chunk sees a mel buffer seeded with ones`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feed(loud())

        // Only 8 real frames exist, so the window is mostly seed padding - and
        // the classifier was trained against ones, not zeros.
        val window = models.embedWindows.first()
        assertEquals(76, window.size)
        assertTrue(window.first().all { it == 1f })
    }

    @Test
    fun `every embedding window is 76 frames of 32 bins`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feedChunks(20) { loud() }

        assertTrue(models.embedWindows.isNotEmpty())
        assertTrue(models.embedWindows.all { it.size == 76 && it.all { f -> f.size == 32 } })
    }

    @Test
    fun `the classifier only ever sees 16 embeddings`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feedChunks(40) { loud() }

        assertTrue(models.classified.isNotEmpty())
        assertTrue(models.classified.all { it.size == 16 })
    }

    @Test
    fun `nothing is scored until a full 16 embeddings have been gathered`() {
        val models = FakeModels()
        val detector = detector(models)

        // Well short of the ~1.2 s the window needs.
        detector.feedChunks(5) { loud() }

        assertTrue(models.classified.isEmpty())
    }

    @Test
    fun `the embeddings handed to the classifier are consecutive`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feedChunks(40) { loud() }

        // Each fake embedding is filled with its own ordinal, so a gap here
        // means a window straddled a break in the feature buffer.
        val ids = models.classified.last().map { it.first() }
        assertEquals(ids.sorted(), ids)
        assertEquals(15f, ids.last() - ids.first(), 0f)
    }

    @Test
    fun `silence runs the cheap graph but never the expensive one`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feedChunks(30) { quiet() }

        // Mel history has to stay unbroken, so that one still runs; the
        // embedding model is the one the battery gate is there to stop.
        assertEquals(30, models.melInputs.size)
        assertTrue(models.embedWindows.isEmpty())
        assertTrue(models.classified.isEmpty())
    }

    @Test
    fun `speech opens the gate with a full window rather than an empty one`() {
        val models = FakeModels()
        val detector = detector(models)

        detector.feedChunks(30) { quiet() }
        detector.feed(loud())

        // Backfilled from mel history: waiting for 16 fresh embeddings would
        // let the phrase finish before the classifier had context for it.
        assertEquals(16, models.embedWindows.size)
        assertEquals(1, models.classified.size)
    }

    @Test
    fun `the gate stays open across a short pause`() {
        val models = FakeModels()
        val detector = detector(models, hangoverChunks = 3)

        detector.feed(loud())
        val afterSpeech = models.embedWindows.size
        detector.feedChunks(2) { quiet() }
        val duringPause = models.embedWindows.size
        detector.feedChunks(5) { quiet() }

        assertTrue("hangover should keep embedding", duringPause > afterSpeech)
        assertEquals("then it should stop", duringPause, models.embedWindows.size)
    }

    @Test
    fun `a score over the threshold fires`() {
        val models = FakeModels(score = 0.9f)
        val detector = detector(models)

        assertTrue(detector.feedChunks(40) { loud() })
    }

    @Test
    fun `a score under the threshold does not`() {
        val models = FakeModels(score = 0.4f)
        val detector = detector(models)

        assertFalse(detector.feedChunks(40) { loud() })
    }

    @Test
    fun `one phrase fires once, not on every chunk it stays inside`() {
        val models = FakeModels(score = 0.9f)
        val detector = detector(models)

        detector.feedChunks(40) { loud() }
        val again = detector.feed(loud())

        assertFalse("needs a fresh window before it can fire again", again)
    }

    @Test
    fun `the score is exposed for logging`() {
        val models = FakeModels(score = 0.77f)
        val detector = detector(models)

        detector.feedChunks(40) { loud() }

        assertEquals(0.77f, detector.lastScore, 1e-6f)
    }

    @Test
    fun `reset drops history, so a detection needs a full window again`() {
        val models = FakeModels(score = 0.9f)
        val detector = detector(models)

        detector.feedChunks(40) { loud() }
        detector.reset()

        assertEquals(0f, detector.lastScore, 0f)
        assertFalse(detector.feed(loud()))
    }

    @Test
    fun `close releases the models`() {
        val models = FakeModels()
        detector(models).close()

        assertTrue(models.closed)
    }
}
