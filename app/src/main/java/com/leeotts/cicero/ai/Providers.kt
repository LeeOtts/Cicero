package com.leeotts.cicero.ai

/**
 * Which request/response shape a provider speaks. Three, and only three - every
 * hosted provider worth reaching either invented one of these or copied OpenAI.
 */
enum class Wire { OPENAI, ANTHROPIC, GEMINI }

/** How the key is presented. Shared by [Brain] requests and [ModelCatalog]. */
enum class AuthStyle {
    BEARER, X_API_KEY, GOOG_API_KEY;

    fun headers(key: String): Map<String, String> = when {
        key.isBlank() -> emptyMap()
        this == BEARER -> mapOf("Authorization" to "Bearer $key")
        // Anthropic rejects a request without the version header, so it travels
        // with the key rather than being remembered separately at each call site.
        this == X_API_KEY -> mapOf(
            "x-api-key" to key,
            "anthropic-version" to ClaudeBrain.API_VERSION,
        )
        else -> mapOf("x-goog-api-key" to key)
    }
}

/** How this provider lists the models it will accept. */
enum class Discovery { OPENAI, ANTHROPIC, GEMINI, OPENROUTER }

/**
 * One backend the user can point Cicero at.
 *
 * This is deliberately data rather than code. Adding a provider means adding a
 * line to [Providers.all] and nothing else - if a `when (provider)` ever appears
 * anywhere, that is the signal this design has been broken.
 */
data class Provider(
    /**
     * Stable and persisted: this string is written into `Conversation.brainId`
     * and `Turn.brainId`. The three legacy values - "gemini", "claude" and
     * "openai-compatible" - predate this catalog and MUST NOT be renamed, or
     * every existing history row orphans and the threading rule mis-fires on the
     * first turn after upgrade.
     */
    val id: String,
    /** A proper noun, so deliberately not a string resource. */
    val displayName: String,
    val wire: Wire,
    /**
     * Everything *before* "/v1" - [OpenAiCompatibleBrain] appends that itself and
     * tolerates either form.
     */
    val baseUrl: String,
    val auth: AuthStyle,
    /**
     * A starting point, not a promise. Model ids churn faster than this file can
     * track, which is why every provider also supports discovery: the picker
     * corrects this the first time Settings is opened. Tests assert that it is
     * non-blank, never that it is any particular string.
     */
    val defaultModel: String,
    /** Where a human goes to get a key. Opened from Settings. */
    val signupUrl: String,
    val discovery: Discovery,
    val vision: Boolean = true,
    val tools: Boolean = true,
    val acceptsAudio: Boolean = false,
    /** Server-side search, resolved inside one request. See [Brain.supportsWebSearch]. */
    val webSearch: Boolean = false,
    /** Only the self-hosted entry lets the user type an address. */
    val userEditableUrl: Boolean = false,
    /** Only the self-hosted entry has capabilities we cannot know in advance. */
    val userEditableCaps: Boolean = false,
    /** True where the user can sign in rather than paste a key. OpenRouter only. */
    val oauth: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
)

object Providers {

