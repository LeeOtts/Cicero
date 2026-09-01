package com.leeotts.cicero

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ai.BrainFactory
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.ModelCatalog
import com.leeotts.cicero.ai.ModelList
import com.leeotts.cicero.ai.Provider
import com.leeotts.cicero.ai.Providers
import com.leeotts.cicero.ai.TurnRunner
import com.leeotts.cicero.ai.withResolvedLocalUrl
import com.leeotts.cicero.ai.oauth.OAuthResult
import com.leeotts.cicero.ai.oauth.OpenRouterAuth
import com.leeotts.cicero.audio.Speaker
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.DeletedConversation
import com.leeotts.cicero.data.Turn
import com.leeotts.cicero.home.NestConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One exchange, as shown in the live transcript. */
data class Exchange(
    val question: String,
    val answer: String,
    val backend: String,
    /** Set when [answer] is a failure message rather than a real reply. */
    val isError: Boolean = false,
)

/** Outcome of a Settings "Test connection" run. */
sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val detail: String) : TestResult
    data class Failed(val message: String) : TestResult
}

/** How far through an OpenRouter sign-in the user is. */
sealed interface OAuthState {
    data object Idle : OAuthState

    /** The browser has the foreground. There is no callback for giving up here. */
    data object Waiting : OAuthState
    data object Exchanging : OAuthState
    data class Failed(val message: String) : OAuthState
}

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = BrainSettings(app)
    private val repository = ConversationRepository(app)
    private val nest = getApplication<CiceroApp>().nest
    private val speaker = Speaker(app)

    /**
     * Shared with the wake-word service, which runs the same turn with no
     * ViewModel to hang it on. Everything about how a question is routed,
     * answered and logged lives there; this class keeps only what the screen
     * needs to draw.
     */
    private val runner = TurnRunner(app, speaker)
    private val auth = OpenRouterAuth(settings)

    init {
        // Replayed, because the app is often killed while the browser is in
        // front: the callback activity can run before this ViewModel exists.
        viewModelScope.launch { OAuthResult.codes.collect(::onAuthCode) }
    }

    /** True while an answer is being read aloud. */
    val speaking: StateFlow<Boolean> = speaker.speaking

    val config: StateFlow<BrainConfig> = settings.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrainConfig())

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the drawer shows under RECENT. */
    val recentConversations: StateFlow<List<Conversation>> = conversations
        .map { it.take(RECENT_LIMIT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Turns for one thread. Deliberately not a StateFlow: which thread is open is
     * a navigation fact, so it lives in the back stack and survives process death
     * without this ViewModel tracking it.
     */
    fun turns(conversationId: Long): Flow<List<Turn>> = repository.observeTurns(conversationId)

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Snackbar traffic - deletions and their undo. Collected by the app shell. */
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    val nestConfig: StateFlow<NestConfig> = nest.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, NestConfig())

    /**
     * Its own slot rather than sharing [testResult]: the two Test buttons sit on
     * the same screen, and one answer landing under the other button is worse
     * than no answer at all.
     */
    private val _nestTestResult = MutableStateFlow<TestResult?>(null)
    val nestTestResult: StateFlow<TestResult?> = _nestTestResult.asStateFlow()

    /**
     * One probe per provider, because the routing section shows several pickers
     * at once and each is looking at a different endpoint.
     */
    private val probes = mutableMapOf<String, ModelProbe>()
    private val whisperProbe = ModelProbe()

    val whisperModels: StateFlow<ModelList> = whisperProbe.state.asStateFlow()

    private val _oauth = MutableStateFlow<OAuthState>(OAuthState.Idle)
    val oauth: StateFlow<OAuthState> = _oauth.asStateFlow()

    fun update(transform: (BrainConfig) -> BrainConfig) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun updateNest(transform: (NestConfig) -> NestConfig) {
        viewModelScope.launch { nest.update(transform) }
    }

    fun testNest() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _nestTestResult.value = TestResult.Running
            _nestTestResult.value = nest.test().fold(
                onSuccess = { TestResult.Ok("Connected — $it") },
                onFailure = { TestResult.Failed("Failed — ${it.message}") },
            )
            _busy.value = false
        }
    }

    /**
     * One endpoint's model list, for a Settings picker.
     *
     * Cached per url: a picker asks every time it opens, and re-listing on each
     * tap would put a network call behind a dropdown arrow. [force] is the
     * explicit Refresh, for when a model was loaded since the last look.
     */
    private inner class ModelProbe {
        val state = MutableStateFlow<ModelList>(ModelList.Idle)
        private var probed: String? = null

        fun load(provider: Provider, url: String, apiKey: String, force: Boolean) {
            if (url.isBlank() || state.value == ModelList.Loading) return
            // Keyed on both, because the self-hosted entry can be re-pointed.
            val key = "${provider.id}@$url"
            if (!force && key == probed && state.value is ModelList.Loaded) return

            probed = key
            viewModelScope.launch {
                state.value = ModelList.Loading
                state.value = ModelCatalog.list(provider, url, apiKey).fold(
                    onSuccess = { ModelList.Loaded(it) },
                    onFailure = { ModelList.Failed(it.message ?: "Couldn't reach the server.") },
                )
            }
        }
    }

    /** The model list for one provider's picker. */
    fun models(providerId: String): StateFlow<ModelList> =
        probes.getOrPut(providerId) { ModelProbe() }.state.asStateFlow()

    fun loadModels(providerId: String, force: Boolean = false) {
        val cfg = config.value
        val provider = Providers.byId(providerId)
        probes.getOrPut(providerId) { ModelProbe() }
            .load(provider, cfg.baseUrlFor(provider), cfg.keyFor(provider.id), force)
    }

    /** The Whisper endpoint takes no key of its own today, hence the blank one. */
    fun loadWhisperModels(force: Boolean = false) {
        whisperProbe.load(Providers.LOCAL, config.value.speechUrl, apiKey = "", force = force)
    }

    // --- OpenRouter sign-in -------------------------------------------------

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            _oauth.value = OAuthState.Waiting
            runCatching { auth.begin(activity) }.onFailure {
                _oauth.value = OAuthState.Failed(it.message ?: "Could not open a browser.")
            }
        }
    }

    /**
     * A Custom Tab has no cancel callback - backing out of it delivers nothing
     * at all. So Settings reports when it returns to the front, and a sign-in
     * still marked [OAuthState.Waiting] is one the user walked away from.
     */
    fun oauthResumed() {
        if (_oauth.value != OAuthState.Waiting) return
        // A code may already be delivered and not yet collected. That is not a
        // cancel, and clearing the verifier here would break the exchange.
        if (OAuthResult.codes.replayCache.isNotEmpty()) return
        _oauth.value = OAuthState.Idle
        viewModelScope.launch { settings.clearPendingAuth() }
    }

    private suspend fun onAuthCode(code: String?) {
        OAuthResult.clear()
        if (code == null) {
            settings.clearPendingAuth()
            if (_oauth.value == OAuthState.Waiting) {
                _oauth.value = OAuthState.Failed("Sign-in was cancelled.")
            }
            return
        }
        _oauth.value = OAuthState.Exchanging
        _oauth.value = runCatching { auth.complete(code) }.fold(
            onSuccess = { key ->
                settings.update { cfg ->
                    cfg.copy(
                        providerId = Providers.OPENROUTER.id,
                        keys = cfg.keys + (Providers.OPENROUTER.id to key),
                    )
                }
                OAuthState.Idle
            },
            onFailure = { OAuthState.Failed(it.message ?: "Sign-in failed.") },
        )
    }

    fun testConnection() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _testResult.value = TestResult.Running
            // Resolved first, so the button reports on the address a question
            // would actually use rather than the one merely typed in.
            val cfg = config.value.withResolvedLocalUrl(getApplication())
            _testResult.value = BrainFactory.brain(cfg).testConnection().fold(
                onSuccess = { TestResult.Ok("Connected — $it") },
                onFailure = { TestResult.Failed("Failed — ${it.message}") },
            )
            _busy.value = false
        }
    }

    fun ask(question: String) {
        if (_busy.value || question.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            val result = runner.run(question)
            _exchanges.value = _exchanges.value + Exchange(
                result.question,
                result.answer,
                result.brainName,
                isError = result.isError,
            )
            _busy.value = false
        }
    }

    private companion object {
        const val RECENT_LIMIT = 6
    }

    /** Starts a fresh thread; the next question carries no prior context. */
    fun clearConversation() {
        runner.clearHistory()
        _exchanges.value = emptyList()
    }

    /**
     * Deletes one thread, with undo.
     *
     * The live transcript is left alone: it is the conversation in progress, not
     * the stored log, and clearing it because an old thread was deleted would
     * lose context the user never asked to drop.
     */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val deleted = repository.deleteConversation(id) ?: return@launch
            _messages.send(
                UiMessage(
                    text = getApplication<Application>().getString(R.string.history_deleted),
                    actionLabel = getApplication<Application>().getString(R.string.action_undo),
                    action = { restoreConversation(deleted) },
                ),
            )
        }
    }

    private fun restoreConversation(deleted: DeletedConversation) {
        viewModelScope.launch { repository.restoreConversation(deleted) }
    }

    /**
     * Drops every stored thread. Not undoable, so the caller must confirm first.
     *
     * The live transcript goes too - leaving it visible after wiping the log
     * would be the one copy of a conversation the user just deleted.
     */
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            clearConversation()
            _messages.send(
                UiMessage(text = getApplication<Application>().getString(R.string.history_cleared)),
            )
        }
    }
}
