package to.bitkit.ui.settings.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class AppStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppStatusUiState())
    val uiState: StateFlow<AppStatusUiState> = _uiState.asStateFlow()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Logger.debug("Network available: $network")
            updateInternetState(true)
        }

        override fun onLost(network: Network) {
            Logger.debug("Network lost: $network")
            updateInternetState(false)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val isConnected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Logger.debug("Network capabilities changed: $network, connected: $isConnected")
            updateInternetState(isConnected)
        }
    }

    init {
        startNetworkMonitoring()
        checkInitialConnectivity()
    }

    private fun startNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun checkInitialConnectivity() {
        viewModelScope.launch(bgDispatcher) {
            val isConnected = isNetworkConnected()
            updateInternetState(isConnected)
        }
    }

    private fun isNetworkConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateInternetState(isConnected: Boolean) {
        val newState = if (isConnected) StatusUi.State.READY else StatusUi.State.ERROR
        _uiState.update { it.copy(internetState = newState) }
    }

    override fun onCleared() {
        super.onCleared()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}

data class AppStatusUiState(
    val internetState: StatusUi.State = StatusUi.State.READY,
    val bitcoinNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningConnectionState: StatusUi.State = StatusUi.State.PENDING,
    val backupState: StatusUi.State = StatusUi.State.ERROR,
)
