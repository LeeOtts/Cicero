package com.leeotts.cicero.ai

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.leeotts.cicero.BuildConfig
import com.leeotts.cicero.audio.ArmingRules
import com.leeotts.cicero.audio.MicSource
import com.leeotts.cicero.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "brain_settings",
    // Runs inside DataStore initialisation, before the first emission, so no
    // reader can ever observe a half-migrated store.
    produceMigrations = { listOf(LegacySettingsMigration(KeystoreSecrets)) },
)

/** Which provider and model answer one kind of turn. */
data class Target(val providerId: String, val model: String) {
    val provider: Provider get() = Providers.byId(providerId)
}

/**
 * Which address reaches the self-hosted server.
 *
 * The same machine answers on a LAN IP at home and on a Tailscale name
 * anywhere, and neither works in the other place: a LAN IP does not resolve off
 * the network. [AUTO] settles it per network instead of asking the user to
 * remember, which is the whole point - the switch is needed exactly when
 * fiddling with settings is least convenient.
 */
enum class LocalUrlMode { AUTO, LAN, TAILSCALE }

/**
 * Everything the user can change about the assistant backend.
 *
 * Keys and models are maps rather than named fields because the provider list is
 * data now ([Providers]), and a field per provider would put the count back into
 * the code.
 */
data class BrainConfig(
    val providerId: String = Providers.DEFAULT.id,

    /** provider id -> secret. Plaintext in memory, encrypted on disk. */
    val keys: Map<String, String> = emptyMap(),
    /** provider id -> chosen model. Absent means the provider default. */
    val models: Map<String, String> = emptyMap(),

    /** Only the self-hosted provider has an address and capabilities to set. */
    val localBaseUrl: String = OpenAiCompatibleBrain.LM_STUDIO_DEFAULT,
    /** Alternate address for the same server, reached over Tailscale when the
     *  phone is off the home network - a LAN IP does not resolve there. */
    val localTailscaleUrl: String = "",
    /** Which of the two addresses above is used, or [LocalUrlMode.AUTO] to decide. */
    val localUrlMode: LocalUrlMode = LocalUrlMode.AUTO,
    val localVision: Boolean = false,
    val localTools: Boolean = true,

    val routingEnabled: Boolean = false,
    val roles: Map<TaskRole, Target> = emptyMap(),

    /** Whisper endpoint, used by every backend that cannot hear for itself. */
    val whisperBaseUrl: String = OpenAiCompatibleBrain.LM_STUDIO_DEFAULT,
    val whisperModel: String = WhisperTranscriber.DEFAULT_MODEL,
    /** On by default: usually one machine serves both, so one address is enough. */
    val whisperSameServer: Boolean = true,

    // --- "Hey Cicero" ------------------------------------------------------
    // Every default here is what the app did before the wake word existed, so
    // an upgrading install needs no migration: absent keys read back as these.
    val wakeEnabled: Boolean = false,
    val wakeMic: MicSource = MicSource.PHONE,
    val wakeSensitivity: Float = 0.5f,
    /** Picovoice access key. Encrypted at rest, like the provider keys. */
    val wakeAccessKey: String = "",
    /**
     * The largest battery saving in the feature, hence on by default: with the
     * glasses disconnected there is nothing to look at, so nothing to listen
     * for. Turning it off is a real cost and the UI says so.
     */
    val wakeArmOnlyWithGlasses: Boolean = true,
    /** Percent below which listening stops, unless the phone is charging. */
    val wakeBatteryFloor: Int = 20,
    /** Skips the phone's input DSP: cheaper, possibly less accurate. */
    val wakeUnprocessedAudio: Boolean = false,

    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    /** What the arming policy needs, without it having to know about this class. */
    val armingRules: ArmingRules
        get() = ArmingRules(
            armOnlyWithGlasses = wakeArmOnlyWithGlasses,
            batteryFloor = wakeBatteryFloor,
        )

    val provider: Provider get() = Providers.byId(providerId)

    /**
     * The Gemini key still falls back to the one baked in from local.properties,
     * so a fresh install works before anything is typed. Note that this baked-in
     * value sits in the APK in cleartext regardless of [Secrets]; drop the
     * buildConfigField if that key ever matters.
     */
    fun keyFor(providerId: String): String = keys[providerId]?.ifBlank { null }
        ?: if (providerId == Providers.GEMINI.id) BuildConfig.GEMINI_API_KEY else ""

    fun modelFor(provider: Provider): String =
        models[provider.id]?.ifBlank { null } ?: provider.defaultModel

    /**
     * Which of the two local addresses to use.
     *
     * Pure and synchronous so the rules can be tested without a network:
     * [lanReachable] is the probe's answer, passed in rather than taken, and is
     * null when nothing has probed yet. Only AUTO with both addresses set
     * consults it - every other case has one honest answer, which is what lets
     * [LocalEndpoint] skip the probe entirely most of the time.
     */
    fun localUrl(lanReachable: Boolean? = null): String {
        if (localTailscaleUrl.isBlank()) return localBaseUrl
        if (localBaseUrl.isBlank()) return localTailscaleUrl
        return when (localUrlMode) {
            LocalUrlMode.LAN -> localBaseUrl
            LocalUrlMode.TAILSCALE -> localTailscaleUrl
            // Unprobed AUTO favours the LAN address: at home it is the right
            // answer, and away from home the probe will have run.
            LocalUrlMode.AUTO -> if (lanReachable == false) localTailscaleUrl else localBaseUrl
        }
    }

    /** The address as things stand, with no probe result to hand. */
    val activeLocalUrl: String get() = localUrl()

    fun baseUrlFor(provider: Provider): String =
        if (provider.userEditableUrl) activeLocalUrl else provider.baseUrl

    fun defaultTarget(): Target = Target(providerId, modelFor(provider))

    /** The target for one role, falling back to the user's own choice. */
    fun targetFor(role: TaskRole?): Target {
        if (role == null) return defaultTarget()
        roles[role]?.takeIf { it.providerId.isNotBlank() }?.let { return it }
        // Unassigned TITLE keeps the rule this replaced: Claude has a fast model
        // that writes a five-word title as well as Opus does for a fifth of the
        // price. The other backends have no cheaper sibling and answer for
        // themselves.
        if (role == TaskRole.TITLE && providerId == Providers.CLAUDE.id) {
            return Target(Providers.CLAUDE.id, ClaudeBrain.FAST_MODEL)
        }
        return defaultTarget()
    }

    /**
     * True while speech rides on the model's own server, which is only an option
     * when there *is* one - a hosted provider still needs an address typed for
     * transcription.
     */
    val speechSharesServer: Boolean get() = whisperSameServer && provider.userEditableUrl

    /** Where recordings actually go. */
    val speechUrl: String get() = if (speechSharesServer) activeLocalUrl else whisperBaseUrl
}

