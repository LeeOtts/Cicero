package com.leeotts.cicero.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the whole pipeline with fakes. No microphone, no JNI, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeCoordinatorTest {

    /** A frame whose first sample is this value makes the fake detector fire. */
    private val trigger: Short = 777

    /**
     * Lets the coordinator run.
     *
     * runCurrent first is load-bearing: work launched into backgroundScope is
     * not scheduled by advanceUntilIdle on its own, so without it the loop
     * never starts and every assertion here passes vacuously against a
     * coordinator that did nothing.
     */
    private fun TestScope.pump() {
        runCurrent()
        advanceUntilIdle()
    }

    private class FakeSource : AudioSource {
        var opens = 0
        var closes = 0
        val open get() = opens > closes
        var blocks: MutableList<ShortArray> = mutableListOf()

        var reads = 0

        /** Lets a test disarm partway through, from inside the read loop. */
        var onRead: (Int) -> Unit = {}
        override val description = "fake microphone"
        override fun open(): Boolean { opens++; return true }
        override fun read(into: ShortArray): Int {
            if (!open) return -1
            val next = blocks.removeFirstOrNull() ?: return -1
            next.copyInto(into)
            onRead(++reads)
            return next.size
        }
        override fun close() { if (open) closes++ }
    }

    private class FakeDetector(private val trigger: Short) : WakeDetector {
        override val frameLength = 4
        override val sampleRate = 16_000
        var closed = false
        override fun process(frame: ShortArray) = if (frame[0] == trigger) 0 else -1
        override fun close() { closed = true }
    }

    private class FakeFeedback : WakeFeedback {
        var earcons = 0
        var stops = 0
        val order = mutableListOf<String>()
        private val _speaking = MutableStateFlow(false)
        override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
        override fun earcon() { earcons++; order += "earcon" }
        override fun haptic() {}
        override fun stopSpeaking() { stops++; order += "stop" }
    }

    private fun block(vararg first: Short) = ShortArray(8).also { arr ->
        first.forEachIndexed { i, v -> arr[i * 4] = v }
    }

    /** Two frames per block; `trigger` in slot 0 fires on the first frame. */
    private fun quiet() = block(1, 1)
    private fun wake() = block(trigger, 1)

    private class Harness(
        val dispatcher: CoroutineDispatcher,
        val exclusiveMic: Boolean = true,
    ) {
        val source = FakeSource()
        val detector = FakeDetector(777)
        val feedback = FakeFeedback()
        var captured: String? = "what am I looking at"
        var captureCalls = 0
        var cancels = 0
        val questions = mutableListOf<String>()
        var turnThrows = false

        val coordinator = WakeCoordinator(
            sources = object : AudioSources {
                override fun create(source: MicSource) = this@Harness.source
            },
            detectors = object : WakeDetectors {
                override fun create(accessKey: String, sensitivity: Float) =
                    Result.success<WakeDetector>(detector)
            },
            captures = object : UtteranceCaptures {
                override fun create(mic: MicSource, source: AudioSource) =
                    object : UtteranceCapture {
                        override val needsExclusiveMic = exclusiveMic
                        override suspend fun capture(): String? {
                            captureCalls++
                            return captured
                        }
                        override fun cancel() { cancels++ }
                    }
            },
            turns = object : TurnSink {
                override suspend fun run(question: String) {
                    questions += question
                    if (turnThrows) throw IllegalStateException("model exploded")
                }
            },
            feedback = feedback,
            dispatcher = dispatcher,
        )
    }

    private fun sessions() =
        MutableStateFlow<WakeCredentials?>(WakeCredentials("key", 0.5f, MicSource.PHONE))

    @Test
    fun `a wake word runs exactly one turn`() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.source.blocks = mutableListOf(quiet(), wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(listOf("what am I looking at"), h.questions)
        assertEquals(1, h.captureCalls)
    }

    @Test
    fun `the microphone is released for the capture and reopened after`() = runTest {
        // The recognizer cannot have the microphone while the detector holds
        // it. Both opens matter: without the second, the wake word is deaf
        // forever after the first question.
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.source.blocks = mutableListOf(wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(2, h.source.opens)
    }

    @Test
    fun `disarming closes the microphone - the battery contract`() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        val session = sessions()

        // Plenty of audio left when the disarm lands, so a closed microphone
        // can only mean the session was actually cancelled - not that the fake
        // simply ran dry. This is the path that matters: without it the mic
        // stays open until the loop happens to finish, which is never.
        h.source.blocks = MutableList(500) { quiet() }
        h.source.onRead = { n -> if (n == 5) session.value = null }

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertFalse("the microphone must actually close", h.source.open)
        assertEquals(WakeCoordinator.State.DISARMED, h.coordinator.state.value)
        assertEquals("it must stop reading, not drain the source", 5, h.source.reads)
    }

    @Test
    fun `a capture that reads the live stream keeps the microphone open`() = runTest {
        // The glasses path has no recognizer to hand off to - it reads straight
        // past the wake word. Closing the source there would throw away the
        // start of the question, so the handover must be conditional.
        val h = Harness(StandardTestDispatcher(testScheduler), exclusiveMic = false)
        h.source.blocks = mutableListOf(wake(), quiet())

        backgroundScope.launch { h.coordinator.run(sessions()) }
        pump()

        assertEquals("the microphone must not be handed over", 1, h.source.opens)
        assertEquals(listOf("what am I looking at"), h.questions)
    }

    @Test
    fun `nothing said runs no turn and keeps listening`() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.captured = null
        h.source.blocks = mutableListOf(wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertTrue(h.questions.isEmpty())
        assertEquals(2, h.source.opens)
    }

    @Test
    fun `barge-in stops the answer before the earcon sounds`() = runTest {
        // Heard together they are just noise; the stop has to come first.
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.source.blocks = mutableListOf(wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(listOf("stop", "earcon"), h.feedback.order)
    }

    @Test
    fun `a failing turn does not kill the loop`() = runTest {
        // Otherwise the service stays alive but permanently deaf, which is the
        // worst possible failure: the notification still says it is listening.
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.turnThrows = true
        h.source.blocks = mutableListOf(wake(), quiet(), wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(2, h.questions.size)
    }

    @Test
    fun `the capture is always cancelled, so the recognizer never leaks`() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.source.blocks = mutableListOf(wake(), quiet())
        val session = sessions()

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(1, h.cancels)
    }

    @Test
    fun `it starts disarmed and opens nothing until armed`() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        h.source.blocks = MutableList(100) { quiet() }
        val session = MutableStateFlow<WakeCredentials?>(null)

        backgroundScope.launch { h.coordinator.run(session) }
        pump()

        assertEquals(0, h.source.opens)
        assertEquals(WakeCoordinator.State.DISARMED, h.coordinator.state.value)
    }

    @Test
    fun `an engine that will not start reports instead of crashing`() = runTest {
        // A bad access key or an expired keyword must be readable text, not an
        // exception inside a foreground service.
        var reported: String? = null
        val coordinator = WakeCoordinator(
            sources = object : AudioSources {
                override fun create(source: MicSource) = FakeSource()
            },
            detectors = object : WakeDetectors {
                override fun create(accessKey: String, sensitivity: Float) =
                    Result.failure<WakeDetector>(IllegalStateException("invalid access key"))
            },
            captures = object : UtteranceCaptures {
                override fun create(mic: MicSource, source: AudioSource) =
                    object : UtteranceCapture {
                        override val needsExclusiveMic = true
                        override suspend fun capture(): String? = null
                        override fun cancel() {}
                    }
            },
            turns = object : TurnSink {
                override suspend fun run(question: String) = Unit
            },
            feedback = FakeFeedback(),
            dispatcher = StandardTestDispatcher(testScheduler),
            onError = { reported = it },
        )

        backgroundScope.launch { coordinator.run(sessions()) }
        pump()

        assertEquals("invalid access key", reported)
    }
}
