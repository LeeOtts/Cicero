package com.leeotts.cicero.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The real graphs, on a real device.
 *
 * [WakeWordTest] covers the buffering with fakes; this covers everything the
 * fakes stand in for - that the assets survive packaging uncompressed and can
 * be mapped, that the resizable melspectrogram input is handled, that the
 * shapes are what the buffering assumes, and that a pass costs far less than
 * the 80 ms it is given.
 *
 * It deliberately does not assert that the wake word fires: that needs a
 * recording of someone saying it, and the phrase here is still openWakeWord's
 * stand-in rather than "Hey Cicero".
 */
@RunWith(AndroidJUnit4::class)
class WakeWordModelsTest {

    private lateinit var models: WakeWordModels

    private val chunkWithRunUp = 480 + WAKE_WORD_CHUNK

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Fails here if the .tflite assets were compressed into the APK, which
        // is what the noCompress rule in build.gradle.kts prevents.
        models = WakeWordAssets(context).load()
    }

    @After
    fun tearDown() {
        models.close()
    }

    /** Speech-like level, so the detector's VAD gate opens. */
    private fun noise(n: Int, amplitude: Int = 3000) =
        ShortArray(n) { Random.nextInt(-amplitude, amplitude).toShort() }

    private fun noiseFloats(n: Int) = FloatArray(n) { Random.nextInt(-3000, 3000).toFloat() }

    @Test
    fun melspectrogramTurnsOneChunkIntoEightFrames() {
        val frames = models.melspectrogram(noiseFloats(chunkWithRunUp))

        // The embedding stride assumes exactly this. If the run-up were
        // dropped the count would come up short and the buffer would drift.
        assertEquals(8, frames.size)
        assertTrue(frames.all { it.size == 32 })
    }

    @Test
    fun melspectrogramOutputIsScaledForTheEmbeddingModel() {
        val frames = models.melspectrogram(noiseFloats(chunkWithRunUp))
        val values = frames.flatMap { it.asList() }

        // openWakeWord's x / 10 + 2. Raw output runs to tens; scaled it sits in
        // low single digits, which is the range the classifiers were trained
        // against. Wrong here and the wake word never fires, with no error.
        Log.i(TAG, "mel range ${values.min()}..${values.max()}")
        assertTrue("looks unscaled: ${values.max()}", values.max() < 20f)
        assertTrue("looks empty: ${values.max()}", values.max() > 0f)
    }

    @Test
    fun melspectrogramHandlesAChangeOfInputLength() {
        // The graph ships with a placeholder [1, 1] input, so every distinct
        // length has to resize it. Two lengths in a row is the case that
        // catches a resize done once and then assumed.
        assertEquals(8, models.melspectrogram(noiseFloats(chunkWithRunUp)).size)
        assertEquals(16, models.melspectrogram(noiseFloats(480 + WAKE_WORD_CHUNK * 2)).size)
        assertEquals(8, models.melspectrogram(noiseFloats(chunkWithRunUp)).size)
    }

    @Test
    fun embeddingsAre96Wide() {
        val window = Array(76) { FloatArray(32) { 2f } }

        assertEquals(96, models.embed(window).size)
    }

    @Test
    fun classifierReturnsAProbability() {
        val features = Array(16) { FloatArray(96) { 0f } }
        val score = models.classify(features)

        assertTrue("score out of range: $score", score in 0f..1f)
    }

    @Test
    fun noiseDoesNotFireTheWakeWord() {
        val detector = WakeWordDetector(models)

        var fired = false
        repeat(120) { if (detector.feed(noise(WAKE_WORD_CHUNK))) fired = true }

        assertFalse("false accept on ~10 s of noise", fired)
    }

    /**
     * Milliseconds per 80 ms chunk, averaged over [chunks].
     *
     * The audio is generated up front on purpose: filling 1280 samples from
     * Random costs enough to swamp the inference being measured, and doing it
     * inside the loop made speech and silence look identical.
     */
    private fun perChunkMs(amplitude: Int, chunks: Int = 200): Double {
        val detector = WakeWordDetector(models)
        val audio = Array(chunks) { noise(WAKE_WORD_CHUNK, amplitude) }
        val warmUp = Array(20) { noise(WAKE_WORD_CHUNK, amplitude) }

        warmUp.forEach { detector.feed(it) }
        val started = System.nanoTime()
        audio.forEach { detector.feed(it) }
        return (System.nanoTime() - started) / 1_000_000.0 / chunks
    }

    @Test
    fun aChunkIsProcessedWellInsideItsBudget() {
        // Loud, so the VAD gate is open and the embedding model - the expensive
        // half, and the half the battery argument rests on - actually runs.
        val ms = perChunkMs(amplitude = 3000)

        Log.i(TAG, "wake word speech: %.2f ms per 80 ms chunk".format(ms))
        assertTrue("no real-time headroom: $ms ms per 80 ms", ms < 30.0)
    }

    @Test
    fun silenceCostsLessThanSpeech() {
        // Under the VAD threshold, so the embedding model is skipped and only
        // the melspectrogram runs. This is the state the phone is in almost all
        // day, so it - not the speech figure - is what the battery cost comes
        // down to.
        val idle = perChunkMs(amplitude = 20)
        val speech = perChunkMs(amplitude = 3000)

        Log.i(TAG, "wake word idle: %.2f ms, speech: %.2f ms".format(idle, speech))
        assertTrue("idle should be cheaper than speech: $idle vs $speech", idle < speech)
        assertTrue("idle is not cheap enough to run all day: $idle ms", idle < 10.0)
    }
}
