package to.bitkit.ui.screens.transfer.external

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.UserChannelId
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.WatchResult
import to.bitkit.ext.of
import to.bitkit.ext.watchUntil
import to.bitkit.models.Toast
import to.bitkit.models.TransferType
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.screens.transfer.external.ExternalNodeContract.SideEffect
import to.bitkit.ui.screens.transfer.external.ExternalNodeContract.UiState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("LongParameterList")
@HiltViewModel
class ExternalNodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val transferRepo: to.bitkit.repositories.TransferRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()
    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effects.emit(effect) }

    init {
        observeState()
    }

    private fun observeState() {
        viewModelScope.launch {
            walletRepo.balanceState.collect {
                val maxAmount = walletRepo.balanceState.value.maxSendOnchainSats
                _uiState.update { it.copy(amount = it.amount.copy(max = maxAmount.toLong())) }
            }
        }
    }

    fun onConnectionContinue(peer: PeerDetails) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = lightningRepo.connectPeer(peer)

            _uiState.update { it.copy(isLoading = false) }

            if (result.isSuccess) {
                _uiState.update { it.copy(peer = peer) }
                setEffect(SideEffect.ConnectionSuccess)
            } else {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__error_add_title),
                    description = context.getString(R.string.lightning__error_add),
                )
            }
        }
    }

    fun parseNodeUri(uriString: String) {
        viewModelScope.launch {
            val result = runCatching { PeerDetails.of(uriString) }

            if (result.isSuccess) {
                _uiState.update { it.copy(peer = result.getOrNull()) }
            } else {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__error_add_uri),
                )
            }
        }
    }

    fun onAmountChange(sats: Long) {
        val maxAmount = _uiState.value.amount.max

        if (sats > maxAmount) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__spending_amount__error_max__title),
                    description = context.getString(R.string.lightning__spending_amount__error_max__description)
                        .replace("{amount}", maxAmount.formatToModernDisplay()),
                )
            }
            return
        }

        _uiState.update { it.copy(amount = it.amount.copy(sats = sats)) }
    }

    fun onAmountContinue() {
        viewModelScope.launch {
            val speed = settingsStore.data.first().defaultTransactionSpeed
            val amountSats = _uiState.value.amount.sats

            if (amountSats <= 0) {
                _uiState.update { it.copy(networkFee = 0L) }
                return@launch
            }

            val fee = lightningRepo.calculateTotalFee(
                amountSats = amountSats.toULong(),
                speed = speed,
            ).getOrDefault(1000uL)

            _uiState.update { it.copy(networkFee = fee.toLong()) }
        }
    }

    fun onConfirm() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            @Suppress("ForbiddenComment")
            // TODO: pass customFeeRate to ldk-node when supported
            lightningRepo.openChannel(
                peer = requireNotNull(_uiState.value.peer),
                channelAmountSats = _uiState.value.amount.sats.toULong(),
            ).mapCatching { result ->
                awaitChannelPendingEvent(result.userChannelId).mapCatching { event ->
                    val (txId, _) = event.fundingTxo

                    transferRepo.createTransfer(
                        type = TransferType.MANUAL_SETUP,
                        amountSats = result.channelAmountSats.toLong(),
                        channelId = event.channelId,
                        fundingTxId = txId,
                    )
                }.getOrThrow()
            }.onSuccess {
                setEffect(SideEffect.ConfirmSuccess)
            }.onFailure { e ->
                val error = e.message.orEmpty()
                Logger.warn("Error opening channel with peer: '${_uiState.value.peer}': '$error'")
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__error_channel_purchase),
                    description = context.getString(R.string.lightning__error_channel_setup_msg)
                        .replace("{raw}", error),
                )
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun awaitChannelPendingEvent(userChannelId: UserChannelId): Result<Event.ChannelPending> {
        return lightningRepo.nodeEvents.watchUntil { event ->
            when (event) {
                is Event.ChannelClosed -> if (event.userChannelId == userChannelId) {
                    WatchResult.Complete(Result.failure(Exception("${event.reason}")))
                } else {
                    WatchResult.Continue()
                }

                is Event.ChannelPending -> if (event.userChannelId == userChannelId) {
                    WatchResult.Complete(Result.success(event))
                } else {
                    WatchResult.Continue()
                }

                else -> WatchResult.Continue()
            }
        }
    }
}

interface ExternalNodeContract {
    data class UiState(
        val isLoading: Boolean = false,
        val peer: PeerDetails? = null,
        val amount: Amount = Amount(),
        val networkFee: Long = 0,
    ) {
        data class Amount(
            val sats: Long = 0,
            val max: Long = 0,
        )
    }

    sealed interface SideEffect {
        data object ConnectionSuccess : SideEffect
        data object ConfirmSuccess : SideEffect
    }
}