class BrainSettings(
    private val context: Context,
    private val secrets: Secrets = KeystoreSecrets,
) {

    val config: Flow<BrainConfig> = context.dataStore.data.map { it.toConfig(secrets) }

    suspend fun update(transform: (BrainConfig) -> BrainConfig) {
        context.dataStore.edit { prefs ->
            prefs.write(transform(prefs.toConfig(secrets)), secrets)
        }
    }

    /**
     * Parks the PKCE verifier while the browser holds the foreground.
     *
     * It cannot live in a ViewModel or a SavedStateHandle: the app is routinely
     * killed while a Custom Tab is in front, and the redirect arrives at a
     * different activity with no saved state of its own. Encrypted like any
     * other secret, single-use, and short-lived.
     */
    suspend fun putPendingAuth(verifier: String) {
        context.dataStore.edit {
            it[OAUTH_VERIFIER] = secrets.encrypt(verifier)
            it[OAUTH_STARTED] = System.currentTimeMillis().toString()
        }
    }

    /** Consumes the verifier. Null when absent, spent, or older than the TTL. */
    suspend fun takePendingAuth(): String? {
        var verifier: String? = null
        context.dataStore.edit { prefs ->
            val stored = prefs[OAUTH_VERIFIER].orEmpty()
            val startedAt = prefs[OAUTH_STARTED]?.toLongOrNull() ?: 0L
            val fresh = System.currentTimeMillis() - startedAt <= PENDING_AUTH_TTL_MS
            if (stored.isNotBlank() && fresh) verifier = secrets.decrypt(stored).ifBlank { null }
            prefs.remove(OAUTH_VERIFIER)
            prefs.remove(OAUTH_STARTED)
        }
        return verifier
    }

    suspend fun clearPendingAuth() {
        context.dataStore.edit {
            it.remove(OAUTH_VERIFIER)
            it.remove(OAUTH_STARTED)
        }
    }

    private companion object {
        /** Long enough to find a password, short enough that a stale one expires. */
        const val PENDING_AUTH_TTL_MS = 10 * 60 * 1000L
    }
}