    /**
     * The headline path. One sign-in reaches every model below and several
     * hundred more, because consumer subscriptions cannot legitimately authorise
     * a third-party app: Anthropic forbids it outright, Google closed the same
     * door on Gemini CLI, and the OpenAI equivalent is unsanctioned. OpenRouter
     * OAuth is the only flow that hands this app a real, revocable key.
     */
    val OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        wire = Wire.OPENAI,
        baseUrl = "https://openrouter.ai/api",
        auth = AuthStyle.BEARER,
        // OpenRouter picks a model per request. A better default than any single
        // id, and it cannot go stale.
        defaultModel = "openrouter/auto",
        signupUrl = "https://openrouter.ai/keys",
        discovery = Discovery.OPENROUTER,
        oauth = true,
        // Attribution, used for the public OpenRouter leaderboards.
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://github.com/leeotts/cicero",
            "X-Title" to "Cicero",
        ),
    )

    val GEMINI = Provider(
        id = "gemini",
        displayName = "Gemini",
        wire = Wire.GEMINI,
        baseUrl = GeminiBrain.BASE,
        auth = AuthStyle.GOOG_API_KEY,
        defaultModel = GeminiBrain.DEFAULT_MODEL,
        signupUrl = "https://aistudio.google.com/apikey",
        discovery = Discovery.GEMINI,
        acceptsAudio = true,
        webSearch = true,
    )

    val CLAUDE = Provider(
        id = "claude",
        displayName = "Claude",
        wire = Wire.ANTHROPIC,
        baseUrl = ClaudeBrain.BASE,
        auth = AuthStyle.X_API_KEY,
        defaultModel = ClaudeBrain.DEFAULT_MODEL,
        signupUrl = "https://console.anthropic.com/settings/keys",
        discovery = Discovery.ANTHROPIC,
        webSearch = true,
    )

    val OPENAI = Provider(
        id = "openai",
        displayName = "OpenAI",
        wire = Wire.OPENAI,
        baseUrl = "https://api.openai.com",
        auth = AuthStyle.BEARER,
        defaultModel = "gpt-5.1",
        signupUrl = "https://platform.openai.com/api-keys",
        discovery = Discovery.OPENAI,
    )

    val XAI = Provider(
        id = "xai",
        displayName = "xAI",
        wire = Wire.OPENAI,
        baseUrl = "https://api.x.ai",
        auth = AuthStyle.BEARER,
        defaultModel = "grok-4",
        signupUrl = "https://console.x.ai",
        discovery = Discovery.OPENAI,
    )

    val DEEPSEEK = Provider(
        id = "deepseek",
        displayName = "DeepSeek",
        wire = Wire.OPENAI,
        baseUrl = "https://api.deepseek.com",
        auth = AuthStyle.BEARER,
        defaultModel = "deepseek-chat",
        signupUrl = "https://platform.deepseek.com/api_keys",
        discovery = Discovery.OPENAI,
        vision = false,
    )

    val GROQ = Provider(
        id = "groq",
        displayName = "Groq",
        wire = Wire.OPENAI,
        // The Groq OpenAI-compatible surface hangs off /openai, so /v1 lands right.
        baseUrl = "https://api.groq.com/openai",
        auth = AuthStyle.BEARER,
        defaultModel = "llama-3.3-70b-versatile",
        signupUrl = "https://console.groq.com/keys",
        discovery = Discovery.OPENAI,
        vision = false,
    )

    val MISTRAL = Provider(
        id = "mistral",
        displayName = "Mistral",
        wire = Wire.OPENAI,
        baseUrl = "https://api.mistral.ai",
        auth = AuthStyle.BEARER,
        defaultModel = "mistral-large-latest",
        signupUrl = "https://console.mistral.ai/api-keys",
        discovery = Discovery.OPENAI,
    )

    /**
     * LM Studio, Ollama, llama.cpp, vLLM - anything self-hosted.
     *
     * The id is the legacy "openai-compatible" and stays that way: history rows
     * written before this catalog existed carry it.
     */
    val LOCAL = Provider(
        id = "openai-compatible",
        displayName = "Local",
        wire = Wire.OPENAI,
        baseUrl = OpenAiCompatibleBrain.LM_STUDIO_DEFAULT,
        auth = AuthStyle.BEARER,
        defaultModel = "",
        signupUrl = "https://lmstudio.ai",
        discovery = Discovery.OPENAI,
        vision = false,
        userEditableUrl = true,
        userEditableCaps = true,
    )

    /** Order here is the order shown in Settings. */
    val all = listOf(OPENROUTER, GEMINI, CLAUDE, OPENAI, XAI, DEEPSEEK, GROQ, MISTRAL, LOCAL)

    val DEFAULT = GEMINI

    /** Unknown ids fall back rather than throw: a stale preference must not brick the app. */
    fun byId(id: String?): Provider = all.firstOrNull { it.id == id } ?: DEFAULT
}
