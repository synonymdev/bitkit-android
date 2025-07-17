package to.bitkit.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
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

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val isOnline: Flow<ConnectivityState> = callbackFlow {
        Logger.debug("Network connectivity monitor starting")

        val networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                // onCapabilitiesChanged is always called immediately after
                Logger.verbose("Network available, skipping state update")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                repoScope.launch {
                    val state = capabilities.asState()
                    Logger.verbose("Network capabilities changed, sent: $state")
                    send(state)
                }
            }

            override fun onUnavailable() {
                repoScope.launch {
                    val state = ConnectivityState.DISCONNECTED
                    Logger.debug("Network unavailable, sent: $state")
                    send(state)
                }
            }

            override fun onLost(network: Network) {
                repoScope.launch {
                    val state = ConnectivityState.DISCONNECTED
                    Logger.debug("Network connection lost, sent: $state")
                    send(state)
                }
            }
        }

        val initialState = getCurrentNetworkState()
        repoScope.launch {
            Logger.debug("Network initial state: $initialState")
            send(initialState)
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Logger.debug("Network monitoring stopped")
        }
    }.distinctUntilChanged()
        .transformLatest { state ->
            when (state) {
                ConnectivityState.DISCONNECTED -> {
                    val delay = 200.milliseconds
                    Logger.verbose("Network state $state delayed by $delay")
                    delay(200)
                    emit(state)
                }

                else -> emit(state)
            }
        }.onEach {
            Logger.debug("New network state: $it")
        }

    private fun getCurrentNetworkState(): ConnectivityState {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) return ConnectivityState.DISCONNECTED

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities == null) return ConnectivityState.DISCONNECTED

        return capabilities.asState()
    }

    private fun NetworkCapabilities.asState(): ConnectivityState {
        val hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return when {
            hasInternet && isValidated -> ConnectivityState.CONNECTED
            hasInternet && hasTransport -> ConnectivityState.CONNECTING
            else -> ConnectivityState.DISCONNECTED
        }
    }
}
