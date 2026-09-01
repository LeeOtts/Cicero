package com.leeotts.cicero.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery contract. Every rule that keeps the microphone shut is asserted
 * here, because the cost of getting one wrong is not a crash - it is a phone
 * that quietly dies at four in the afternoon.
 */
class ArmingPolicyTest {

    /** Armed: glasses on, plenty of battery, nothing else holding the mic. */
    private val ready = ArmingInputs(
        userEnabled = true,
        glassesConnected = true,
        batteryPercent = 80,
        charging = false,
        inCall = false,
        micBusyElsewhere = false,
    )
    private val rules = ArmingRules()

    @Test
    fun `the happy path arms`() {
        assertTrue(shouldArm(ready, rules))
        assertNull(disarmedReason(ready, rules))
    }

    @Test
    fun `the toggle wins over everything`() {
        assertFalse(shouldArm(ready.copy(userEnabled = false), rules))
        assertEquals(DisarmedReason.OFF, disarmedReason(ready.copy(userEnabled = false), rules))
    }

    @Test
    fun `disconnected glasses disarm - the largest saving in the feature`() {
        val away = ready.copy(glassesConnected = false)
        assertFalse(shouldArm(away, rules))
        assertEquals(DisarmedReason.NO_GLASSES, disarmedReason(away, rules))
    }

    @Test
    fun `disconnected glasses still arm when the user opted out of the gate`() {
        // Their battery, their choice - but it must be a choice, not a default.
        val away = ready.copy(glassesConnected = false)
        assertTrue(shouldArm(away, rules.copy(armOnlyWithGlasses = false)))
    }

    @Test
    fun `a flat battery disarms`() {
        val flat = ready.copy(batteryPercent = 15)
        assertFalse(shouldArm(flat, rules))
        assertEquals(DisarmedReason.LOW_BATTERY, disarmedReason(flat, rules))
    }

    @Test
    fun `charging overrides the battery floor`() {
        assertTrue(shouldArm(ready.copy(batteryPercent = 3, charging = true), rules))
    }

    @Test
    fun `the floor re-arms on the way back up`() {
        assertFalse(shouldArm(ready.copy(batteryPercent = 19), rules))
        assertTrue(shouldArm(ready.copy(batteryPercent = 20), rules))
    }

    @Test
    fun `a call disarms, and outranks a low battery in the reason`() {
        val calling = ready.copy(inCall = true, batteryPercent = 5)
        assertFalse(shouldArm(calling, rules))
        assertEquals(DisarmedReason.IN_CALL, disarmedReason(calling, rules))
    }

    @Test
    fun `another app holding the mic disarms`() {
        val busy = ready.copy(micBusyElsewhere = true)
        assertFalse(shouldArm(busy, rules))
        assertEquals(DisarmedReason.MIC_BUSY, disarmedReason(busy, rules))
    }

    @Test
    fun `every disarmed input reports a reason, and every armed one reports none`() {
        // The two functions must never disagree: a paused notification with no
        // explanation is how a user decides the feature is broken.
        val all = listOf(true, false)
        for (enabled in all) for (glasses in all) for (charging in all) {
            for (call in all) for (busy in all) for (battery in listOf(5, 80)) {
                val inputs = ArmingInputs(enabled, glasses, battery, charging, call, busy)
                val armed = shouldArm(inputs, rules)
                assertEquals(armed, disarmedReason(inputs, rules) == null)
            }
        }
    }
}
