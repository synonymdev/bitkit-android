package to.bitkit.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val DELAY_MS = 250L
    }

    val isOnline: Flow<ConnectivityState> = callbackFlow {
        var currentNetwork: Network? = null
        var currentCapabilities: NetworkCapabilities? = null

        fun send() {
            repoScope.launch {
                val state = calculateConnectivityState(currentCapabilities)
                Logger.verbose("Network state update: $state")
                send(state)
            }
        }

        fun fetchAsyncAndSend(delay: Long) {
            // Avoid race conditions by fetching capabilities async
            mainHandler.postDelayed({
                runCatching {
                    currentCapabilities = currentNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                    send()
                }.onFailure {
                    Logger.error("Error in async update: ${it.message}")
                }
            }, delay)
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                Logger.verbose("Network available: $network")
                fetchAsyncAndSend(DELAY_MS)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                currentNetwork = network
                Logger.verbose("Network losing: $network in ${maxMsToLive}ms")
                send()
            }

            override fun onLost(network: Network) {
                currentNetwork = null
                currentCapabilities = null
                Logger.debug("Network lost: $network")
                send()
            }

            override fun onUnavailable() {
                currentNetwork = null
                currentCapabilities = null
                Logger.debug("Network unavailable")
                send()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                currentNetwork = network
                currentCapabilities = capabilities
                Logger.verbose("Network capabilities changed for $network")
                send()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (currentNetwork != null) {
                    currentNetwork = network
                }
                Logger.verbose("Network link properties changed for $network")
                fetchAsyncAndSend(DELAY_MS)
            }
        }

        runCatching {
            currentNetwork = connectivityManager.activeNetwork
            fetchAsyncAndSend(0)
        }.onFailure {
            Logger.error("Error getting active network: ${it.message}")
        }

        // Register NetworkCallback
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            Logger.debug("Network callback registered")
        }.onFailure {
            Logger.error("Error registering network callback: ${it.message}")
        }

        awaitClose {
            runCatching {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                Logger.debug("Network callback unregistered")
            }.onFailure {
                Logger.warn("Error unregistering network callback: ${it.message}")
            }

            mainHandler.removeCallbacksAndMessages(null)
        }
    }.distinctUntilChanged().onEach { state ->
        Logger.debug("New network state: $state")
    }

    private fun calculateConnectivityState(capabilities: NetworkCapabilities?): ConnectivityState {
        if (capabilities == null) return ConnectivityState.DISCONNECTED

        // Check if internet is suspended
        val isInternetSuspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)

        // Determine internet reachability
        var isInternetReachable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !isInternetSuspended

        // Special handling for VPN connections
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            isInternetReachable = isInternetReachable && capabilities.linkDownstreamBandwidthKbps != 0
        }

        // Determine connectivity state
        return when {
            isInternetReachable -> ConnectivityState.CONNECTED
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> ConnectivityState.CONNECTING
            else -> ConnectivityState.DISCONNECTED
        }
    }
}

enum class ConnectivityState { CONNECTED, CONNECTING, DISCONNECTED }