// --- persistence ------------------------------------------------------------

internal fun keyPref(providerId: String) = stringPreferencesKey("key_$providerId")
internal fun modelPref(providerId: String) = stringPreferencesKey("model_$providerId")
private fun rolePref(role: TaskRole, part: String) =
    stringPreferencesKey("role_${role.name.lowercase()}_$part")

internal val PROVIDER_ID = stringPreferencesKey("provider_id")
internal val MIGRATED = booleanPreferencesKey("migrated_to_catalog")
private val ROUTING = booleanPreferencesKey("routing_enabled")
private val LOCAL_URL = stringPreferencesKey("local_url")
private val LOCAL_TAILSCALE_URL = stringPreferencesKey("local_tailscale_url")
private val LOCAL_URL_MODE = stringPreferencesKey("local_url_mode")
private val LOCAL_VISION = booleanPreferencesKey("local_vision")
private val LOCAL_TOOLS = booleanPreferencesKey("local_tools")
private val WHISPER_URL = stringPreferencesKey("whisper_url")
private val WHISPER_MODEL = stringPreferencesKey("whisper_model")
private val WHISPER_SAME = booleanPreferencesKey("whisper_same_server")
private val THEME_MODE = stringPreferencesKey("theme_mode")
private val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
private val WAKE_MIC = stringPreferencesKey("wake_mic")
private val WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
private val WAKE_ARM_WITH_GLASSES = booleanPreferencesKey("wake_arm_only_with_glasses")
private val WAKE_BATTERY_FLOOR = intPreferencesKey("wake_battery_floor")
private val WAKE_UNPROCESSED = booleanPreferencesKey("wake_unprocessed_audio")

// Its own encrypted key rather than a tenth entry in the provider catalog:
// toConfig and write iterate Providers.all, so a non-provider id there would
// never be read back - and inventing a fake provider to carry it would break
// the rule that adding a provider is one line and nothing else.
private val WAKE_ACCESS_KEY = stringPreferencesKey("wake_access_key")

// Not part of BrainConfig: a half-finished sign-in is transient, and nothing in
// the UI should be able to observe it as settings state.
private val OAUTH_VERIFIER = stringPreferencesKey("oauth_verifier")
private val OAUTH_STARTED = stringPreferencesKey("oauth_started_at")

