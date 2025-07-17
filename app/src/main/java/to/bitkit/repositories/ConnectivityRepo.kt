package to.bitkit.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

enum class ConnectivityState { CONNECTED, CONNECTING, DISCONNECTED, }

@Singleton
class ConnectivityRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @OptIn(FlowPreview::class)
    val isOnline: Flow<ConnectivityState> = callbackFlow {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                repoScope.launch {
                    val state = ConnectivityState.CONNECTING
                    Logger.debug("Network connection available, state: $state")
                    send(ConnectivityState.CONNECTING)
                }
            }

            override fun onLost(network: Network) {
                repoScope.launch {
                    val hasActiveNetwork = connectivityManager.activeNetwork != null &&
                        getCurrentNetworkState() != ConnectivityState.DISCONNECTED

                    if (hasActiveNetwork) {
                        Logger.debug("Lost a connection while having another active, skipping state update")
                    } else {
                        val state = ConnectivityState.DISCONNECTED
                        Logger.debug("Network connection lost, state: $state")
                        send(state)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                repoScope.launch {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                    val state = when {
                        hasInternet && isValidated -> ConnectivityState.CONNECTED
                        hasInternet && !isValidated -> ConnectivityState.CONNECTING
                        else -> ConnectivityState.DISCONNECTED
                    }

                    Logger.debug("Network capabilities changed, state: $state")
                    send(state)
                }
            }

            override fun onUnavailable() {
                repoScope.launch {
                    val state = ConnectivityState.DISCONNECTED
                    Logger.debug("Network unavailable, state: $state")
                    send(state)
                }
            }
        }

        val initialState = getCurrentNetworkState()
        Logger.debug("Network monitoring started, state: $initialState")

        repoScope.launch {
            send(initialState)
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Logger.debug("Network monitoring stopped")
        }
    }
        .debounce(300.milliseconds)
        .distinctUntilChanged()

    private fun getCurrentNetworkState(): ConnectivityState {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) return ConnectivityState.DISCONNECTED

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities == null) return ConnectivityState.DISCONNECTED

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return when {
            hasInternet && isValidated -> ConnectivityState.CONNECTED
            hasInternet && !isValidated -> ConnectivityState.CONNECTING
            else -> ConnectivityState.DISCONNECTED
        }
    }
}
