package to.bitkit.wear.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.wear.ext.connectivityManager
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the watch has a network that reaches the internet, over Wi-Fi, LTE or the phone's Bluetooth proxy. */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

@Singleton
class ConnectivityNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkMonitor {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivity = context.connectivityManager
    private val _isOnline = MutableStateFlow(connectivity.hasInternet(connectivity.activeNetwork))

    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    set(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }

                override fun onLost(network: Network) = set(false)
            },
        )
    }

    private fun set(isOnline: Boolean) {
        if (_isOnline.value != isOnline) {
            Logger.info("Changed network to '${if (isOnline) "online" else "offline"}'", context = TAG)
        }
        _isOnline.update { isOnline }
    }

    private fun ConnectivityManager.hasInternet(network: Network?): Boolean =
        network?.let { getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
