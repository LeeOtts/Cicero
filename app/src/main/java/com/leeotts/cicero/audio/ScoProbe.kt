package com.leeotts.cicero.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Phase 2 spike. Answers one question: what sample rate does the glasses
 * microphone actually deliver over Bluetooth HFP?
 *
 * The DAT docs say 8 kHz mono, but if the phone and glasses negotiate HFP
 * wideband (mSBC) it is 16 kHz — and that changes which wake-word engine is
 * viable. Porcupine needs true 16 kHz; feeding it upsampled narrowband leaves
 * the top half of the spectrum empty and accuracy collapses. Measure, do not
 * guess.
 *
 * Result is a WAV in the app's external files dir. Pull and inspect it:
 *   adb pull /sdcard/Android/data/com.leeotts.cicero/files/probe-16000.wav
 */
class ScoProbe(private val context: Context) {

    data class Result(
        val sampleRate: Int,
        val file: File,
        val bytesRecorded: Int,
        val peakAmplitude: Int,
        val routedToBluetooth: Boolean,
    )

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Tries 16 kHz first, then falls back to 8 kHz. */
    suspend fun probe(seconds: Int = 5): List<Result> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result>()
        for (rate in intArrayOf(16_000, 8_000)) {
            runCatching { record(rate, seconds) }
                .onSuccess { results += it }
                .onFailure { Log.e(TAG, "probe at $rate Hz failed: ${it.message}") }
        }
        results
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(sampleRate: Int, seconds: Int): Result {
        val routed = audioManager.selectBluetoothScoDevice()
        // The SCO route needs a moment to settle before AudioRecord sees it.
        delay(ROUTE_SETTLE_MS)

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "unsupported sample rate $sampleRate" }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 4,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord did not initialise at $sampleRate Hz"
        }

        val file = File(context.getExternalFilesDir(null), "probe-$sampleRate.wav")
        var total = 0
        var peak = 0

        try {
            record.startRecording()
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(WAV_HEADER_BYTES)) // placeholder, patched below

                val buffer = ByteArray(minBuffer)
                val deadline = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    out.write(buffer, 0, read)
                    total += read
                    peak = maxOf(peak, peakOf(buffer, read))
                }
                out.seek(0)
                out.write(wavHeader(total, sampleRate))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            audioManager.clearRoute()
        }

        Log.i(
            TAG,
            "SCO probe $sampleRate Hz: $total bytes, peak=$peak, bluetooth=$routed -> $file",
        )
        return Result(sampleRate, file, total, peak, routed)
    }
}
