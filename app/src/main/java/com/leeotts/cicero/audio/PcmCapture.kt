package com.leeotts.cicero.audio

import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.Audio
import com.leeotts.cicero.ai.Transcriber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Captures the question straight from the microphone, for the glasses path.
 *
 * There is no recognizer in the loop over Bluetooth HFP - just raw PCM - so
 * this does the two jobs Android would otherwise do: decide where the question
 * ends, and turn it into words.
 *
 * This is the first thing in the app that ever produces an [Audio]. Transcriber
 * and WhisperTranscriber were written for it and have had nothing to consume
 * them until now, which is why neither needed changing to support this.
 */
class PcmCapture(
    private val source: AudioSource,
    private val transcriber: Transcriber,
    private val dispatcher: CoroutineDispatcher,
) : UtteranceCapture {

    @Volatile
    private var cancelled = false

    override suspend fun capture(): String? {
        cancelled = false
        val samples = withContext(dispatcher) { record() } ?: return null

        return runCatching {
            transcriber.transcribe(Audio(wavOf(samples, source.sampleRate), source.sampleRate))
        }.getOrElse {
            // A speech endpoint that is unset or unreachable is the likely
            // cause. Returning null keeps the loop listening rather than
            // speaking an error the user cannot act on mid-sentence.
            Log.e(TAG, "transcription failed", it)
            null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The spoken samples, or null when nothing was said. */
    private fun record(): ShortArray? {
        val endpointer = Endpointer(sampleRate = source.sampleRate)
        val block = ShortArray(READ_SAMPLES)
        val collected = ArrayList<Short>()

        while (!cancelled) {
            val read = source.read(block)
            if (read < 0) break
            if (read == 0) continue

            val frame = block.copyOf(read)
            collected.ensureCapacity(collected.size + read)
            frame.forEach(collected::add)

            when (endpointer.offer(frame)) {
                Endpointer.State.DONE -> return collected.toShortArray()
                Endpointer.State.ABANDONED -> return null
                else -> Unit
            }
        }
        // Cancelled or the source closed under us. Whatever was collected is a
        // fragment of a sentence at best, and sending it would put a
        // half-question to the model.
        return null
    }

    override fun cancel() {
        cancelled = true
    }

    private companion object {
        /** ~100 ms at 16 kHz, matching the coordinator's own read size. */
        const val READ_SAMPLES = 1_600
    }
}
