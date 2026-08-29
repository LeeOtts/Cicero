package com.leeotts.cicero.ai

/**
 * What kind of turn this is, and therefore which model should answer it.
 *
 * Three, not four. Vision is deliberately NOT a role - see [Router.route].
 */
enum class TaskRole {
    /** Short commands and lookups: "set a timer for ten", "what time is it". */
    FAST,

    /** Reasoning, comparison, planning, open questions. */
    DEEP,

    /** Naming a thread. Background work the user never waits on. */
    TITLE,
}

/** A routing decision: who answers, and what they are asked. */
data class Routing(
    val target: Target,
    /** The question with any explicit override phrase removed. */
    val text: String,
    val role: TaskRole,
)

/**
 * Picks the backend for one turn from the words alone.
 *
 * There is no classifier model call, and there must not be one. This app has no
 * streaming and no retry, and it speaks its answers - a second round trip before
 * the first token would be paid on every single turn, on the weakest part of the
 * experience.
 *
 * It will misroute. That is acceptable, because the cost of a misroute is a
 * slightly slower or slightly shallower answer and never a missing capability:
 * [Assistant] and [ToolRegistry] still gate tools, vision and web search on the
 * chosen brain's own flags, whatever this decides.
 */
object Router {

    fun route(config: BrainConfig, text: String, historyDepth: Int): Routing {
        if (!config.routingEnabled) {
            return Routing(config.defaultTarget(), text, TaskRole.FAST)
        }

        // "ask Claude what the weather is" beats any heuristic, so it wins first.
        overrideIn(text)?.let { (provider, rest) ->
            return Routing(
                target = Target(provider.id, config.modelFor(provider)),
                text = rest,
                role = TaskRole.DEEP,
            )
        }

        val role = roleFor(text, historyDepth)
        var target = config.targetFor(role)

        // Vision is a constraint, not a role. We cannot know a turn needs the
        // camera until the model asks for `look`, and there is no round in which
        // to re-decide - one brain answers one turn, because each adapter encodes
        // images differently. So if the wording is camera-shaped and the routed
        // provider is blind, fall back to one that can see: ToolRegistry drops
        // LookTool entirely for a blind backend, and routing must never be the
        // reason the glasses stop working.
        if (looksVisual(text) && !target.provider.vision) {
            val fallback = config.defaultTarget()
            if (fallback.provider.vision) target = fallback
        }

        return Routing(target, text, role)
    }

    /** Exposed for testing; [route] is the real entry point. */
    fun roleFor(text: String?, historyDepth: Int): TaskRole {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isBlank()) return TaskRole.FAST
        // Anything this long is being thought about, not barked.
        if (t.split(WHITESPACE).size > LONG_QUESTION_WORDS) return TaskRole.DEEP
        // A thread that has run this many turns is a hard one by revealed preference.
        if (historyDepth >= DEEP_THREAD_TURNS) return TaskRole.DEEP
        if (DEEP_MARKERS.any { it in t }) return TaskRole.DEEP
        return TaskRole.FAST
    }

    /** True when the wording suggests the camera is the point of the question. */
    fun looksVisual(text: String?): Boolean {
        val t = text?.trim()?.lowercase().orEmpty()
        return VISION_MARKERS.any { it in t }
    }

    /** Finds a leading "ask <provider>" / "use <provider>" and strips it. */
    private fun overrideIn(text: String): Pair<Provider, String>? {
        val trimmed = text.trimStart()
        val lower = trimmed.lowercase()
        for (verb in OVERRIDE_VERBS) {
            if (!lower.startsWith(verb)) continue
            val rest = trimmed.substring(verb.length).trimStart()
            val provider = Providers.all.firstOrNull { p ->
                rest.lowercase().startsWith(p.displayName.lowercase())
            } ?: continue
            val remainder = rest.substring(provider.displayName.length)
                // "ask Claude, what's the weather" and "ask Claude what's..."
                .trimStart(' ', ',', ':', '-')
            if (remainder.isBlank()) continue
            return provider to remainder
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")

    /** Past this, the user is asking rather than instructing. */
    private const val LONG_QUESTION_WORDS = 25
    private const val DEEP_THREAD_TURNS = 6

    private val OVERRIDE_VERBS = listOf("ask ", "use ")

    private val DEEP_MARKERS = listOf(
        "why ", "compare", "explain", "pros and cons", "step by step",
        "how do i", "how would i", "which should", "should i", "plan ",
        "work out", "calculate", "trade-off", "tradeoff", "difference between",
    )

    private val VISION_MARKERS = listOf(
        "look at", "looking at", "what is this", "what's this", "read this",
        "what does this say", "in front of me", "this sign", "this label",
        "what colour", "what color", "see this", "am i holding",
    )
}
