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
import org.lightningdevkit.ldknode.UserChannelId
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.WatchResult
import to.bitkit.ext.watchUntil
import to.bitkit.models.LnPeer
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.screens.transfer.external.ExternalNodeContract.SideEffect
import to.bitkit.ui.screens.transfer.external.ExternalNodeContract.UiState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ExternalNodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
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
                val maxAmount = walletRepo.getMaxSendAmount()
                _uiState.update { it.copy(amount = it.amount.copy(max = maxAmount.toLong())) }
            }
        }
    }

    fun onConnectionContinue(peer: LnPeer) {
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
            val result = LnPeer.parseUri(uriString.trim())

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

        _uiState.update { it.copy(amount = it.amount.copy(sats = sats, overrideSats = null)) }
    }

    fun onAmountOverride(sats: Long) {
        val max = _uiState.value.amount.max
        val nextAmount = minOf(sats, max)

        _uiState.update { it.copy(amount = it.amount.copy(overrideSats = nextAmount)) }
    }

    fun onAmountContinue() {
        viewModelScope.launch {
            val speed = settingsStore.data.first().defaultTransactionSpeed
            val defaultSatsPerVbyte = lightningRepo.getFeeRateForSpeed(speed).getOrThrow().toUInt()
            _uiState.update {
                it.copy(
                    customFeeRate = defaultSatsPerVbyte,
                    networkFee = 0L,
                )
            }
            updateNetworkFee()
        }
    }

    fun onCustomFeeRateChange(feeRate: UInt) {
        _uiState.update { it.copy(customFeeRate = feeRate) }
        updateNetworkFee()
    }

    private fun updateNetworkFee() {
        viewModelScope.launch {
            val amountSats = _uiState.value.amount.sats
            val customFeeRate = _uiState.value.customFeeRate

            if (amountSats <= 0 || customFeeRate == 0u) {
                _uiState.update { it.copy(networkFee = 0L) }
                return@launch
            }

            val speed = customFeeRate?.let { TransactionSpeed.Custom(it) }

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

            // TODO: pass customFeeRate to ldk-node when supported
            val result = lightningRepo.openChannel(
                peer = requireNotNull(_uiState.value.peer),
                channelAmountSats = _uiState.value.amount.sats.toULong(),
            )

            if (result.isSuccess) {
                val userChannelId = requireNotNull(result.getOrNull())

                // Wait until matching channel event is received
                val initResult = awaitChannelInitResult(userChannelId)

                if (initResult.isSuccess) {
                    setEffect(SideEffect.ConfirmSuccess)
                } else {
                    failConfirm(initResult.exceptionOrNull()?.message.orEmpty())
                }
            } else {
                failConfirm(result.exceptionOrNull()?.message.orEmpty())
            }
        }
    }

    private suspend fun failConfirm(error: String) {
        Logger.warn("Error opening channel to '${_uiState.value.peer}': '$error'")
        _uiState.update { it.copy(isLoading = false) }

        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.lightning__error_channel_purchase),
            description = context.getString(R.string.lightning__error_channel_setup_msg).replace("{raw}", error),
        )
    }

    private suspend fun awaitChannelInitResult(userChannelId: UserChannelId): Result<Unit> {
        return ldkNodeEventBus.events.watchUntil { event ->
            when (event) {
                is Event.ChannelClosed -> {
                    if (event.userChannelId == userChannelId) {
                        WatchResult.Complete(Result.failure(Exception("${event.reason}")))
                    } else {
                        WatchResult.Continue()
                    }
                }

                is Event.ChannelPending -> {
                    if (event.userChannelId == userChannelId) {
                        WatchResult.Complete(Result.success(Unit))
                    } else {
                        WatchResult.Continue()
                    }
                }

                else -> WatchResult.Continue()
            }
        }
    }
}

interface ExternalNodeContract {
    data class UiState(
        val isLoading: Boolean = false,
        val peer: LnPeer? = null,
        val amount: Amount = Amount(),
        val networkFee: Long = 0,
        val customFeeRate: UInt? = null,
    ) {
        data class Amount(
            val sats: Long = 0,
            val max: Long = 0,
            val overrideSats: Long? = null,
        )
    }

    sealed interface SideEffect {
        data object ConnectionSuccess : SideEffect
        data object ConfirmSuccess : SideEffect
    }
}
