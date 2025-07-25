package to.bitkit.ui.screens.transfer.external

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.LnPeer
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.Routes
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class LnurlChannelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LnurlChannelUiState())
    val uiState = _uiState.asStateFlow()

    private lateinit var params: Routes.LnurlChannel

    fun init(route: Routes.LnurlChannel) {
        this.params = route
        fetchChannelInfo()
    }

    private fun fetchChannelInfo() {
        viewModelScope.launch {
            lightningRepo.fetchLnurlChannelInfo(params.uri)
                .onSuccess { channelInfo ->
                    val peer = LnPeer.parseUri(channelInfo.uri).getOrElse {
                        errorToast(it)
                        return@onSuccess
                    }
                    _uiState.update { it.copy(peer = peer) }
                }
                .onFailure { error ->
                    val message = context.getString(R.string.other__lnurl_channel_error_raw)
                        .replace("{raw}", error.message.orEmpty())
                    errorToast(Exception(message))
                }
        }
    }

    fun onConnect() {
        val peer = requireNotNull(_uiState.value.peer)
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true) }

            val nodeId = lightningRepo.getNodeId()
            if (nodeId == null) {
                errorToast(Exception(context.getString(R.string.other__lnurl_ln_error_msg)))
                return@launch
            }

            // Connect to peer if not connected
            lightningRepo.connectPeer(peer)

            lightningRepo.requestLnurlChannel(callback = params.callback, k1 = params.k1, nodeId = nodeId)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.other__lnurl_channel_success_title),
                        description = context.getString(R.string.other__lnurl_channel_success_msg_no_peer),
                    )
                    _uiState.update { it.copy(isConnected = true) }
                }.onFailure { error ->
                    errorToast(error)
                }

            _uiState.update { it.copy(isConnecting = false) }
        }
    }

    suspend fun errorToast(error: Throwable) {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.other__lnurl_channel_error),
            description = error.message ?: "Unknown error",
        )
    }
}

data class LnurlChannelUiState(
    val peer: LnPeer? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
)
