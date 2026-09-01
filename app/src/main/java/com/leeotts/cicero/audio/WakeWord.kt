package com.leeotts.cicero.audio

import kotlin.math.sqrt

/**
 * The three TFLite graphs openWakeWord chains together, behind one seam.
 *
 * It exists so the buffering in [WakeWordDetector] - which is where this gets
 * silently wrong rather than loudly broken - can be tested on the JVM with no
 * device, no microphone and no models. The TFLite types stay in
 * [TfLiteWakeWordModels] on the other side of this interface.
 */
internal interface WakeWordModels {

    /**
     * Mel frames for [samples], each MEL_BINS wide.
     *
     * [samples] are int16 magnitudes widened to float - NOT normalised to
     * +/-1. The graph was traced from a pipeline fed raw PCM values and reads
     * the 32768 scale directly; handing it normalised audio produces a
     * spectrogram some 90 dB down, which shows up as poor accuracy rather than
     * as an error.
     *
     * Implementations must apply openWakeWord's x / 10 + 2 output transform
     * before returning. See [TfLiteWakeWordModels].
     */
    fun melspectrogram(samples: FloatArray): Array<FloatArray>

    /** One 96-dim embedding for a 76 x 32 mel window. */
    fun embed(window: Array<FloatArray>): FloatArray

    /** Wake-word probability, 0..1, for 16 consecutive embeddings. */
    fun classify(features: Array<FloatArray>): Float

    fun close()
}

/** Where models come from, so a test can hand over fakes. */
internal interface WakeWordModelSource {
    fun load(): WakeWordModels
}

/** 16 kHz mono is the only rate the melspectrogram graph was traced for. */
const val WAKE_WORD_SAMPLE_RATE = 16_000

/** 80 ms. openWakeWord advances in exactly this step and nothing else lines up. */
const val WAKE_WORD_CHUNK = 1_280

/**
 * Samples of history prepended to each chunk before the melspectrogram runs.
 *
 * 160 * 3 upstream. The graph needs a run-up to place its first window, and
 * without it every chunk loses frames off the front, so the mel buffer drifts
 * out of step with the 8-frames-per-chunk that the embedding stride assumes.
 */
private const val MEL_CONTEXT = 480

private const val MEL_BINS = 32
private const val EMBED_WINDOW = 76
private const val EMBED_STEP = 8
private const val FEATURE_FRAMES = 16

/** 10 * 97 frames, ~10 s. Upstream's cap; only the last 76 are ever read. */
private const val MEL_MAX = 970

/**
 * Spots a wake word in a stream of 16 kHz mono PCM.
 *
 * The pipeline is openWakeWord's, and the constants are load-bearing: audio
 * accumulates into 1280-sample chunks, each chunk becomes 8 mel frames, every
 * 76 mel frames become one 96-dim embedding, and the classifier scores the last
 * 16 embeddings - 1.28 s of context. Getting any of these wrong yields a
 * detector that runs happily and never fires, so each stage is a named field
 * with its own invariant and its own test.
 *
 * Not thread-safe: [feed] is expected to be driven by one capture loop.
 */
