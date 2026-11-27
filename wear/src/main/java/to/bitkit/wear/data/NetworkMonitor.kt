package to.bitkit.wear.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.connectivityManager
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the watch has a network that the system checked to reach the internet, over Wi-Fi, LTE or the phone's
 * Bluetooth proxy. It is false until the system reports the default network.
 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

/** Registers with the system off the main thread; the system then reports the default network at once. */
@Singleton
class ConnectivityNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : NetworkMonitor {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val _isOnline = MutableStateFlow(false)

    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        CoroutineScope(ioDispatcher + SupervisorJob()).launch {
            runCatching { context.connectivityManager.registerDefaultNetworkCallback(callback()) }
                .onFailure { Logger.error("Failed to watch the network", it, context = TAG) }
        }
    }

    private fun callback() = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // The internet capability alone only says the network is meant to reach it; validated says it does.
            set(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) = set(false)
    }

    private fun set(isOnline: Boolean) {
        if (_isOnline.value != isOnline) {
            Logger.info("Changed network to '${if (isOnline) "online" else "offline"}'", context = TAG)
        }
        _isOnline.update { isOnline }
    }
}
