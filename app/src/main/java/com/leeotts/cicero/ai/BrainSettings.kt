package com.leeotts.cicero.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.annotation.StringRes
import com.leeotts.cicero.BuildConfig
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "brain_settings")

/** Which backend answers. */
enum class BrainChoice(@param:StringRes val label: Int) {
    GEMINI(R.string.brain_gemini),
    LOCAL(R.string.brain_local),
    CLAUDE(R.string.brain_claude),
}

/**
 * Everything the user can change about the assistant backend.
 *
 * API keys default to the BuildConfig values baked in from local.properties, so
 * the app works out of the box, but can be overridden in-app without a rebuild.
 */
data class BrainConfig(
    val choice: BrainChoice = BrainChoice.GEMINI,

    val geminiKey: String = BuildConfig.GEMINI_API_KEY,
    val geminiModel: String = GeminiBrain.DEFAULT_MODEL,

    val claudeKey: String = "",
    val claudeModel: String = ClaudeBrain.DEFAULT_MODEL,

    val localBaseUrl: String = OpenAiCompatibleBrain.LM_STUDIO_DEFAULT,
    val localModel: String = "",
    val localKey: String = "",
    val localVision: Boolean = false,
    val localTools: Boolean = true,

    /** Whisper endpoint, used by every backend except Gemini. */
    val whisperBaseUrl: String = OpenAiCompatibleBrain.LM_STUDIO_DEFAULT,
    val whisperModel: String = WhisperTranscriber.DEFAULT_MODEL,
    /** On by default: usually one machine serves both, so one address is enough. */
    val whisperSameServer: Boolean = true,

    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    /**
     * True while speech rides on the model's own server, which is only an option
     * when there *is* one — Claude runs in the cloud and still needs an address
     * typed for transcription.
     */
    val speechSharesServer: Boolean get() = whisperSameServer && choice == BrainChoice.LOCAL

    /** Where recordings actually go. */
    val speechUrl: String get() = if (speechSharesServer) localBaseUrl else whisperBaseUrl
}

class BrainSettings(private val context: Context) {

    val config: Flow<BrainConfig> = context.dataStore.data.map { p -> p.toConfig() }

    suspend fun update(transform: (BrainConfig) -> BrainConfig) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toConfig())
            prefs[CHOICE] = next.choice.name
            prefs[GEMINI_KEY] = next.geminiKey
            prefs[GEMINI_MODEL] = next.geminiModel
            prefs[CLAUDE_KEY] = next.claudeKey
            prefs[CLAUDE_MODEL] = next.claudeModel
            prefs[LOCAL_URL] = next.localBaseUrl
            prefs[LOCAL_MODEL] = next.localModel
            prefs[LOCAL_KEY] = next.localKey
            prefs[LOCAL_VISION] = next.localVision
            prefs[LOCAL_TOOLS] = next.localTools
            prefs[WHISPER_URL] = next.whisperBaseUrl
            prefs[WHISPER_MODEL] = next.whisperModel
            prefs[WHISPER_SAME] = next.whisperSameServer
            prefs[THEME_MODE] = next.themeMode.name
        }
    }

    private fun Preferences.toConfig(): BrainConfig {
        val defaults = BrainConfig()
        return BrainConfig(
            choice = this[CHOICE]?.let { runCatching { BrainChoice.valueOf(it) }.getOrNull() }
                ?: defaults.choice,
            geminiKey = this[GEMINI_KEY]?.ifBlank { null } ?: defaults.geminiKey,
            geminiModel = this[GEMINI_MODEL]?.ifBlank { null } ?: defaults.geminiModel,
            claudeKey = this[CLAUDE_KEY] ?: defaults.claudeKey,
            claudeModel = this[CLAUDE_MODEL]?.ifBlank { null } ?: defaults.claudeModel,
            localBaseUrl = this[LOCAL_URL]?.ifBlank { null } ?: defaults.localBaseUrl,
            localModel = this[LOCAL_MODEL] ?: defaults.localModel,
            localKey = this[LOCAL_KEY] ?: defaults.localKey,
            localVision = this[LOCAL_VISION] ?: defaults.localVision,
            localTools = this[LOCAL_TOOLS] ?: defaults.localTools,
            whisperBaseUrl = this[WHISPER_URL]?.ifBlank { null } ?: defaults.whisperBaseUrl,
            whisperModel = this[WHISPER_MODEL]?.ifBlank { null } ?: defaults.whisperModel,
            whisperSameServer = this[WHISPER_SAME] ?: defaults.whisperSameServer,
            themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
        )
    }

    private companion object {
        val CHOICE = stringPreferencesKey("choice")
        val GEMINI_KEY = stringPreferencesKey("gemini_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val CLAUDE_KEY = stringPreferencesKey("claude_key")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        val LOCAL_URL = stringPreferencesKey("local_url")
        val LOCAL_MODEL = stringPreferencesKey("local_model")
        val LOCAL_KEY = stringPreferencesKey("local_key")
        val LOCAL_VISION = booleanPreferencesKey("local_vision")
        val LOCAL_TOOLS = booleanPreferencesKey("local_tools")
        val WHISPER_URL = stringPreferencesKey("whisper_url")
        val WHISPER_MODEL = stringPreferencesKey("whisper_model")
        val WHISPER_SAME = booleanPreferencesKey("whisper_same_server")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

/** Builds the live [Brain] and [Transcriber] pair for a given config. */
object BrainFactory {

    fun brain(config: BrainConfig): Brain = when (config.choice) {
        BrainChoice.GEMINI -> GeminiBrain(config.geminiKey, config.geminiModel)
        BrainChoice.CLAUDE -> ClaudeBrain(config.claudeKey, config.claudeModel)
        BrainChoice.LOCAL -> OpenAiCompatibleBrain(
            baseUrl = config.localBaseUrl,
            model = config.localModel,
            apiKey = config.localKey,
            supportsVision = config.localVision,
            supportsTools = config.localTools,
        )
    }

    /** Gemini takes audio directly; everything else transcribes first. */
    fun transcriber(config: BrainConfig, brain: Brain): Transcriber =
        if (brain.acceptsAudio) NoOpTranscriber
        else WhisperTranscriber(config.speechUrl, config.whisperModel)
}
