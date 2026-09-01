package com.leeotts.cicero.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.leeotts.cicero.TAG

/**
 * The real microphones behind [AudioSource].
 *
 * Both deliver 16 kHz mono PCM16, which is the only thing Porcupine accepts.
 */
class AndroidAudioSources(
    context: Context,
    /**
     * Whether to ask for unprocessed audio on the phone.
     *
     * VOICE_RECOGNITION is the default and applies light pre-processing;
     * UNPROCESSED skips the DSP chain entirely, which draws less power but can
     * cost accuracy in a noisy room. Exposed so the difference can be measured
     * on real hardware rather than guessed at.
     */
    private val unprocessed: Boolean = false,
) : AudioSources {

    private val appContext = context.applicationContext

    override fun create(source: MicSource): AudioSource = when (source) {
        MicSource.PHONE -> PhoneAudioSource(unprocessed)
        MicSource.GLASSES -> GlassesAudioSource(appContext)
    }
}

/**
 * The phone's own microphone. The default, and the only one that coexists with
 * "Hey Meta".
 */
private class PhoneAudioSource(private val unprocessed: Boolean) : AudioSource {

    override val description = "phone microphone"
    private var record: AudioRecord? = null

    /**
     * Not VOICE_COMMUNICATION, which runs the full call-oriented echo
     * cancellation and gain control chain: it is the most expensive option and
     * it is tuned for a phone call, not for a keyword spotter.
     */
    private val audioSource
        get() = if (unprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before starting
    override fun open(): Boolean {
        if (record != null) return true
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            Log.e(TAG, "16 kHz mono capture unsupported on this device")
            return false
        }
        val created = runCatching {
            AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes(minimum))
        }.getOrElse {
            Log.e(TAG, "AudioRecord could not be created", it)
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            Log.e(TAG, "AudioRecord did not initialise")
            return false
        }
        created.startRecording()
        record = created
        return true
    }

    override fun read(into: ShortArray): Int {
        val active = record ?: return -1
        val read = active.read(into, 0, into.size)
        // Negative codes are genuine errors; the source is finished either way.
        return if (read < 0) -1 else read
    }

    override fun close() {
        val active = record ?: return
        record = null
        runCatching { active.stop() }
        active.release()
    }
}

/**
 * The glasses microphone, over Bluetooth HFP.
 *
 * Opt-in and off by default, because holding this route open is not a small
 * thing: it takes the microphone away from Meta AI for as long as it runs, so
 * "Hey Meta" does not degrade, it stops. All glasses audio drops to narrowband
 * mono meanwhile, and both the phone and the glasses drain faster - and of the
 * two, the glasses have the small battery you cannot easily top up mid-day.
 *
 * Whether it can even work is an open question until ScoProbe has been run on
 * real hardware: Porcupine needs true 16 kHz, and if the link negotiates
 * narrowband then [upsample2x] makes the samples the right shape without
 * restoring the information the engine was trained on.
 */
private class GlassesAudioSource(context: Context) : AudioSource {

    override val description = "glasses microphone"

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null

    /** 16 kHz when the link is wideband, 8 kHz when it is not. */
    private var captureRate = SAMPLE_RATE

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        if (record != null) return true
        if (!audioManager.selectBluetoothScoDevice()) return false

        // The route needs a moment before AudioRecord can see it. Blocking is
        // correct here: open() is called off the main thread and there is
        // nothing useful to do in the meantime.
        Thread.sleep(ROUTE_SETTLE_MS)

        for (rate in intArrayOf(SAMPLE_RATE, NARROWBAND)) {
            val minimum = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
            if (minimum <= 0) continue
            val created = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    CHANNEL,
                    ENCODING,
                    bufferBytes(minimum),
                )
            }.getOrNull() ?: continue

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                continue
            }
            created.startRecording()
            record = created
            captureRate = rate
            Log.i(TAG, "glasses microphone open at $rate Hz")
            return true
        }
        audioManager.clearRoute()
        Log.e(TAG, "glasses microphone would not open at 16 kHz or 8 kHz")
        return false
    }

    override fun read(into: ShortArray): Int {
        val active = record ?: return -1
        if (captureRate == SAMPLE_RATE) {
            val read = active.read(into, 0, into.size)
            return if (read < 0) -1 else read
        }

        // Narrowband: read half as many samples and stretch them, so the caller
        // still sees a 16 kHz stream. Accuracy suffers - see the class comment.
        val half = ShortArray(into.size / 2)
        val read = active.read(half, 0, half.size)
        if (read < 0) return -1
        if (read == 0) return 0
        val stretched = upsample2x(half.copyOf(read))
        stretched.copyInto(into)
        return stretched.size
    }

    override fun close() {
        val active = record
        record = null
        if (active != null) {
            runCatching { active.stop() }
            active.release()
        }
        // Handing the route back is what lets media return to A2DP and gives
        // Meta AI its microphone back. It must happen even if stop() threw.
        audioManager.clearRoute()
    }
}

private const val SAMPLE_RATE = 16_000
private const val NARROWBAND = 8_000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Four times the platform minimum.
 *
 * Headroom matters more than latency here: the reader is a coroutine that can
 * be descheduled, and an overrun silently drops audio the detector then never
 * sees. A wake word that misses one word in fifty is indistinguishable from a
 * badly trained one.
 */
private fun bufferBytes(minimum: Int) = minimum * 4
