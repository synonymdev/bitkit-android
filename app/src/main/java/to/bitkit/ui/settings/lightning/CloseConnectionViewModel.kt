package to.bitkit.ui.settings.lightning

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
import to.bitkit.ext.amountOnClose
import to.bitkit.models.Toast
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class CloseConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val transferRepo: TransferRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "CloseConnectionViewModel"
    }

    private val _uiState = MutableStateFlow(CloseConnectionUiState())
    val uiState = _uiState.asStateFlow()

    fun closeChannel(channelId: String) {
        val channel = lightningRepo.lightningState.value.channels
            .find { it.channelId == channelId }
            ?: run {
                Logger.error("No channel found for closing: '$channelId'", context = TAG)
                return
            }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            lightningRepo.closeChannel(channel).fold(
                onSuccess = {
                    transferRepo.createTransfer(
                        type = TransferType.COOP_CLOSE,
                        amountSats = channel.amountOnClose.toLong(),
                        channelId = channel.channelId,
                        fundingTxId = channel.fundingTxo?.txid,
                    )
                    walletRepo.syncNodeAndWallet()

                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.lightning__close_success_title),
                        description = context.getString(R.string.lightning__close_success_msg),
                    )

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            closeSuccess = true,
                        )
                    }
                },
                onFailure = {
                    Logger.error("Failed to close channel", it, context = TAG)

                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__close_error),
                        description = context.getString(R.string.lightning__close_error_msg),
                    )

                    _uiState.update { it.copy(isLoading = false) }
                }
            )
        }
    }
}

data class CloseConnectionUiState(
    val isLoading: Boolean = false,
    val closeSuccess: Boolean = false,
)
