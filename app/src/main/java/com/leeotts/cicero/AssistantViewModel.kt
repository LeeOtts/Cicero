package com.leeotts.cicero

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.ai.Assistant
import com.leeotts.cicero.ai.Brain
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ai.BrainException
import com.leeotts.cicero.ai.BrainFactory
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.LocalEndpoint
import com.leeotts.cicero.ai.ModelCatalog
import com.leeotts.cicero.ai.ModelList
import com.leeotts.cicero.ai.Msg
import com.leeotts.cicero.ai.Provider
import com.leeotts.cicero.ai.Providers
import com.leeotts.cicero.ai.Router
import com.leeotts.cicero.ai.TaskRole
import com.leeotts.cicero.ai.withResolvedLocalUrl
import com.leeotts.cicero.ai.oauth.OAuthResult
import com.leeotts.cicero.ai.oauth.OpenRouterAuth
import com.leeotts.cicero.audio.Speaker
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.DeletedConversation
import com.leeotts.cicero.data.Role
import com.leeotts.cicero.data.Turn
import com.leeotts.cicero.home.NestConfig
import com.leeotts.cicero.tools.ToolRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
    // Shared with GlassesViewModel and the look tool; see CiceroApp.glasses.
    private val glasses = getApplication<CiceroApp>().glasses
    private val location = getApplication<CiceroApp>().location
    private val destinations = getApplication<CiceroApp>().destinations
    private val nest = getApplication<CiceroApp>().nest
    private val speaker = Speaker(app)
    // Process-scoped, because it describes one microphone; see CiceroApp.voice.
    private val voice = getApplication<CiceroApp>().voice
    private val auth = OpenRouterAuth(settings)

    init {
        // Replayed, because the app is often killed while the browser is in
        // front: the callback activity can run before this ViewModel exists.
        viewModelScope.launch { OAuthResult.codes.collect(::onAuthCode) }

        // What stops the wake word listening to Cicero. The Ask screen does the
        // same for the recognizer's microphone; this is the other half of it.
        viewModelScope.launch { speaker.speaking.collect(voice::holdSpeaker) }

        // The only way a failure to speak can be reported. Everything else the
        // assistant has to say, it says out loud - which is no use at all when
        // the thing that is broken is saying things out loud.
        viewModelScope.launch {
            speaker.problem.filterNotNull().collect { _messages.send(UiMessage(text = it)) }
        }
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

    /** In-memory history for follow-ups within the current thread. */
    private var history: List<Msg> = emptyList()

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
            // Once per turn: the answer, its title and any transcription all
            // read the address off this one config.
            val cfg = config.value.withResolvedLocalUrl(getApplication())
            val routing = Router.route(cfg, question, history.size)
            var brain = BrainFactory.brain(cfg, routing.target)
            val now = System.currentTimeMillis()

            // Threaded on the provider the USER chose, never the one routing
            // picked: routing changes model turn to turn, and keying on that
            // would split one conversation into a string of one-turn threads.
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

            // Spoken before the database writes so the glasses answer at the
            // speed of the model, not the speed of Room. Failures are spoken
            // too - silence is the worst outcome when the phone is in a pocket.
            speaker.speak(answer)

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

            _exchanges.value = _exchanges.value +
                Exchange(question, answer, brain.displayName, isError = failed)
            _busy.value = false
        }
    }

    /** Everything a turn needs around one brain, so a fallback can rebuild it. */
    private fun assistantFor(cfg: BrainConfig, brain: Brain) = Assistant(
        brain = brain,
        transcriber = BrainFactory.transcriber(cfg, brain),
        tools = ToolRegistry.build(
            context = getApplication(),
            repository = repository,
            glasses = glasses,
            location = location,
            destinations = destinations,
            brain = brain,
            nest = nest.gateway,
        ),
        // Through speakCue rather than speak, so the answer flushes whatever
        // is still being said and a lost cue never reaches the snackbar.
        onProgress = speaker::speakCue,
    )

    /** Cuts off an answer mid-sentence, for when it is long or unwanted. */
    fun stopSpeaking() = speaker.stop()

    override fun onCleared() {
        speaker.shutdown()
        // Explicitly, and not as a formality. The collector above dies with the
        // scope, so a ViewModel cleared mid-answer would leave the flag stuck
        // true and the wake word deaf until the process restarted - the same
        // shape of bug as an abandoned listen request, one flag over.
        voice.holdSpeaker(false)
        super.onCleared()
    }

    /**
     * Writes one TOOL turn per tool result, so a thread records what the
     * assistant actually did rather than only what it said - which is how the
     * three backends get compared on tool reliability.
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
        const val RECENT_LIMIT = 6

        /** Tool output can be long; the thread shows enough to audit the call. */
        const val TOOL_TEXT_LIMIT = 500
    }

    /** Starts a fresh thread; the next question carries no prior context. */
    fun clearConversation() {
        history = emptyList()
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
