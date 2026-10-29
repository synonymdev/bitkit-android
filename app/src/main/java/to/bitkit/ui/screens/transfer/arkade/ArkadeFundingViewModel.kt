package to.bitkit.ui.screens.transfer.arkade

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatToModernDisplay
import to.bitkit.models.safe
import to.bitkit.repositories.ArkadeRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.Deposit
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.arkade.ArkadeFundingContract.UiState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Transfer to Spending through Arkade: sends savings to the wallet's Arkade boarding address. Once it confirms, the
 * Arkade wallet boards it into the operator's next batch by itself, with no fee.
 */
@HiltViewModel
class ArkadeFundingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val arkadeRepo: ArkadeRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "ArkadeFundingViewModel"

        /** How often to check the deposit until it is boarded; boarding needs 1 confirmation, then a batch. */
        private val DEPOSIT_POLL_INTERVAL = 15.seconds
    }

    /** Fast, since boarding waits for the deposit's first confirmation. */
    private val speed = TransactionSpeed.Fast

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var depositJob: Job? = null

    init {
        viewModelScope.launch {
            walletRepo.balanceState.collectLatest { balance ->
                _uiState.update { it.copy(maxSats = balance.maxSendOnchainSats.toLong()) }
                fastMaxSats().onSuccess { max -> _uiState.update { it.copy(maxSats = max) } }
            }
        }
    }

    private suspend fun fastMaxSats(): Result<Long> = runSuspendCatching {
        val address = arkadeRepo.depositAddress().getOrThrow()
        val spendable = lightningRepo.getBalancesAsync().getOrThrow().spendableOnchainBalanceSats
        val fee = lightningRepo.estimateSendAllFee(address = address, speed = speed).getOrThrow()
        (spendable.safe() - fee.safe()).toLong()
    }.onFailure { Logger.warn("Failed to estimate the most Arkade funding can send", it, context = TAG) }

    fun onAmountChange(sats: Long) {
        _uiState.update { it.copy(amountSats = sats) }
    }

    fun onMaxExceeded() {
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.lightning__spending_amount__error_max__title),
                description = context.getString(R.string.lightning__spending_amount__error_max__description)
                    .replace("{amount}", _uiState.value.maxSats.formatToModernDisplay()),
                visibilityTime = Toast.VISIBILITY_TIME_SHORT,
            )
        }
    }

    /** Prices the confirm screen: the network fee, and Arkade's boarding fee, which is 0 on the live operator. */
    fun onAmountContinue() {
        val amountSats = _uiState.value.amountSats.toULong()
        _uiState.update { it.copy(isLoading = true, networkFeeSats = 0, claimFeeSats = 0) }
        viewModelScope.launch {
            runSuspendCatching {
                val address = arkadeRepo.depositAddress().getOrThrow()
                val networkFee = if (isMaxAmount()) {
                    lightningRepo.estimateSendAllFee(address = address, speed = speed).getOrThrow()
                } else {
                    lightningRepo.calculateTotalFee(amountSats, address = address, speed = speed).getOrThrow()
                }
                val claimFee = arkadeRepo.boardingFeeSats().getOrThrow()
                _uiState.update {
                    it.copy(address = address, networkFeeSats = networkFee.toLong(), claimFeeSats = claimFee.toLong())
                }
                _effects.emit(SideEffect.AmountReady)
            }.onFailure {
                Logger.error("Failed to price Arkade funding", it, context = TAG)
                ToastEventBus.send(it)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onConfirm() {
        val state = _uiState.value
        val address = state.address ?: return
        if (state.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runSuspendCatching {
                val outcome = lightningRepo.sendOnChain(
                    address = address,
                    sats = state.amountSats.toULong(),
                    speed = speed,
                    isTransfer = true,
                    isMaxAmount = isMaxAmount(),
                ).getOrThrow()
                check(outcome is OnchainSendOutcome.Accepted) { "Arkade funding was not accepted: '$outcome'" }
                withContext(NonCancellable) {
                    runSuspendCatching {
                        lightningRepo.completeAcceptedTransferFollowup(orderId = null, txid = outcome.txid)
                    }.onFailure { Logger.warn("Failed to finish Arkade funding locally", it, context = TAG) }
                    arkadeRepo.onDepositBroadcast(outcome.txid, outcome.amountSats ?: state.amountSats.toULong())
                }
                Logger.info("Funded Arkade with '${state.amountSats}' sats in '${outcome.txid}'", context = TAG)
                _uiState.update { it.copy(txid = outcome.txid, deposit = Deposit.Looking) }
                _effects.emit(SideEffect.Sent)
                watchDeposit(outcome.txid)
            }.onFailure {
                Logger.error("Failed to fund Arkade", it, context = TAG)
                ToastEventBus.send(it)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun watchDeposit(txid: String) {
        depositJob?.cancel()
        depositJob = viewModelScope.launch {
            arkadeRepo.state.collectLatest { state ->
                val deposit = state.deposits.firstOrNull { it.txid.equals(txid, ignoreCase = true) }
                val next = when {
                    deposit?.isClaimed == true -> Deposit.Boarded
                    (lightningRepo.onchainConfirmations(txid) ?: 0u) > 0u -> Deposit.Boarding
                    else -> Deposit.Looking
                }
                _uiState.update { it.copy(deposit = next) }
                if (next == Deposit.Boarded) depositJob?.cancel()
            }
        }
        viewModelScope.launch {
            while (_uiState.value.deposit != Deposit.Boarded) {
                arkadeRepo.refreshState()
                delay(DEPOSIT_POLL_INTERVAL)
            }
        }
    }

    private fun isMaxAmount(): Boolean = _uiState.value.run { amountSats > 0 && amountSats >= maxSats }
}

interface ArkadeFundingContract {
    @Immutable
    data class UiState(
        val isLoading: Boolean = false,
        val amountSats: Long = 0,
        val maxSats: Long = 0,
        val address: String? = null,
        val networkFeeSats: Long = 0,
        /** What the operator charges to board the deposit. */
        val claimFeeSats: Long = 0,
        val txid: String? = null,
        val deposit: Deposit = Deposit.Looking,
    )

    sealed interface Deposit {
        /** Broadcast and waiting for its first confirmation. */
        data object Looking : Deposit

        /** Confirmed; the wallet boards it in the operator's next batch. */
        data object Boarding : Deposit

        /** In the Arkade balance. */
        data object Boarded : Deposit
    }

    sealed interface SideEffect {
        data object AmountReady : SideEffect
        data object Sent : SideEffect
    }
}
