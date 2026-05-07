package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.removeSpaces
import to.bitkit.ext.toLongOrDefault
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CLASSIC_DECIMALS
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.WidgetType
import to.bitkit.models.asBtc
import to.bitkit.models.formatCurrency
import to.bitkit.models.formatToModernDisplay
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.WidgetsRepo
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject

internal const val CALCULATOR_FIAT_DECIMAL_PLACES = 2

@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()
    private var pendingValues: CalculatorValues? = null
    private var lastCurrencyKey: CalculatorCurrencyKey? = null

    val isCalculatorWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.CALCULATOR }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val showWidgetTitles: StateFlow<Boolean> = widgetsRepo.showWidgetTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = true
        )

    init {
        observeCalculatorState()
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.CALCULATOR)
        }
    }

    fun saveWidget() {
        viewModelScope.launch {
            widgetsRepo.addWidget(WidgetType.CALCULATOR)
        }
    }

    fun onBtcInputChanged(rawValue: String) {
        val displayUnit = _uiState.value.displayUnit
        val btcValue = if (displayUnit.isModern()) {
            sanitizeIntegerInput(rawValue)
        } else {
            sanitizeDecimalInput(rawValue)
        }
        val fiatValue = if (btcValue.isEmpty()) {
            ""
        } else {
            convertBtcToFiat(btcValue, displayUnit)
        }
        updateCalculatorValues(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
            )
        )
    }

    fun onFiatInputChanged(rawValue: String) {
        val displayUnit = _uiState.value.displayUnit
        val fiatValue = sanitizeDecimalInput(rawValue, maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES)
        val btcValue = if (fiatValue.isEmpty()) {
            ""
        } else {
            val converted = convertFiatToBtc(fiatValue, displayUnit)
            if (displayUnit.isModern()) {
                converted.filter { it.isDigit() }
            } else {
                converted
            }
        }
        updateCalculatorValues(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
            )
        )
    }

    private fun observeCalculatorState() {
        viewModelScope.launch {
            combine(
                widgetsRepo.widgetsDataFlow
                    .map { it.calculatorValues }
                    .distinctUntilChanged(),
                currencyRepo.currencyState,
            ) { calculatorValues, currencyState ->
                calculatorValues to currencyState
            }.collect { (storedValues, currencyState) ->
                val activeValues = resolveActiveValues(storedValues)
                val nextValues = deriveValuesForCurrency(
                    activeValues = activeValues,
                    storedValues = storedValues,
                    currencyState = currencyState,
                )
                updateUiState(nextValues, currencyState)
            }
        }
    }

    private fun resolveActiveValues(storedValues: CalculatorValues): CalculatorValues {
        val pending = pendingValues ?: return storedValues
        if (pending == storedValues) {
            pendingValues = null
            return storedValues
        }
        return pending
    }

    private fun deriveValuesForCurrency(
        activeValues: CalculatorValues,
        storedValues: CalculatorValues,
        currencyState: CurrencyState,
    ): CalculatorValues {
        val currencyKey = CalculatorCurrencyKey(
            selectedCurrency = currencyState.selectedCurrency,
            displayUnit = currencyState.displayUnit,
        )
        val previousCurrencyKey = lastCurrencyKey
        lastCurrencyKey = currencyKey

        val currencyChanged = previousCurrencyKey != null && previousCurrencyKey != currencyKey
        val isInitialSync = previousCurrencyKey == null
        val shouldRefreshFiat = isInitialSync || currencyChanged || shouldHydrateFiatFromStoredBtc(
            storedBtcValue = storedValues.btcValue,
            storedFiatValue = storedValues.fiatValue,
            currentFiatValue = activeValues.fiatValue,
            displayUnit = currencyState.displayUnit,
        )

        if (!shouldRefreshFiat) {
            return activeValues
        }
        if (activeValues.btcValue.isEmpty() || isZeroBtcValue(activeValues.btcValue, currencyState.displayUnit)) {
            return activeValues
        }

        val convertedFiat = convertBtcToFiat(
            btcValue = activeValues.btcValue,
            displayUnit = currencyState.displayUnit,
        )
        if (convertedFiat.isEmpty()) {
            return activeValues
        }

        val updatedValues = activeValues.copy(fiatValue = convertedFiat)
        updateCalculatorValues(updatedValues)
        return updatedValues
    }

    private fun updateCalculatorValues(calculatorValues: CalculatorValues) {
        pendingValues = calculatorValues
        _uiState.update {
            it.copy(
                btcValue = calculatorValues.btcValue,
                fiatValue = calculatorValues.fiatValue,
            )
        }
        viewModelScope.launch {
            widgetsRepo.updateCalculatorValues(calculatorValues)
        }
    }

    private fun updateUiState(
        calculatorValues: CalculatorValues,
        currencyState: CurrencyState,
    ) {
        _uiState.update {
            it.copy(
                btcValue = calculatorValues.btcValue,
                fiatValue = calculatorValues.fiatValue,
                displayUnit = currencyState.displayUnit,
                currencySymbol = currencyState.currencySymbol,
                selectedCurrency = currencyState.selectedCurrency,
            )
        }
    }

    private fun convertBtcToFiat(
        btcValue: String,
        displayUnit: BitcoinDisplayUnit,
    ): String {
        val satsOrBtc = btcValue.removeSpaces()
        val satsLong = when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> satsOrBtc.toLongOrDefault()
            BitcoinDisplayUnit.CLASSIC -> {
                val btcDecimal = satsOrBtc.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val satsDecimal = btcDecimal.multiply(BigDecimal(SATS_IN_BTC))
                val roundedNumber = satsDecimal.setScale(0, RoundingMode.HALF_UP)
                roundedNumber.toLong()
            }
        }

        return currencyRepo.convertSatsToFiat(sats = satsLong).getOrNull()?.formatted.orEmpty()
    }

    private fun convertFiatToBtc(
        fiatValue: String,
        displayUnit: BitcoinDisplayUnit,
    ): String {
        val fiatDecimal = fiatValue.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val satsValue = currencyRepo.convertFiatToSats(fiatDecimal).getOrNull()?.toLong() ?: 0L

        return when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> satsValue.formatToModernDisplay()
            BitcoinDisplayUnit.CLASSIC -> {
                satsValue.asBtc()
                    .formatCurrency(decimalPlaces = CLASSIC_DECIMALS)
                    .orEmpty()
            }
        }
    }
}

