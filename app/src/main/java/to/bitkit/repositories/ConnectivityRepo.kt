package to.bitkit.repositories

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.onEach
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

    @OptIn(FlowPreview::class)
    val isOnline: Flow<ConnectivityState> = callbackFlow {
        Logger.debug("Network connectivity monitor starting")

        val airplaneModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                repoScope.launch {
                    if (intent?.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                        val isAirplaneModeOn = intent.getBooleanExtra("state", false)

                        if (isAirplaneModeOn) {
                            val state = ConnectivityState.DISCONNECTED
                            Logger.debug("Airplane mode on, sent: $state")
                            send(state)
                        } else {
                            // TODO emit CONNECTING if reliable solution
                        }
                    }
                }
            }
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                repoScope.launch {
                    val state = ConnectivityState.CONNECTING
                    Logger.debug("Network connection available, sent: $state")
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

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                repoScope.launch {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                    val state = when {
                        hasInternet && isValidated -> ConnectivityState.CONNECTED
                        hasInternet && !isValidated -> ConnectivityState.CONNECTING
                        else -> ConnectivityState.DISCONNECTED
                    }

                    Logger.debug("Network capabilities changed, sent: $state")
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
        }

        val initialState = getCurrentNetworkState()
        repoScope.launch {
            Logger.debug("Network initial state: $initialState")
            send(initialState)
        }

        // registering monitors
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build(),
            networkCallback,
        )
        ContextCompat.registerReceiver(
            context,
            airplaneModeReceiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose {
            runCatching { context.unregisterReceiver(airplaneModeReceiver) }
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Logger.debug("Network monitoring stopped")
        }
    }.distinctUntilChanged()
        .debounce { state ->
            when (state) {
                ConnectivityState.CONNECTED -> 150
                else -> 0
            }
        }
        .onEach {
            Logger.debug("New network state: $it")
        }


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
