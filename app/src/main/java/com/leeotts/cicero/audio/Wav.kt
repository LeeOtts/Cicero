package com.leeotts.cicero.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Just enough WAV to hand recorded PCM to something that expects a file.
 *
 * Extracted from ScoProbe, which wrote these headers so a probe recording could
 * be pulled off the device and listened to. The wake word's glasses path needs
 * the identical thing for a different reason: WhisperTranscriber posts a file,
 * and raw PCM16 is not one.
 */
internal const val WAV_HEADER_BYTES = 44

/** A 44-byte canonical PCM header for [dataBytes] of 16-bit mono at [sampleRate]. */
internal fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int = 1): ByteArray {
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + dataBytes)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1) // PCM
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((channels * bitsPerSample / 8).toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray())
        putInt(dataBytes)
    }.array()
}

/** Wraps PCM16 samples as a complete little-endian WAV file. */
internal fun wavOf(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    out.put(wavHeader(dataBytes, sampleRate))
    samples.forEach(out::putShort)
    return out.array()
}

/** Loudest absolute sample in the first [length] bytes of little-endian PCM16. */
internal fun peakOf(buffer: ByteArray, length: Int): Int {
    var peak = 0
    var i = 0
    while (i + 1 < length) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
        peak = maxOf(peak, abs(sample.toInt()))
        i += 2
    }
    return peak
}