@Immutable
data class CalculatorUiState(
    val btcValue: String = CalculatorValues().btcValue,
    val fiatValue: String = CalculatorValues().fiatValue,
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val currencySymbol: String = "$",
    val selectedCurrency: String = "USD",
)

private data class CalculatorCurrencyKey(
    val selectedCurrency: String,
    val displayUnit: BitcoinDisplayUnit,
)

internal fun shouldHydrateFiatFromStoredBtc(
    storedBtcValue: String,
    storedFiatValue: String,
    currentFiatValue: String,
    displayUnit: BitcoinDisplayUnit,
): Boolean {
    if (storedBtcValue.isEmpty()) {
        return false
    }
    if (isZeroBtcValue(storedBtcValue, displayUnit)) {
        return false
    }
    if (storedFiatValue.isNotEmpty()) {
        return false
    }
    return currentFiatValue.isEmpty()
}

internal fun isZeroBtcValue(
    btcValue: String,
    displayUnit: BitcoinDisplayUnit,
): Boolean = when (displayUnit) {
    BitcoinDisplayUnit.MODERN -> btcValue == "0"
    BitcoinDisplayUnit.CLASSIC -> btcValue.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0
}

internal fun sanitizeIntegerInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return digits
    return digits.trimStart('0').ifEmpty { "0" }
}

internal fun sanitizeDecimalInput(
    raw: String,
    locale: Locale = Locale.getDefault(),
    maxDecimalPlaces: Int? = null,
): String {
    val localDecimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val normalized = if (localDecimal == ',') raw.replace(',', '.') else raw
    val filtered = normalized.filter { it.isDigit() || it == '.' }
    val dotIndex = filtered.indexOf('.')
    val singleDot = if (dotIndex == -1) {
        filtered
    } else {
        filtered.substring(0, dotIndex + 1) +
            filtered.substring(dotIndex + 1).replace(".", "")
    }
    if (maxDecimalPlaces == null) return singleDot
    val cappedDot = singleDot.indexOf('.')
    if (cappedDot == -1) return singleDot
    val fraction = singleDot.substring(cappedDot + 1)
    if (fraction.length <= maxDecimalPlaces) return singleDot
    return singleDot.substring(0, cappedDot + 1) + fraction.take(maxDecimalPlaces)
}
