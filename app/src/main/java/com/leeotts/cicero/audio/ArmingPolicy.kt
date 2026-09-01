package com.leeotts.cicero.audio

/**
 * Whether the wake-word microphone should be open right now.
 *
 * This is the battery design, in one pure function.
 *
 * Porcupine itself is not the cost - its inference is a rounding error next to
 * everything else the phone is doing. The cost is that an open AudioRecord
 * keeps the audio HAL, and with it the CPU, out of deep sleep continuously and
 * forever. A foreground service drains nothing on its own; the open microphone
 * does. Left permanently armed, expect 5-10% of the battery per hour, which is
 * a dead phone by mid-afternoon.
 *
 * So the most valuable thing this feature does is know when NOT to listen. If
 * listening is ever expensive at a moment it did not need to be, the bug is
 * here and nowhere else.
 *
 * Pure and synchronous so every rule is testable with no device, in the same
 * spirit as BrainConfig.localUrl and Router.roleFor.
 */
data class ArmingInputs(
    /** The Settings toggle. */
    val userEnabled: Boolean,
    val glassesConnected: Boolean,
    /** 0..100. */
    val batteryPercent: Int,
    val charging: Boolean,
    /** AudioManager.mode != MODE_NORMAL. */
    val inCall: Boolean,
    /** Another app is recording. */
    val micBusyElsewhere: Boolean,
)

/** The knobs [shouldArm] reads, so the policy needs nothing from BrainConfig. */
data class ArmingRules(
    /**
     * On by default, and the single largest saving in the feature.
     *
     * The point of the wake word is "what am I looking at" through the glasses.
     * With the glasses in a drawer there is nothing to look at, so holding the
     * microphone open buys nothing at all. For a normal wear pattern this is
     * most of the day, and it turns a 5-10%/hour feature into one that costs
     * nothing while the glasses are off.
     */
    val armOnlyWithGlasses: Boolean = true,
    /** Percent below which listening stops unless the phone is charging. */
    val batteryFloor: Int = 20,
)

/**
 * Note what is deliberately NOT a rule: the screen being off.
 *
 * It looks like an obvious saving and it is exactly backwards - the whole point
 * is the phone in a pocket with the screen off. Stated here so it does not get
 * "optimised" in later.
 */
fun shouldArm(inputs: ArmingInputs, rules: ArmingRules): Boolean {
    if (!inputs.userEnabled) return false

    // Yielding to whoever legitimately holds the microphone is both correct and
    // free: we would get nothing but silence from a contended mic anyway.
    if (inputs.inCall || inputs.micBusyElsewhere) return false

    if (rules.armOnlyWithGlasses && !inputs.glassesConnected) return false

    // On a charger the drain does not matter, so the floor does not apply. An
    // assistant that strands the user at 3% is worse than one they have to open
    // by hand.
    if (!inputs.charging && inputs.batteryPercent < rules.batteryFloor) return false

    return true
}

/**
 * Why the microphone is closed, for the notification and the Settings line.
 *
 * A user who cannot tell whether the app is listening will turn it off, so the
 * reason is surfaced rather than inferred. Returns null when armed.
 */
fun disarmedReason(inputs: ArmingInputs, rules: ArmingRules): DisarmedReason? = when {
    !inputs.userEnabled -> DisarmedReason.OFF
    inputs.inCall -> DisarmedReason.IN_CALL
    inputs.micBusyElsewhere -> DisarmedReason.MIC_BUSY
    rules.armOnlyWithGlasses && !inputs.glassesConnected -> DisarmedReason.NO_GLASSES
    !inputs.charging && inputs.batteryPercent < rules.batteryFloor -> DisarmedReason.LOW_BATTERY
    else -> null
}

enum class DisarmedReason { OFF, IN_CALL, MIC_BUSY, NO_GLASSES, LOW_BATTERY }
