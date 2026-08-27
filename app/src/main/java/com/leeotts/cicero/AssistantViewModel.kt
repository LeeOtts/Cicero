package com.leeotts.cicero

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.ai.Assistant
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ai.BrainException
import com.leeotts.cicero.ai.BrainFactory
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.ModelCatalog
import com.leeotts.cicero.ai.ModelList
import com.leeotts.cicero.ai.Msg
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.data.Role
import com.leeotts.cicero.data.Turn
import com.leeotts.cicero.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
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

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = BrainSettings(app)
    private val repository = ConversationRepository(app)
    // Shared with GlassesViewModel and the look tool; see CiceroApp.glasses.
    private val glasses = getApplication<CiceroApp>().glasses

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

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val localProbe = ModelProbe()
    private val whisperProbe = ModelProbe()

    val localModels: StateFlow<ModelList> = localProbe.state.asStateFlow()
    val whisperModels: StateFlow<ModelList> = whisperProbe.state.asStateFlow()

    /** In-memory history for follow-ups within the current thread. */
    private var history: List<Msg> = emptyList()

    fun update(transform: (BrainConfig) -> BrainConfig) {
        viewModelScope.launch { settings.update(transform) }
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
        private var probedUrl: String? = null

        fun load(url: String, apiKey: String, force: Boolean) {
            if (url.isBlank() || state.value == ModelList.Loading) return
            if (!force && url == probedUrl && state.value is ModelList.Loaded) return

            probedUrl = url
            viewModelScope.launch {
                state.value = ModelList.Loading
                state.value = ModelCatalog.ids(url, apiKey).fold(
                    onSuccess = { ModelList.Loaded(it) },
                    onFailure = { ModelList.Failed(it.message ?: "Couldn't reach the server.") },
                )
            }
        }
    }

    fun loadLocalModels(force: Boolean = false) {
        val cfg = config.value
        localProbe.load(cfg.localBaseUrl, cfg.localKey, force)
    }

    /** The Whisper endpoint takes no key of its own today, hence the blank one. */
    fun loadWhisperModels(force: Boolean = false) {
        whisperProbe.load(config.value.speechUrl, apiKey = "", force = force)
    }

    fun testConnection() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _testResult.value = TestResult.Running
            _testResult.value = BrainFactory.brain(config.value).testConnection().fold(
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
            val cfg = config.value
            val brain = BrainFactory.brain(cfg)
            val now = System.currentTimeMillis()

            val conversationId = repository.conversationFor(now, brain.id)
            val isFirstTurn = history.isEmpty()
            repository.addTurn(conversationId, Role.USER, question, now = now)

            val assistant = Assistant(
                brain = brain,
                transcriber = BrainFactory.transcriber(cfg, brain),
                tools = ToolRegistry.build(getApplication(), repository, glasses, brain),
            )

            var failed = false
            val answer = try {
                val result = assistant.ask(text = question, priorHistory = history)
                history = result.history
                result.spoken
            } catch (e: BrainException) {
                failed = true
                e.spokenMessage // already phrased for a human
            } catch (e: Exception) {
                failed = true
                Log.e(TAG, "ask failed", e)
                "Something went wrong: ${e.message}"
            }

            repository.addTurn(
                conversationId,
                Role.ASSISTANT,
                answer,
                now = System.currentTimeMillis(),
            )
            if (isFirstTurn) repository.ensureTitled(conversationId, brain, question)

            _exchanges.value = _exchanges.value +
                Exchange(question, answer, brain.displayName, isError = failed)
            _busy.value = false
        }
    }

    private companion object {
        const val RECENT_LIMIT = 6
    }

    /** Starts a fresh thread; the next question carries no prior context. */
    fun clearConversation() {
        history = emptyList()
        _exchanges.value = emptyList()
    }

}