internal fun Preferences.toConfig(secrets: Secrets): BrainConfig {
    val defaults = BrainConfig()
    val keys = buildMap {
        Providers.all.forEach { p ->
            val stored = this@toConfig[keyPref(p.id)].orEmpty()
            if (stored.isNotBlank()) secrets.decrypt(stored).ifBlank { null }?.let { put(p.id, it) }
        }
    }
    val models = buildMap {
        Providers.all.forEach { p ->
            this@toConfig[modelPref(p.id)]?.ifBlank { null }?.let { put(p.id, it) }
        }
    }
    val roles = buildMap {
        TaskRole.entries.forEach { role ->
            val id = this@toConfig[rolePref(role, "provider")]?.ifBlank { null } ?: return@forEach
            put(role, Target(id, this@toConfig[rolePref(role, "model")].orEmpty()))
        }
    }
    return BrainConfig(
        providerId = this[PROVIDER_ID]?.ifBlank { null } ?: defaults.providerId,
        keys = keys,
        models = models,
        localBaseUrl = this[LOCAL_URL]?.ifBlank { null } ?: defaults.localBaseUrl,
        localTailscaleUrl = this[LOCAL_TAILSCALE_URL]?.ifBlank { null } ?: defaults.localTailscaleUrl,
        // Same shape as themeMode: an unreadable value falls back rather than
        // throwing, so a hand-edited or downgraded store still opens.
        localUrlMode = this[LOCAL_URL_MODE]?.let { runCatching { LocalUrlMode.valueOf(it) }.getOrNull() }
            ?: defaults.localUrlMode,
        localVision = this[LOCAL_VISION] ?: defaults.localVision,
        localTools = this[LOCAL_TOOLS] ?: defaults.localTools,
        routingEnabled = this[ROUTING] ?: defaults.routingEnabled,
        roles = roles,
        whisperBaseUrl = this[WHISPER_URL]?.ifBlank { null } ?: defaults.whisperBaseUrl,
        whisperModel = this[WHISPER_MODEL]?.ifBlank { null } ?: defaults.whisperModel,
        whisperSameServer = this[WHISPER_SAME] ?: defaults.whisperSameServer,
        themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: defaults.themeMode,
        wakeEnabled = this[WAKE_ENABLED] ?: defaults.wakeEnabled,
        wakeMic = this[WAKE_MIC]?.let { runCatching { MicSource.valueOf(it) }.getOrNull() }
            ?: defaults.wakeMic,
        wakeSensitivity = this[WAKE_SENSITIVITY] ?: defaults.wakeSensitivity,
        wakeAccessKey = this[WAKE_ACCESS_KEY].orEmpty().ifBlank { null }
            ?.let { secrets.decrypt(it) }.orEmpty(),
        wakeArmOnlyWithGlasses = this[WAKE_ARM_WITH_GLASSES] ?: defaults.wakeArmOnlyWithGlasses,
        wakeBatteryFloor = this[WAKE_BATTERY_FLOOR] ?: defaults.wakeBatteryFloor,
        wakeUnprocessedAudio = this[WAKE_UNPROCESSED] ?: defaults.wakeUnprocessedAudio,
    )
}

/** Internal, like [toConfig], so the round trip can be driven from a test. */
internal fun MutablePreferences.write(next: BrainConfig, secrets: Secrets) {
    this[PROVIDER_ID] = next.providerId
    Providers.all.forEach { p ->
        // Removing rather than writing a blank means clearing a field genuinely
        // deletes it, and the file does not churn nine fresh IVs on every theme
        // toggle.
        val key = next.keys[p.id].orEmpty()
        if (key.isBlank()) remove(keyPref(p.id)) else this[keyPref(p.id)] = secrets.encrypt(key)
        val model = next.models[p.id].orEmpty()
        if (model.isBlank()) remove(modelPref(p.id)) else this[modelPref(p.id)] = model
    }
    TaskRole.entries.forEach { role ->
        val target = next.roles[role]
        if (target == null || target.providerId.isBlank()) {
            remove(rolePref(role, "provider"))
            remove(rolePref(role, "model"))
        } else {
            this[rolePref(role, "provider")] = target.providerId
            this[rolePref(role, "model")] = target.model
        }
    }
    this[ROUTING] = next.routingEnabled
    this[LOCAL_URL] = next.localBaseUrl
    if (next.localTailscaleUrl.isBlank()) {
        remove(LOCAL_TAILSCALE_URL)
    } else {
        this[LOCAL_TAILSCALE_URL] = next.localTailscaleUrl
    }
    this[LOCAL_URL_MODE] = next.localUrlMode.name
    this[LOCAL_VISION] = next.localVision
    this[LOCAL_TOOLS] = next.localTools
    this[WHISPER_URL] = next.whisperBaseUrl
    this[WHISPER_MODEL] = next.whisperModel
    this[WHISPER_SAME] = next.whisperSameServer
    this[THEME_MODE] = next.themeMode.name
    this[WAKE_ENABLED] = next.wakeEnabled
    this[WAKE_MIC] = next.wakeMic.name
    this[WAKE_SENSITIVITY] = next.wakeSensitivity
    this[WAKE_ARM_WITH_GLASSES] = next.wakeArmOnlyWithGlasses
    this[WAKE_BATTERY_FLOOR] = next.wakeBatteryFloor
    this[WAKE_UNPROCESSED] = next.wakeUnprocessedAudio
    // Cleared rather than blanked, so removing the key really removes it.
    if (next.wakeAccessKey.isBlank()) {
        remove(WAKE_ACCESS_KEY)
    } else {
        this[WAKE_ACCESS_KEY] = secrets.encrypt(next.wakeAccessKey)
    }
    this[MIGRATED] = true
}

