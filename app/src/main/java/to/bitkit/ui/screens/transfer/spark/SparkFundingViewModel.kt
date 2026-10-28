package to.bitkit.ui.screens.transfer.spark

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.SparkClaimOffer
import to.bitkit.repositories.SparkEarlyClaim
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.Deposit
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.UiState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Transfer to Spending through Spark: sends savings to the Spark deposit address, then offers the early claim the
 * provider quotes for that deposit, since its fee is only known once the deposit exists.
 */
@HiltViewModel
class SparkFundingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "SparkFundingViewModel"

        /** How often to look for the deposit until Spark lists it; the early quote is then asked for once. */
        private val DEPOSIT_POLL_INTERVAL = 5.seconds

        /** Looks for the deposit for 5 minutes, after which Spark still claims it on its own. */
        private const val DEPOSIT_POLL_ATTEMPTS = 60
    }

    /** Fast, so the deposit confirms soon in case the early claim is not offered. */
    private val speed = TransactionSpeed.Fast

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var depositJob: Job? = null

    init {
        viewModelScope.launch {
            walletRepo.balanceState.collect { balance ->
                _uiState.update { it.copy(maxSats = balance.maxSendOnchainSats.toLong()) }
            }
        }
    }

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

    /** Prices the transfer for the confirm screen: the network fee and what Spark keeps to claim it at 3 confirmations. */
    fun onAmountContinue() {
        val amountSats = _uiState.value.amountSats.toULong()
        _uiState.update { it.copy(isLoading = true, networkFeeSats = 0, claimFeeSats = 0) }
        viewModelScope.launch {
            runSuspendCatching {
                val address = sparkRepo.depositAddress().getOrThrow()
                val networkFee = if (isMaxAmount()) {
                    lightningRepo.estimateSendAllFee(address = address, speed = speed).getOrThrow()
                } else {
                    lightningRepo.calculateTotalFee(amountSats, address = address, speed = speed).getOrThrow()
                }
                val claimFee = sparkRepo.estimateDepositClaimFeeSats(amountSats).getOrThrow()
                _uiState.update {
                    it.copy(address = address, networkFeeSats = networkFee.toLong(), claimFeeSats = claimFee.toLong())
                }
                _effects.emit(SideEffect.AmountReady)
            }.onFailure {
                Logger.error("Failed to price Spark funding", it, context = TAG)
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
                check(outcome is OnchainSendOutcome.Accepted) { "Spark funding was not accepted: '$outcome'" }
                withContext(NonCancellable) {
                    runSuspendCatching { lightningRepo.completeAcceptedTransferFollowup(orderId = null, txid = outcome.txid) }
                        .onFailure { Logger.warn("Failed to finish Spark funding locally", it, context = TAG) }
                    sparkRepo.onDepositBroadcast(outcome.txid, outcome.amountSats ?: state.amountSats.toULong())
                }
                Logger.info("Funded Spark with '${state.amountSats}' sats in '${outcome.txid}'", context = TAG)
                _uiState.update { it.copy(txid = outcome.txid, deposit = Deposit.Looking) }
                _effects.emit(SideEffect.Sent)
                watchDeposit(outcome.txid)
            }.onFailure {
                Logger.error("Failed to fund Spark", it, context = TAG)
                ToastEventBus.send(it)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun watchDeposit(txid: String) {
        depositJob?.cancel()
        depositJob = viewModelScope.launch {
            repeat(DEPOSIT_POLL_ATTEMPTS) {
                val offer = sparkRepo.depositClaimOffer(txid)
                    .onFailure { Logger.warn("Failed to quote Spark deposit '$txid'", it, context = TAG) }
                    .getOrNull()
                if (offer != null) {
                    _uiState.update { it.copy(deposit = Deposit.Offered(offer)) }
                    return@launch
                }
                sparkRepo.refreshState()
                delay(DEPOSIT_POLL_INTERVAL)
            }
            Logger.warn("Spark did not list deposit '$txid' in time", context = TAG)
        }
    }

    fun onClaimEarly() {
        val offer = (_uiState.value.deposit as? Deposit.Offered)?.offer as? SparkClaimOffer.Choice ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            sparkRepo.claimEarly(offer)
                .onSuccess { claim ->
                    val deposit = when (claim) {
                        is SparkEarlyClaim.Repriced -> Deposit.Offered(offer.copy(earlyFeeSats = claim.requiredFeeSats))
                        else -> Deposit.Claimed(claim, offer)
                    }
                    _uiState.update { it.copy(deposit = deposit) }
                }
                .onFailure { ToastEventBus.send(it) }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun isMaxAmount(): Boolean = _uiState.value.run { amountSats > 0 && amountSats >= maxSats }
}

interface SparkFundingContract {
    @Immutable
    data class UiState(
        val isLoading: Boolean = false,
        val amountSats: Long = 0,
        val maxSats: Long = 0,
        val address: String? = null,
        val networkFeeSats: Long = 0,
        /** Estimated fee Spark keeps to claim the deposit at 3 confirmations. */
        val claimFeeSats: Long = 0,
        val txid: String? = null,
        val deposit: Deposit = Deposit.Looking,
    )

    sealed interface Deposit {
        /** Broadcast, and Spark has not listed it yet. */
        data object Looking : Deposit

        data class Offered(val offer: SparkClaimOffer) : Deposit

        data class Claimed(val claim: SparkEarlyClaim, val offer: SparkClaimOffer.Choice) : Deposit
    }

    sealed interface SideEffect {
        data object AmountReady : SideEffect
        data object Sent : SideEffect
    }
}
