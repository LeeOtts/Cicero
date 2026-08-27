package com.leeotts.cicero

import com.leeotts.cicero.ai.Assistant
import com.leeotts.cicero.ai.OpenAiCompatibleBrain
import com.leeotts.cicero.ai.NoOpTranscriber
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.tools.Schemas
import com.leeotts.cicero.tools.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against a real LM Studio server on the local network.
 *
 * Skips itself when the server is unreachable, so it never breaks a run on a
 * machine without it. Uses a *recording* alarm tool rather than the real one, so
 * the test verifies the loop without firing a genuine alarm.
 */
class LocalLlmIntegrationTest {

    private val baseUrl = "http://10.0.0.5:1234"
    private val model = "google/gemma-4-e4b"

    private fun brain(vision: Boolean = false) =
        OpenAiCompatibleBrain(baseUrl = baseUrl, model = model, supportsVision = vision)

    private fun reachable(): Boolean = runCatching {
        runBlocking { brain().testConnection().isSuccess }
    }.getOrDefault(false)

    /** Records what the model asked for instead of setting a real alarm. */
    private class RecordingAlarmTool : Tool {
        var lastHour: Int? = null
        var lastMinute: Int? = null
        var callCount = 0

        override val spec = ToolSpec(
            name = "set_alarm",
            description = "Set an alarm at a specific clock time. Use 24-hour time.",
            parameters = Schemas.obj(
                "hour" to Schemas.integer("Hour, 0 to 23"),
                "minute" to Schemas.integer("Minute, 0 to 59"),
                required = listOf("hour"),
            ),
        )

        override suspend fun run(arguments: JsonObject): ToolOutcome {
            callCount++
            lastHour = arguments.int("hour")
            lastMinute = arguments.int("minute")
            return ToolOutcome("Alarm set.")
        }
    }

    @Test
    fun connectsAndReportsTheLoadedModel() = runBlocking {
        assumeTrue("LM Studio not reachable at $baseUrl", reachable())

        val result = brain().testConnection()

        assertTrue(result.isSuccess)
        assertTrue(
            "should confirm the model is loaded, got: ${result.getOrNull()}",
            result.getOrNull().orEmpty().contains(model),
        )
    }

    @Test
    fun answersAPlainQuestion() = runBlocking {
        assumeTrue("LM Studio not reachable at $baseUrl", reachable())

        val result = Assistant(
            brain = brain(),
            transcriber = NoOpTranscriber,
        ).ask(text = "Reply with exactly the word: ready")

        assertNotNull(result.spoken)
        assertTrue("empty answer", result.spoken.isNotBlank())
    }

    /**
     * The one that matters: a spoken-style request must reach the tool with the
     * right arguments, through the same loop the app uses.
     */
    @Test
    fun routesASpokenRequestToTheRightTool() = runBlocking {
        assumeTrue("LM Studio not reachable at $baseUrl", reachable())

        val alarm = RecordingAlarmTool()
        val result = Assistant(
            brain = brain(),
            transcriber = NoOpTranscriber,
            tools = listOf(alarm),
        ).ask(text = "set an alarm for 7am")

        assertEquals("tool should have been called once", 1, alarm.callCount)
        assertEquals("should be 7 in 24-hour time", 7, alarm.lastHour)
        // A model may omit minute entirely; both null and 0 are correct.
        assertTrue("minute should be 0 or absent", alarm.lastMinute == null || alarm.lastMinute == 0)
        assertTrue("should still speak an answer", result.spoken.isNotBlank())
    }

    /** A backend told it cannot use tools must not be offered any. */
    @Test
    fun respectsToolsDisabled() = runBlocking {
        assumeTrue("LM Studio not reachable at $baseUrl", reachable())

        val alarm = RecordingAlarmTool()
        Assistant(
            brain = OpenAiCompatibleBrain(
                baseUrl = baseUrl, model = model, supportsTools = false,
            ),
            transcriber = NoOpTranscriber,
            tools = listOf(alarm),
        ).ask(text = "set an alarm for 7am")

        assertEquals("no tool should run when tools are disabled", 0, alarm.callCount)
    }
}