// --- migration off the pre-catalog layout -----------------------------------

internal class LegacySettingsMigration(private val secrets: Secrets) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[MIGRATED] != true
    override suspend fun migrate(currentData: Preferences) = migrateLegacy(currentData, secrets)
    override suspend fun cleanUp() = Unit
}

/**
 * Moves the three named key fields into per-provider encrypted slots.
 *
 * A pure function so it can be driven from a unit test with a fake [Secrets] -
 * the Android Keystore does not exist off-device, and losing someone's keys on
 * upgrade is not a thing to find out about by hand.
 */
internal fun migrateLegacy(prefs: Preferences, secrets: Secrets): Preferences {
    val out = prefs.toMutablePreferences()
    if (prefs[MIGRATED] == true) return out

    fun move(from: String, to: Preferences.Key<String>, encrypt: Boolean) {
        val legacy = stringPreferencesKey(from)
        val value = prefs[legacy].orEmpty()
        if (value.isNotBlank()) out[to] = if (encrypt) secrets.encrypt(value) else value
        // Never leave the plaintext behind - that was the point.
        out.remove(legacy)
    }

    move("gemini_key", keyPref(Providers.GEMINI.id), encrypt = true)
    move("claude_key", keyPref(Providers.CLAUDE.id), encrypt = true)
    move("local_key", keyPref(Providers.LOCAL.id), encrypt = true)
    move("gemini_model", modelPref(Providers.GEMINI.id), encrypt = false)
    move("claude_model", modelPref(Providers.CLAUDE.id), encrypt = false)
    move("local_model", modelPref(Providers.LOCAL.id), encrypt = false)

    val legacyChoice = stringPreferencesKey("choice")
    out[PROVIDER_ID] = when (prefs[legacyChoice]) {
        "CLAUDE" -> Providers.CLAUDE.id
        "LOCAL" -> Providers.LOCAL.id
        else -> Providers.GEMINI.id
    }
    out.remove(legacyChoice)

    out[MIGRATED] = true
    return out
}

/** Builds the live [Brain] and [Transcriber] pair for a given config. */
object BrainFactory {

    /**
     * One `when`, over three wires, that does not grow when a provider is added.
     * If a `when (provider)` ever appears here, the catalog has been defeated.
     */
    fun brain(config: BrainConfig, target: Target = config.defaultTarget()): Brain {
        val p = target.provider
        val key = config.keyFor(p.id)
        val model = target.model.ifBlank { p.defaultModel }
        val baseUrl = config.baseUrlFor(p)
        return when (p.wire) {
            Wire.GEMINI -> GeminiBrain(key, model, baseUrl)
            Wire.ANTHROPIC -> ClaudeBrain(key, model, baseUrl)
            Wire.OPENAI -> OpenAiCompatibleBrain(
                baseUrl = baseUrl,
                model = model,
                apiKey = key,
                id = p.id,
                auth = p.auth,
                extraHeaders = p.extraHeaders,
                supportsVision = if (p.userEditableCaps) config.localVision else p.vision,
                supportsTools = if (p.userEditableCaps) config.localTools else p.tools,
                // OpenRouter runs a search server-side for any model asked for
                // with the ":online" suffix, which is the one way an
                // OpenAI-shaped endpoint can honestly claim web search.
                supportsWebSearch = p.webSearch || model.endsWith(":online"),
                displayName = "${p.displayName} ($model)",
            )
        }
    }

    /** The brain for one kind of work. A null role means the user's own choice. */
    fun brainFor(config: BrainConfig, role: TaskRole?): Brain =
        brain(config, config.targetFor(role))

    /** Gemini takes audio directly; everything else transcribes first. */
    fun transcriber(config: BrainConfig, brain: Brain): Transcriber =
        if (brain.acceptsAudio) NoOpTranscriber
        else WhisperTranscriber(config.speechUrl, config.whisperModel)
}
