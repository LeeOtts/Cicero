package com.leeotts.cicero.home

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The process-wide Nest owner, to be held by CiceroApp alongside glasses and
 * location.
 *
 * One instance matters here for the same reason it did for the DAT session: the
 * device cache and the access token are only worth having if the Settings
 * screen and the assistant's tools share them. Two would double the calls
 * against a five-per-minute budget.
 *
 * Deliberately thin. All the logic lives in [NestGateway], which knows nothing
 * about Android and can therefore be tested on the JVM.
 */
class NestController(context: Context) {

    private val settings = NestSettings(context)
    private val auth = NestAuth()

    val config: Flow<NestConfig> = settings.config

    val gateway = NestGateway(auth = auth) { settings.config.first() }

    suspend fun update(transform: (NestConfig) -> NestConfig) {
        settings.update(transform)
        // The pasted credentials may have just changed, which would make a
        // cached access token belong to the wrong account.
        auth.forget()
    }

    suspend fun test(): Result<String> = gateway.test()
}
