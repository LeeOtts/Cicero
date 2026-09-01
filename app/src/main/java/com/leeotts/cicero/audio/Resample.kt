package com.leeotts.cicero.audio

/**
 * Doubles a sample rate by linear interpolation - 8 kHz to 16 kHz, and nothing
 * else.
 *
 * Needed only if the glasses negotiate narrowband HFP. ScoProbe exists to
 * settle that question, and its docstring is blunt about the consequence:
 * Porcupine needs true 16 kHz, and upsampled narrowband leaves the entire top
 * half of the spectrum empty. The samples are then the right shape for the
 * engine without carrying the information the engine was trained on.
 *
 * So this is a fallback, not a fix. If the probe shows 8 kHz, expect detection
 * accuracy on the glasses microphone to be materially worse than on the phone,
 * and treat that as a measurement rather than something to tune away.
 */
fun upsample2x(input: ShortArray): ShortArray {
    if (input.isEmpty()) return ShortArray(0)

    val out = ShortArray(input.size * 2)
    for (i in input.indices) {
        val current = input[i].toInt()
        // The last sample has nothing to interpolate towards, so it is held.
        val next = if (i + 1 < input.size) input[i + 1].toInt() else current
        out[i * 2] = current.toShort()
        out[i * 2 + 1] = ((current + next) / 2).toShort()
    }
    return out
}