class WakeWordDetector internal constructor(
    private val models: WakeWordModels,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val vadThreshold: Float = DEFAULT_VAD_THRESHOLD,
    private val hangoverChunks: Int = DEFAULT_HANGOVER_CHUNKS,
) {

    companion object {
        /**
         * openWakeWord's own default. Lower catches more and costs more false
         * accepts; this is the knob to turn first, ahead of retraining.
         */
        const val DEFAULT_THRESHOLD = 0.5f

        /**
         * Int16 RMS above which a chunk counts as speech and the embedding
         * model is allowed to run.
         *
         * Deliberately low. A false open wastes one chunk of inference; a false
         * close drops the wake word, and the user cannot tell which happened.
         * Room noise sits well under this, speech well over.
         */
        const val DEFAULT_VAD_THRESHOLD = 150f

        /** ~1 s. How long the gate stays open after the last loud chunk. */
        const val DEFAULT_HANGOVER_CHUNKS = 13
    }

    /** Partial chunk carried between [feed] calls; AudioRecord reads any size. */
    private val pending = FloatArray(WAKE_WORD_CHUNK)
    private var pendingCount = 0

    /** The MEL_CONTEXT samples preceding the current chunk. */
    private val context = FloatArray(MEL_CONTEXT)

    /**
     * Mel frames, oldest first.
     *
     * Seeded with ones rather than zeros, matching upstream: the first
     * embedding reads 76 frames when only 8 exist, and the classifier was
     * trained against a buffer padded this way.
     */
    private val mel = ArrayDeque<FloatArray>().apply {
        repeat(EMBED_WINDOW) { add(FloatArray(MEL_BINS) { 1f }) }
    }

    /** The last 16 embeddings, oldest first, contiguous in time. */
    private val features = ArrayDeque<FloatArray>()

    private var hangover = 0
    private var gateOpen = false

    /** Last score computed, for logging. Stays put while the gate is shut. */
    var lastScore: Float = 0f
        private set

    /**
     * Feeds PCM in and reports whether the wake word fired during this call.
     *
     * [pcm] is 16-bit mono at [WAKE_WORD_SAMPLE_RATE] and may be any length;
     * whatever does not complete a chunk is carried over to the next call.
     */
    fun feed(pcm: ShortArray, length: Int = pcm.size): Boolean {
        var fired = false
        var offset = 0
        while (offset < length) {
            val take = minOf(WAKE_WORD_CHUNK - pendingCount, length - offset)
            for (i in 0 until take) pending[pendingCount + i] = pcm[offset + i].toFloat()
            pendingCount += take
            offset += take
            if (pendingCount == WAKE_WORD_CHUNK) {
                if (processChunk()) fired = true
                pendingCount = 0
            }
        }
        return fired
    }

    /** Drops all history. The next detection needs a full 1.28 s again. */
    fun reset() {
        pendingCount = 0
        context.fill(0f)
        mel.clear()
        repeat(EMBED_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        hangover = 0
        gateOpen = false
        lastScore = 0f
    }

    fun close() = models.close()

    /** One 80 ms step. Returns true when this chunk pushed the score over. */
    private fun processChunk(): Boolean {
        appendMelFrames()

        // The gate gets the cheap half of the pipeline unconditionally - mel
        // history has to stay unbroken or a later window straddles the gap -
        // and guards only the embedding model, which is the expensive half.
        val wasOpen = gateOpen
        updateGate(rms(pending))
        if (!gateOpen) {
            features.clear()
            return false
        }

        if (!wasOpen) {
            // Opening on the first loud chunk would leave 16 embeddings still
            // to gather, and the phrase would be over before the classifier had
            // context for it. The mel buffer already holds that history, so
            // rebuild the whole window at once rather than waiting for it.
            backfillFeatures()
        } else {
            pushFeature(embedAt(0))
        }

        if (features.size < FEATURE_FRAMES) return false
        lastScore = models.classify(features.toTypedArray())
        if (lastScore < threshold) return false

        // Clearing forces a fresh 1.28 s before the next fire, so one spoken
        // phrase cannot report itself again on every chunk it remains inside.
        features.clear()
        return true
    }

    /**
     * Runs the melspectrogram over this chunk plus its run-up, appends the
     * frames, then keeps the tail as the run-up for next time.
     */
    private fun appendMelFrames() {
        val input = FloatArray(MEL_CONTEXT + WAKE_WORD_CHUNK)
        context.copyInto(input, 0)
        pending.copyInto(input, MEL_CONTEXT)

        for (frame in models.melspectrogram(input)) mel.addLast(frame)
        while (mel.size > MEL_MAX) mel.removeFirst()

        pending.copyInto(context, 0, WAKE_WORD_CHUNK - MEL_CONTEXT, WAKE_WORD_CHUNK)
    }

    /**
     * The embedding whose 76-frame window ends [stepsBack] * 8 frames before
     * the end of the mel buffer - upstream's ndx = -8 * i.
     */
    private fun embedAt(stepsBack: Int): FloatArray {
        val end = mel.size - EMBED_STEP * stepsBack
        val start = end - EMBED_WINDOW
        val window = Array(EMBED_WINDOW) { mel.elementAt(start + it) }
        return models.embed(window)
    }

    /** Rebuilds all 16 embeddings from mel history, oldest first. */
    private fun backfillFeatures() {
        features.clear()
        for (i in FEATURE_FRAMES - 1 downTo 0) {
            if (mel.size - EMBED_STEP * i < EMBED_WINDOW) continue
            pushFeature(embedAt(i))
        }
    }

    private fun pushFeature(embedding: FloatArray) {
        features.addLast(embedding)
        while (features.size > FEATURE_FRAMES) features.removeFirst()
    }

    private fun updateGate(level: Float) {
        if (level >= vadThreshold) {
            hangover = hangoverChunks
            gateOpen = true
            return
        }
        if (hangover > 0) hangover--
        if (hangover == 0) gateOpen = false
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }
}
