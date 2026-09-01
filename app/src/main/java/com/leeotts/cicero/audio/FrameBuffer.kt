package com.leeotts.cicero.audio

/**
 * Re-slices ragged microphone reads into the exact frames a wake-word engine
 * demands.
 *
 * Two incompatible constraints meet here. Porcupine will only accept exactly
 * [frameLength] samples per process() call - 512, always. AudioRecord, on the
 * other hand, returns whatever it happens to have, and we deliberately ask it
 * for far more than one frame at a time: reading in ~100 ms blocks rather than
 * 32 ms ones is roughly 10 wakeups a second instead of 31, which is fewer
 * interrupts, fewer context switches, and deeper idle in between. On an
 * always-on microphone that is worth having.
 *
 * So this holds the remainder between reads. Pure, and one of the cheapest
 * things in the pipeline to get wrong silently - a dropped or duplicated sample
 * at a frame boundary degrades detection in a way no crash would announce.
 */
class FrameBuffer(private val frameLength: Int) {

    init {
        require(frameLength > 0) { "frameLength must be positive" }
    }

    private var pending = ShortArray(0)

    /**
     * Adds [count] samples from [samples] and returns every whole frame that
     * can now be formed, in order. Empty until a full frame is available.
     *
     * The returned arrays are fresh copies; the caller may hold or mutate them.
     */
    fun offer(samples: ShortArray, count: Int = samples.size): List<ShortArray> {
        require(count >= 0 && count <= samples.size) { "count out of range: $count" }
        if (count == 0 && pending.size < frameLength) return emptyList()

        val combined = ShortArray(pending.size + count)
        pending.copyInto(combined)
        samples.copyInto(combined, pending.size, 0, count)

        val whole = combined.size / frameLength
        if (whole == 0) {
            pending = combined
            return emptyList()
        }

        val frames = ArrayList<ShortArray>(whole)
        for (i in 0 until whole) {
            frames += combined.copyOfRange(i * frameLength, (i + 1) * frameLength)
        }
        pending = combined.copyOfRange(whole * frameLength, combined.size)
        return frames
    }

    /** Samples held back, waiting for the rest of a frame. Test-facing. */
    val buffered: Int get() = pending.size

    /** Drops the remainder, for when capture stops and resumes on new audio. */
    fun reset() {
        pending = ShortArray(0)
    }
}
