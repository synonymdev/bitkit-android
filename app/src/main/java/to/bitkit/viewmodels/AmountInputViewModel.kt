package to.bitkit.viewmodels

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.toLongOrDefault
import to.bitkit.models.CLASSIC_DECIMALS
import to.bitkit.models.FIAT_DECIMALS
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.SATS_GROUPING_SEPARATOR
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.formatToClassicDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.KEY_DECIMAL
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPadType
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class AmountInputViewModel @Inject constructor(
    private val amountInputHandler: AmountInputHandler,
) : ViewModel() {
    companion object {
        const val MAX_AMOUNT = 999_999_999L
        const val MAX_MODERN_LENGTH = 10
        const val MAX_DECIMAL_LENGTH = 20
        const val ERROR_DELAY_MS = 500L

        const val PLACEHOLDER_CLASSIC = "0.00000000"
        const val PLACEHOLDER_MODERN = "0"
        const val PLACEHOLDER_FIAT = "0.00"
        const val PLACEHOLDER_CLASSIC_DECIMALS = ".00000000"
        const val PLACEHOLDER_MODERN_DECIMALS = ""
        const val PLACEHOLDER_FIAT_DECIMALS = ".00"
    }

    private val _uiState = MutableStateFlow(AmountInputUiState())
    val uiState: StateFlow<AmountInputUiState> = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<AmountInputEffect>(extraBufferCapacity = 1)
    val effect: SharedFlow<AmountInputEffect> = _effect.asSharedFlow()

    private var maxAmount: Long = MAX_AMOUNT
    private var rawInputText: String = ""

    fun setMaxAmount(amount: Long) {
        maxAmount = if (amount > 0) amount.coerceAtMost(MAX_AMOUNT) else MAX_AMOUNT
    }

    fun handleNumberPadInput(
        key: String,
        currencyState: CurrencyState,
    ) {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()
        val maxLength = getMaxLength(currencyState)
        val maxDecimals = getMaxDecimals(currencyState)

        val newText = handleInput(key = key, current = rawInputText, maxLength, maxDecimals)

        if (newText == rawInputText && key != KEY_DELETE) {
            triggerErrorState(key)
            return
        }

        // For modern Bitcoin (integer input), format the final amount
        if (primaryDisplay == PrimaryDisplay.BITCOIN && isModern) {
            val newAmount = convertToSats(newText, primaryDisplay, isModern = true)

            if (key == KEY_DELETE || newAmount <= maxAmount) {
                rawInputText = newText
                _uiState.update {
                    it.copy(
                        text = formatDisplayTextFromAmount(newAmount, primaryDisplay, isModern = true),
                        sats = newAmount,
                        errorKey = null
                    )
                }
            } else {
                emitMaxExceeded()
                triggerErrorState(key)
            }
        } else {
            // For decimal input, check limits before updating state
            if (newText.isNotEmpty()) {
                val newAmount = convertToSats(newText, primaryDisplay, isModern)
                if (key == KEY_DELETE || newAmount <= maxAmount) {
                    // Update both raw input and display text
                    rawInputText = newText
                    _uiState.update {
                        it.copy(
                            text = if (primaryDisplay == PrimaryDisplay.FIAT) {
                                formatFiatGroupingOnly(newText)
                            } else {
                                newText
                            },
                            sats = newAmount,
                            errorKey = null
                        )
                    }
                } else {
                    emitMaxExceeded()
                    triggerErrorState(key)
                }
            } else {
                // If input is empty, set sats to 0
                rawInputText = newText
                _uiState.update {
                    it.copy(
                        sats = 0,
                        text = "",
                        errorKey = null
                    )
                }
            }
        }
    }

    fun setSats(sats: Long, currencyState: CurrencyState) {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()

        _uiState.update {
            it.copy(
                sats = sats,
                text = formatDisplayTextFromAmount(sats, primaryDisplay, isModern)
            )
        }
        // Update raw input text based on the formatted display
        rawInputText = when (primaryDisplay) {
            PrimaryDisplay.FIAT -> _uiState.value.text.replace(",", "")
            else -> _uiState.value.text
        }
    }

    /**
     * Toggles between Bitcoin and Fiat display modes while preserving input
     */
    fun switchUnit(currencies: CurrencyState) {
        viewModelScope.launch {
            val currentRawInput = rawInputText
            val isModern = currencies.displayUnit.isModern()
            val newPrimaryDisplay = amountInputHandler.switchUnit(currencies.primaryDisplay)

            // Update display text when currency changes
            val amountSats = _uiState.value.sats
            if (amountSats > 0) {
                _uiState.update {
                    it.copy(
                        text = formatDisplayTextFromAmount(amountSats, newPrimaryDisplay, isModern)
                    )
                }
                // Update raw input text based on the new display
                rawInputText = when (newPrimaryDisplay) {
                    PrimaryDisplay.FIAT -> _uiState.value.text.replace(",", "")
                    else -> _uiState.value.text
                }
            } else if (currentRawInput.isNotEmpty()) {
                // Convert the raw input from the old currency to the new currency
                when (newPrimaryDisplay) {
                    PrimaryDisplay.FIAT -> {
                        // Converting from bitcoin to fiat
                        val sats = convertBitcoinToSats(currentRawInput, isModern)
                        val converted = amountInputHandler.convertSatsToFiatString(sats)
                        if (converted.isNotEmpty()) {
                            rawInputText = converted.replace(",", "")
                            _uiState.update { it.copy(text = formatFiatGroupingOnly(rawInputText)) }
                        }
                    }

                    PrimaryDisplay.BITCOIN -> {
                        // Converting from fiat to bitcoin
                        val sats = convertFiatToSats(currentRawInput)
                        if (sats != null) {
                            rawInputText = formatBitcoinFromSats(sats, isModern)
                            _uiState.update { it.copy(text = rawInputText) }
                        }
                    }
                }
            }
        }
    }

    fun getNumberPadType(currencyState: CurrencyState): NumberPadType {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()
        val isBtc = primaryDisplay == PrimaryDisplay.BITCOIN
        return if (isModern && isBtc) NumberPadType.INTEGER else NumberPadType.DECIMAL
    }

    fun getMaxLength(currencyState: CurrencyState): Int {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()
        val isBtc = primaryDisplay == PrimaryDisplay.BITCOIN
        return if (isModern && isBtc) MAX_MODERN_LENGTH else MAX_DECIMAL_LENGTH
    }

    fun getMaxDecimals(currencyState: CurrencyState): Int {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()
        val isBtc = primaryDisplay == PrimaryDisplay.BITCOIN
        return if (isModern && isBtc) 0 else (if (isBtc) CLASSIC_DECIMALS else FIAT_DECIMALS)
    }

    @Suppress("NestedBlockDepth")
    fun getPlaceholder(currencyState: CurrencyState): String {
        val primaryDisplay = currencyState.primaryDisplay
        val isModern = currencyState.displayUnit.isModern()
        if (_uiState.value.text.isEmpty()) {
            return when (primaryDisplay) {
                PrimaryDisplay.BITCOIN -> if (isModern) PLACEHOLDER_MODERN else PLACEHOLDER_CLASSIC
                PrimaryDisplay.FIAT -> PLACEHOLDER_FIAT
            }
        } else {
            return when (primaryDisplay) {
                PrimaryDisplay.BITCOIN -> {
                    if (isModern) {
                        PLACEHOLDER_MODERN_DECIMALS
                    } else {
                        if (_uiState.value.text.contains(".")) {
                            val parts = _uiState.value.text.split(".", limit = 2)
                            val decimalPart = if (parts.size > 1) parts[1] else ""
                            val remainingDecimals = CLASSIC_DECIMALS - decimalPart.length
                            if (remainingDecimals > 0) "0".repeat(remainingDecimals) else ""
                        } else {
                            PLACEHOLDER_CLASSIC_DECIMALS
                        }
                    }
                }

                PrimaryDisplay.FIAT -> {
                    if (_uiState.value.text.contains(".")) {
                        val parts = _uiState.value.text.split(".", limit = 2)
                        val decimalPart = if (parts.size > 1) parts[1] else ""
                        val remainingDecimals = FIAT_DECIMALS - decimalPart.length
                        if (remainingDecimals > 0) "0".repeat(remainingDecimals) else ""
                    } else {
                        PLACEHOLDER_FIAT_DECIMALS
                    }
                }
            }
        }
    }

    fun clearInput() {
        rawInputText = ""
        maxAmount = MAX_AMOUNT
        _uiState.update { AmountInputUiState() }
    }

    private fun emitMaxExceeded() {
        if (maxAmount < MAX_AMOUNT) {
            _effect.tryEmit(AmountInputEffect.MaxExceeded)
        }
    }

    private fun triggerErrorState(key: String) {
        _uiState.update { it.copy(errorKey = key) }
        viewModelScope.launch {
            delay(ERROR_DELAY_MS)
            _uiState.update { it.copy(errorKey = null) }
        }
    }

    private fun formatDisplayTextFromAmount(
        amountSats: Long,
        primaryDisplay: PrimaryDisplay,
        isModern: Boolean,
    ): String {
        if (amountSats == 0L) return ""
        return when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> formatBitcoinFromSats(amountSats, isModern)
            PrimaryDisplay.FIAT -> amountInputHandler.convertSatsToFiatString(amountSats)
        }
    }

    @Suppress("ReturnCount")
    private fun formatFiatGroupingOnly(text: String): String {
        // Remove any existing grouping separators for parsing
        val cleanText = text.replace(",", "")

        // If the text ends with a decimal point, don't format it (preserve the decimal point)
        if (text.endsWith(".")) {
            // Only add grouping separators to the integer part
            val integerPart = cleanText.dropLast(1) // Remove the decimal point
            integerPart.toIntOrNull()?.let { intValue ->
                val formatter = NumberFormat.getNumberInstance(Locale.US)
                return formatter.format(intValue) + "."
            }
            return text
        }

        // If the text contains a decimal point, preserve the decimal structure
        if (text.contains(".")) {
            val parts = cleanText.split(".", limit = 2)
            val integerPart = parts[0]
            val decimalPart = if (parts.size > 1) parts[1] else ""

            // Format only the integer part with grouping separators
            integerPart.toIntOrNull()?.let { intValue ->
                val formatter = NumberFormat.getNumberInstance(Locale.US)
                return formatter.format(intValue) + "." + decimalPart
            }
            return text
        }

        // For integer-only input, add grouping separators
        cleanText.toIntOrNull()?.let { intValue ->
            val formatter = NumberFormat.getNumberInstance(Locale.US)
            return formatter.format(intValue)
        }

        return text
    }

    private fun formatBitcoinFromSats(sats: Long, isModern: Boolean): String {
        return if (isModern) sats.formatToModernDisplay() else sats.formatToClassicDisplay()
    }

    private fun convertToSats(
        text: String,
        primaryDisplay: PrimaryDisplay,
        isModern: Boolean,
    ): Long {
        if (text.isEmpty()) return 0L
        return when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> convertBitcoinToSats(text, isModern)
            PrimaryDisplay.FIAT -> convertFiatToSats(text) ?: 0
        }
    }

    private fun convertBitcoinToSats(text: String, isModern: Boolean): Long {
        if (text.isEmpty()) return 0

        return if (isModern) {
            text.replace("$SATS_GROUPING_SEPARATOR", "").toLongOrDefault()
        } else {
            runCatching {
                val btcBigDecimal = BigDecimal(text)
                val satsBigDecimal = btcBigDecimal.multiply(BigDecimal(SATS_IN_BTC))
                satsBigDecimal.toLong()
            }.getOrDefault(0)
        }
    }

    private fun convertFiatToSats(text: String): Long? {
        return text.replace(",", "")
            .toDoubleOrNull()
            ?.let { fiat -> amountInputHandler.convertFiatToSats(fiat) }
    }

    private fun handleInput(
        key: String,
        current: String,
        maxLength: Int,
        maxDecimals: Int,
    ): String {
        return if (maxDecimals == 0) {
            handleIntegerInput(key, current, maxLength)
        } else {
            handleDecimalInput(key, current, maxLength, maxDecimals)
        }
    }

    private fun handleIntegerInput(key: String, current: String, maxLength: Int): String {
        if (key == KEY_DELETE) return current.dropLast(1)

        if (current == "0") return key
        if (current.length >= maxLength) return current

        return current + key
    }

    @Suppress("ReturnCount")
    private fun handleDecimalInput(
        key: String,
        current: String,
        maxLength: Int,
        maxDecimals: Int,
    ): String {
        val parts = current.split(".", limit = 2)
        val decimalPart = if (parts.size > 1) parts[1] else ""

        if (key == KEY_DELETE) {
            if (current == "0.") return ""
            return current.dropLast(1)
        }

        // Handle leading zeros - replace "0" with new digit but allow "0."
        if (current == "0" && key != ".") return key

        // Limit to maxLength
        if (current.length >= maxLength) return current

        // Limit decimal places
        if (decimalPart.length >= maxDecimals) return current

        if (key == KEY_DECIMAL) {
            // No multiple decimal symbols
            if (current.contains(".")) return current
            // Add leading zero
            if (current.isEmpty()) return "0."
        }

        return current + key
    }
}

data class AmountInputUiState(
    val sats: Long = 0L,
    val text: String = "",
    val errorKey: String? = null,
)

sealed interface AmountInputEffect {
    data object MaxExceeded : AmountInputEffect
}

@SuppressLint("ViewModelConstructorInComposable")
@Composable
fun previewAmountInputViewModel(
    sats: Long = 4_567,
    currencies: CurrencyState = LocalCurrencies.current,
) = AmountInputViewModel(AmountInputHandler.stub()).also {
    it.setSats(sats, currencies)
}
