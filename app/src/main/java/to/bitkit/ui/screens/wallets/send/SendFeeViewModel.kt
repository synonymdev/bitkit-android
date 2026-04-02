package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.models.FeeRate
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.viewmodels.SendUiState
import javax.inject.Inject

private const val MAX_DIGITS = 3
private const val MAX_VALUE = 999u
private const val MAX_RATIO = 0.5

@HiltViewModel
class SendFeeViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val currencyRepo: CurrencyRepo,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendFeeUiState())
    val uiState = _uiState.asStateFlow()

    private lateinit var sendUiState: SendUiState
    private var maxSatsPerVByte: UInt = MAX_VALUE
    private var maxFee: ULong = 0u

    fun init(sendUiState: SendUiState) {
        this.sendUiState = sendUiState
        this.maxFee = getFeeLimit()
        val selected = FeeRate.fromSpeed(sendUiState.speed)
        val fees = sendUiState.fees

        viewModelScope.launch {
            val custom = when (val speed = sendUiState.speed) {
                is TransactionSpeed.Custom -> speed
                else -> {
                    val settingsSpeed = settingsStore.data.first().defaultTransactionSpeed
                    val satsPerVByte = when (settingsSpeed) {
                        is TransactionSpeed.Custom -> settingsSpeed.satsPerVByte
                        else -> sendUiState.feeRates?.slow ?: 1u
                    }
                    TransactionSpeed.Custom(satsPerVByte)
                }
            }
            calculateMaxSatPerVByte()
            val disabledRates = fees.filter { it.value.toULong() > maxFee }.keys.toImmutableSet()
            _uiState.update {
                it.copy(
                    selected = selected,
                    fees = fees,
                    custom = custom,
                    input = custom.satsPerVByte.toString().takeIf { custom.satsPerVByte > 0u } ?: "",
                    disabledRates = disabledRates,
                )
            }
            updateTotalFeeText()
        }
    }

    private fun getFeeLimit(): ULong {
        val totalBalance = walletRepo.balanceState.value.totalOnchainSats
        val halfBalance = (totalBalance.toDouble() * MAX_RATIO).toULong()
        val remainingFunds = maxOf(0u, totalBalance - sendUiState.amount)
        return minOf(halfBalance, remainingFunds)
    }

    fun onKeyPress(key: String) {
        val currentInput = _uiState.value.input
        val newInput = when (key) {
            KEY_DELETE -> if (currentInput.isNotEmpty()) currentInput.dropLast(1) else ""
            else -> if (currentInput.length < MAX_DIGITS) (currentInput + key).trimStart('0') else currentInput
        }

        val satsPerVByte = newInput.toUIntOrNull() ?: 0u

        _uiState.update {
            it.copy(
                input = newInput,
                custom = TransactionSpeed.Custom(satsPerVByte),
            )
        }
        updateTotalFeeText()
    }

    fun validateCustomFee() {
        viewModelScope.launch {
            val isValid = performValidation()
            _uiState.update { it.copy(shouldContinue = isValid) }
        }
    }

    private suspend fun performValidation(): Boolean {
        val satsPerVByte = _uiState.value.custom?.satsPerVByte ?: 0u

        // TODO update to use minimum instead of slow when using mempool api
        val minSatsPerVByte = sendUiState.feeRates?.slow ?: 1u
        if (satsPerVByte < minSatsPerVByte) {
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.wallet__min_possible_fee_rate),
                description = context.getString(R.string.wallet__min_possible_fee_rate_msg)
            )
            return false
        }

        if (satsPerVByte > maxSatsPerVByte) {
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.wallet__max_possible_fee_rate),
                description = context.getString(R.string.wallet__max_possible_fee_rate_msg)
            )
            return false
        }

        return true
    }

    private fun updateTotalFeeText() {
        viewModelScope.launch {
            val satsPerVByte = _uiState.value.custom?.satsPerVByte ?: 0u
            val totalFee = if (satsPerVByte > 0u) calculateTotalFee(satsPerVByte).toLong() else 0L
            val fiat = if (totalFee != 0L) currencyRepo.convertSatsToFiat(totalFee).getOrNull() else null

            val totalFeeText = fiat?.let {
                context.getString(R.string.wallet__send_fee_total_fiat)
                    .replace("{feeSats}", "$totalFee")
                    .replace("{fiatSymbol}", it.symbol)
                    .replace("{fiatFormatted}", it.formatted)
            } ?: context.getString(R.string.wallet__send_fee_total).replace("{feeSats}", "$totalFee")

            _uiState.update {
                it.copy(
                    totalFeeText = totalFeeText,
                )
            }
        }
    }

    private fun calculateMaxSatPerVByte() {
        viewModelScope.launch {
            val feeFor1SatPerVByte = lightningRepo.calculateTotalFee(
                amountSats = sendUiState.amount,
                address = sendUiState.address,
                speed = TransactionSpeed.Custom(1u),
                utxosToSpend = sendUiState.selectedUtxos,
                feeRates = sendUiState.feeRates,
            ).getOrDefault(0uL)

            maxSatsPerVByte = if (feeFor1SatPerVByte > 0uL) {
                (maxFee / feeFor1SatPerVByte).toUInt().coerceAtLeast(1u)
            } else {
                MAX_VALUE
            }
        }
    }

    suspend fun calculateTotalFee(satsPerVByte: UInt): ULong {
        return lightningRepo.calculateTotalFee(
            amountSats = sendUiState.amount,
            address = sendUiState.address.takeIf { it.isNotEmpty() },
            speed = TransactionSpeed.Custom(satsPerVByte),
            utxosToSpend = sendUiState.selectedUtxos,
            feeRates = sendUiState.feeRates,
        ).getOrDefault(0u)
    }
}

@Immutable
data class SendFeeUiState(
    val fees: ImmutableMap<FeeRate, Long> = persistentMapOf(),
    val selected: FeeRate? = null,
    val custom: TransactionSpeed.Custom? = null,
    val input: String = "",
    val totalFeeText: String = "",
    val disabledRates: ImmutableSet<FeeRate> = persistentSetOf(),
    val shouldContinue: Boolean? = null,
)
