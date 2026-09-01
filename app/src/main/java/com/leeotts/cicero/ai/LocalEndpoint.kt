package com.leeotts.cicero.ai

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which address reaches the self-hosted server, and remembers the answer.
 *
 * The LAN address is tried first and Tailscale is the fallback, rather than
 * detecting the Wi-Fi network by name: reading an SSID needs location permission
 * on modern Android, and it only recognises the one network anybody thought to
 * configure. A probe answers the question that actually matters - can this
 * address be reached from where the phone is standing - on any network at all.
 *
 * The probe is address resolution, not a retry: it runs once before a request,
 * and a failed completion is still never re-issued (see [Router]).
 */
object LocalEndpoint {

    private data class Decision(
        val networkId: Long,
        val lanUrl: String,
        val tailscaleUrl: String,
        val lanReachable: Boolean,
        val atMillis: Long,
    )

    private val lock = Mutex()
    private var cached: Decision? = null

    /**
     * The address to use now, probing only when the answer is not already known.
     *
     * Serialised on [lock] so a burst of calls - the answer, its title and a
     * transcription all start together - probes once rather than three times.
     */
    suspend fun resolve(context: Context, config: BrainConfig): String {
        // Nothing to decide: one of the addresses is missing, or the user has
        // taken the choice off automatic. No network call in either case.
        if (config.localUrlMode != LocalUrlMode.AUTO) return config.localUrl()
        if (config.localBaseUrl.isBlank() || config.localTailscaleUrl.isBlank()) {
            return config.localUrl()
        }

        return lock.withLock {
            val networkId = context.currentNetworkId()
            val fresh = cached?.takeIf {
                it.networkId == networkId &&
                    it.lanUrl == config.localBaseUrl &&
                    it.tailscaleUrl == config.localTailscaleUrl &&
                    System.currentTimeMillis() - it.atMillis <= CACHE_TTL_MS
            }
            val reachable = fresh?.lanReachable ?: probe(config).also { result ->
                cached = Decision(
                    networkId = networkId,
                    lanUrl = config.localBaseUrl,
                    tailscaleUrl = config.localTailscaleUrl,
                    lanReachable = result,
                    atMillis = System.currentTimeMillis(),
                )
            }
            config.localUrl(lanReachable = reachable).also {
                if (fresh == null) Log.i(TAG, "LocalEndpoint: using $it (lan reachable: $reachable)")
            }
        }
    }

    /**
     * Forgets the last decision, so the next [resolve] probes again.
     *
     * Called when a request against the chosen address fails: the cache is keyed
     * on the network, but a server can go down, move, or come back while the
     * phone stays put.
     */
    fun invalidate() {
        cached = null
    }

    /**
     * Asks the LAN address for its model list.
     *
     * A models call rather than a socket connect, because something answering on
     * the port is not the same as the model server being up - and it is the same
     * request Settings' own "test connection" makes.
     */
    private suspend fun probe(config: BrainConfig): Boolean = ModelCatalog.ids(
        baseUrl = config.localBaseUrl,
        apiKey = config.keyFor(Providers.LOCAL.id),
        httpClient = Http.probeClient,
    ).isSuccess

    /**
     * Identifies the current network, so walking out of the house invalidates
     * the decision the moment the phone changes network rather than when a
     * timer expires.
     *
     * Returns [NO_NETWORK] when there is none or the handle cannot be read;
     * paired with the TTL, an unreadable handle degrades to time-based expiry
     * rather than a decision that never refreshes.
     */
    private fun Context.currentNetworkId(): Long = runCatching {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager?.activeNetwork?.networkHandle ?: NO_NETWORK
    }.getOrDefault(NO_NETWORK)

    private const val NO_NETWORK = -1L

    /**
     * Backstop for the case the network handle cannot catch: the same network,
     * but a server that has since gone down or come back.
     */
    private const val CACHE_TTL_MS = 60_000L
}

/**
 * This config with the local address already decided.
 *
 * Returned as a [LocalUrlMode.LAN] config pointing at the winning address, so
 * everything downstream - [BrainConfig.baseUrlFor], [BrainConfig.speechUrl], the
 * title brain, the transcriber - reads it the ordinary way and none of them need
 * to know a choice was made. Brains are built fresh per turn, so resolving once
 * at the top of a turn is enough.
 */
suspend fun BrainConfig.withResolvedLocalUrl(context: Context): BrainConfig {
    if (localUrlMode != LocalUrlMode.AUTO) return this
    val resolved = LocalEndpoint.resolve(context, this)
    return copy(localBaseUrl = resolved, localUrlMode = LocalUrlMode.LAN)
}
