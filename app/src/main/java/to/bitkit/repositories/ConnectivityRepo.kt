package to.bitkit.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectivityState { CONNECTED, CONNECTING, DISCONNECTED, }

@Singleton
class ConnectivityRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Observes network connectivity status as a Flow.
     * Emits CONNECTED when internet is available, CONNECTING when transitioning, DISCONNECTED when offline.
     */
    val isOnline: Flow<ConnectivityState> = callbackFlow {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.info("Network connection available")
                repoScope.launch {
                    send(if (isConnected()) ConnectivityState.CONNECTED else ConnectivityState.CONNECTING)
                }
            }

            override fun onLost(network: Network) {
                Logger.debug("Network connection lost")
                repoScope.launch {
                    send(ConnectivityState.DISCONNECTED)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                val state = when {
                    hasInternet && isValidated -> ConnectivityState.CONNECTED
                    hasInternet && !isValidated -> ConnectivityState.CONNECTING
                    else -> ConnectivityState.DISCONNECTED
                }

                Logger.debug("Network capabilities changed, state: $state")
                repoScope.launch {
                    send(state)
                }
            }
        }

        // Send initial state
        repoScope.launch {
            send(if (isConnected()) ConnectivityState.CONNECTED else ConnectivityState.DISCONNECTED)
        }

        // Register network callback
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        awaitClose {
            Logger.debug("Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }.distinctUntilChanged()

    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return hasInternet && isValidated
    }
}
