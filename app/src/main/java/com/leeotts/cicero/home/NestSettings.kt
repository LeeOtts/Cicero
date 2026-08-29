package com.leeotts.cicero.home

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.leeotts.cicero.ai.KeystoreSecrets
import com.leeotts.cicero.ai.Secrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What Cicero needs to talk to the Smart Device Management API.
 *
 * All four values are pasted by hand. The app never runs the OAuth dance
 * itself, and that is a design decision rather than a shortcut: Google's
 * Partner Connections Manager only redirects to a URL registered against a
 * *web* OAuth client, custom schemes are no longer accepted for native OAuth,
 * and a web client needs a client secret that an installed app cannot keep
 * anyway. An in-app flow would buy complexity and no security.
 */
data class NestConfig(
    /** Device Access project id - the one the $5 registration issues. */
    val projectId: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val refreshToken: String = "",
) {
    val isConfigured: Boolean
        get() = projectId.isNotBlank() && clientId.isNotBlank() &&
            clientSecret.isNotBlank() && refreshToken.isNotBlank()
}

private val Context.nestStore by preferencesDataStore(name = "nest_settings")

/**
 * Its own store rather than fields on BrainConfig: these are house credentials,
 * not model settings, and the two have no reason to be written together.
 *
 * files/datastore/ is already excluded from cloud backup and device transfer
 * wholesale (see res/xml/data_extraction_rules.xml), so this file inherits that
 * without a new rule.
 */
class NestSettings(
    private val context: Context,
    private val secrets: Secrets = KeystoreSecrets,
) {

    val config: Flow<NestConfig> = context.nestStore.data.map { it.toNestConfig(secrets) }

    suspend fun update(transform: (NestConfig) -> NestConfig) {
        context.nestStore.edit { prefs ->
            prefs.writeNest(transform(prefs.toNestConfig(secrets)), secrets)
        }
    }
}

// --- persistence ------------------------------------------------------------

private val PROJECT_ID = stringPreferencesKey("project_id")
private val CLIENT_ID = stringPreferencesKey("client_id")
private val CLIENT_SECRET = stringPreferencesKey("client_secret")
private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

/**
 * Only the two bearer credentials are encrypted. The project and client ids are
 * identifiers that travel in URLs and consent screens; encrypting them would
 * suggest a secrecy they do not have.
 */
internal fun Preferences.toNestConfig(secrets: Secrets) = NestConfig(
    projectId = this[PROJECT_ID].orEmpty(),
    clientId = this[CLIENT_ID].orEmpty(),
    clientSecret = this[CLIENT_SECRET]?.let { secrets.decrypt(it) }.orEmpty(),
    refreshToken = this[REFRESH_TOKEN]?.let { secrets.decrypt(it) }.orEmpty(),
)

internal fun MutablePreferences.writeNest(next: NestConfig, secrets: Secrets) {
    this[PROJECT_ID] = next.projectId.trim()
    this[CLIENT_ID] = next.clientId.trim()
    writeSecret(CLIENT_SECRET, next.clientSecret, secrets)
    writeSecret(REFRESH_TOKEN, next.refreshToken, secrets)
}

/** Blank clears the key outright, so an emptied field does not leave ciphertext behind. */
private fun MutablePreferences.writeSecret(
    key: Preferences.Key<String>,
    value: String,
    secrets: Secrets,
) {
    val trimmed = value.trim()
    if (trimmed.isBlank()) remove(key) else this[key] = secrets.encrypt(trimmed)
}
