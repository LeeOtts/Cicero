package com.leeotts.cicero.ai

import android.app.Application
import android.util.Log
import com.leeotts.cicero.CiceroApp
import com.leeotts.cicero.TAG
import com.leeotts.cicero.audio.Speaker
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.Role
import com.leeotts.cicero.tools.ToolRegistry
import kotlinx.coroutines.flow.first

/**
 * One complete assistant turn, from a question to a spoken, logged answer.
 *
 * Extracted from AssistantViewModel so that a turn no longer needs a ViewModel
 * to run in. The wake-word foreground service has no ViewModel and no Activity,
 * and duplicating this logic there would have left two copies of the routing,
 * fallback and logging rules to drift apart - the tool list alone is a cached
 * prompt prefix whose order matters, and it must be built identically wherever
 * a turn starts.
 *
 * Deliberately knows nothing about the UI. The caller owns the [Speaker] (so it
 * can be shut down with whatever outlives it) and decides what to do with the
 * [Result]; this class owns only the conversation history, because that is what
 * makes a follow-up work and it belongs to the turn sequence rather than to any
 * screen.
 */
class TurnRunner(
    private val app: Application,
    private val speaker: Speaker,
    /**
     * How long a thread stays open for follow-ups.
     *
     * The Ask screen passes no value: its history is cleared explicitly, by the
     * user starting a new conversation. The wake word passes a few minutes, so
     * that "what about the one on the left" works but a question asked an hour
     * later does not silently inherit whatever was in front of the glasses then.
     */
    private val historyTtlMs: Long = Long.MAX_VALUE,
) {

    /** What a finished turn produced, for whatever wants to display it. */
    data class Result(
        val question: String,
        val answer: String,
        val brainName: String,
        /** Set when [answer] is a failure message rather than a real reply. */
        val isError: Boolean,
    )

    private val cicero = app as CiceroApp
    private val settings = BrainSettings(app)
    private val repository = ConversationRepository(app)

    /** In-memory history for follow-ups within the current thread. */
    private var history: List<Msg> = emptyList()
    private var lastTurnAt = 0L

    /** Starts a fresh thread; the next question carries no prior context. */
    fun clearHistory() {
        history = emptyList()
        lastTurnAt = 0L
    }

    /**
     * Runs one turn to completion: routes it, asks the model, speaks the answer
     * and writes the thread to the log.
     *
     * Never throws for an ordinary failure. A model that cannot be reached
     * comes back as a [Result] whose answer is already phrased for a human and
     * has already been spoken, because silence is the worst outcome when the
     * phone is in a pocket.
     */
    suspend fun run(question: String): Result {
        expireStaleHistory()

        // Once per turn: the answer, its title and any transcription all read
        // the address off this one config.
        val cfg = settings.config.first().withResolvedLocalUrl(app)
        val routing = Router.route(cfg, question, history.size)
        var brain = BrainFactory.brain(cfg, routing.target)
        val now = System.currentTimeMillis()

        // Threaded on the provider the USER chose, never the one routing
        // picked: routing changes model turn to turn, and keying on that would
        // split one conversation into a string of one-turn threads.
        val conversationId = repository.conversationFor(now, cfg.providerId)
        val isFirstTurn = history.isEmpty()
        repository.addTurn(conversationId, Role.USER, question, now = now)

        val priorMessages = history.size
        var failed = false
        var photo: ByteArray? = null
        var newMessages: List<Msg> = emptyList()
        val answer = try {
            val result = try {
                assistantFor(cfg, brain).ask(text = routing.text, priorHistory = history)
            } catch (e: BrainException) {
                // Routing chose this model, not the user, so its failure must
                // not become the user's problem. One retry against their own
                // choice - there is no HTTP retry layer to lean on.
                val fallback = cfg.defaultTarget()
                if (!cfg.routingEnabled || fallback == routing.target) throw e
                Log.w(TAG, "routed brain ${brain.id} failed; falling back", e)
                brain = BrainFactory.brain(cfg, fallback)
                assistantFor(cfg, brain).ask(text = routing.text, priorHistory = history)
            }
            history = result.history
            newMessages = result.history.drop(priorMessages)
            photo = result.photo?.bytes
            result.spoken
        } catch (e: BrainException) {
            failed = true
            // The cache is keyed on the network, which cannot notice a server
            // going down while the phone stays put. Re-probe next turn.
            LocalEndpoint.invalidate()
            e.spokenMessage // already phrased for a human
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "ask failed", e)
            "Something went wrong: ${e.message}"
        }

        // Spoken before the database writes so the glasses answer at the speed
        // of the model, not the speed of Room. Failures are spoken too -
        // silence is the worst outcome when the phone is in a pocket.
        speaker.speak(answer)
        lastTurnAt = System.currentTimeMillis()

        // Logged before the answer so the thread reads in the order things
        // happened.
        recordToolTurns(conversationId, newMessages, brain.id)

        repository.addTurn(
            conversationId,
            Role.ASSISTANT,
            answer,
            photoJpeg = photo,
            brainId = brain.id,
            now = System.currentTimeMillis(),
        )
        if (isFirstTurn) {
            repository.ensureTitled(
                conversationId,
                BrainFactory.brainFor(cfg, TaskRole.TITLE),
                question,
            )
        }

        return Result(question, answer, brain.displayName, isError = failed)
    }

    /**
     * Drops a thread nobody has added to in a while.
     *
     * Only ever true for a caller that asked for a TTL. Checked on the way in
     * rather than on a timer, because a timer would be a wakeup and this class
     * runs inside an always-on service.
     */
    private fun expireStaleHistory() {
        if (historyTtlMs == Long.MAX_VALUE || lastTurnAt == 0L) return
        if (System.currentTimeMillis() - lastTurnAt > historyTtlMs) clearHistory()
    }

    /** Everything a turn needs around one brain, so a fallback can rebuild it. */
    private fun assistantFor(cfg: BrainConfig, brain: Brain) = Assistant(
        brain = brain,
        transcriber = BrainFactory.transcriber(cfg, brain),
        tools = ToolRegistry.build(
            context = app,
            repository = repository,
            glasses = cicero.glasses,
            location = cicero.location,
            destinations = cicero.destinations,
            brain = brain,
            nest = cicero.nest.gateway,
        ),
    )

    /**
     * Writes one TOOL turn per tool result, so a thread records what the
     * assistant actually did rather than only what it said - which is how the
     * backends get compared on tool reliability.
     *
     * Reconstructed from the history [Assistant] returns rather than written by
     * [Assistant] itself, which deliberately knows nothing about the data layer.
     * Timestamps are stepped because several tools can run inside one
     * millisecond.
     */
    private suspend fun recordToolTurns(
        conversationId: Long,
        messages: List<Msg>,
        brainId: String,
    ) {
        val results = messages.filterIsInstance<Msg.ToolResult>()
        if (results.isEmpty()) return

        val arguments = messages.filterIsInstance<Msg.Assistant>()
            .flatMap { it.toolCalls }
            .associate { it.id to it.arguments.toString() }

        var stamp = System.currentTimeMillis()
        results.forEach { result ->
            repository.addTurn(
                conversationId,
                Role.TOOL,
                text = "${result.name}: ${result.content}".take(TOOL_TEXT_LIMIT),
                toolCallsJson = arguments[result.callId],
                brainId = brainId,
                now = stamp++,
            )
        }
    }

    private companion object {
        /** Tool output can be long; the thread shows enough to audit the call. */
        const val TOOL_TEXT_LIMIT = 500
    }
}
