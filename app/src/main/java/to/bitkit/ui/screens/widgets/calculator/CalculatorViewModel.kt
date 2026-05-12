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
import to.bitkit.appwidget.CalculatorAppWidgetUpdater
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.WidgetType
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
    private val appWidgetUpdater: CalculatorAppWidgetUpdater,
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
        val satsValue = if (btcValue.isEmpty()) {
            0L
        } else {
            calculatorBtcValueToSats(btcValue, displayUnit)
        }
        val fiatValue = if (btcValue.isEmpty()) {
            ""
        } else {
            convertSatsToFiat(satsValue)
        }
        updateCalculatorValues(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
                satsValue = satsValue,
                displayUnit = displayUnit,
            )
        )
    }

    fun onFiatInputChanged(rawValue: String) {
        val displayUnit = _uiState.value.displayUnit
        val fiatValue = sanitizeDecimalInput(rawValue, maxDecimalPlaces = CALCULATOR_FIAT_DECIMAL_PLACES)
        val satsValue = if (fiatValue.isEmpty()) 0L else convertFiatToSats(fiatValue)
        val btcValue = if (fiatValue.isEmpty()) "" else calculatorSatsToBtcValue(satsValue, displayUnit)
        updateCalculatorValues(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
                satsValue = satsValue,
                displayUnit = displayUnit,
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
        val nextActiveValues = if (activeValues.btcValue.isEmpty()) {
            activeValues.copy(
                satsValue = 0L,
                displayUnit = currencyState.displayUnit,
            )
        } else {
            val satsValue = activeValues.resolveCalculatorSatsValue()
            activeValues.copy(
                btcValue = calculatorSatsToBtcValue(satsValue, currencyState.displayUnit),
                satsValue = satsValue,
                displayUnit = currencyState.displayUnit,
            )
        }
        val shouldRefreshFiat = isInitialSync || currencyChanged || shouldHydrateFiatFromStoredBtc(
            storedBtcValue = storedValues.btcValue,
            storedFiatValue = storedValues.fiatValue,
            currentFiatValue = nextActiveValues.fiatValue,
            displayUnit = currencyState.displayUnit,
        )

        if (!shouldRefreshFiat) {
            persistCanonicalValuesIfNeeded(
                activeValues = activeValues,
                nextActiveValues = nextActiveValues,
            )
            return nextActiveValues
        }
        if (nextActiveValues.btcValue.isEmpty() ||
            isZeroBtcValue(nextActiveValues.btcValue, currencyState.displayUnit)
        ) {
            persistCanonicalValuesIfNeeded(
                activeValues = activeValues,
                nextActiveValues = nextActiveValues,
            )
            return nextActiveValues
        }

        val convertedFiat = convertSatsToFiat(nextActiveValues.resolveCalculatorSatsValue())
        if (convertedFiat.isEmpty()) {
            persistCanonicalValuesIfNeeded(
                activeValues = activeValues,
                nextActiveValues = nextActiveValues,
            )
            return nextActiveValues
        }

        val updatedValues = nextActiveValues.copy(fiatValue = convertedFiat)
        updateCalculatorValues(updatedValues)
        return updatedValues
    }

    private fun persistCanonicalValuesIfNeeded(
        activeValues: CalculatorValues,
        nextActiveValues: CalculatorValues,
    ) {
        if (activeValues == nextActiveValues) return
        updateCalculatorValues(nextActiveValues)
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
            appWidgetUpdater.update()
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

    private fun convertSatsToFiat(satsValue: Long): String {
        return currencyRepo.convertSatsToFiat(sats = satsValue).getOrNull()
            ?.value
            ?.toCalculatorFiatRawValue()
            .orEmpty()
    }

    private fun convertFiatToSats(fiatValue: String): Long {
        val fiatDecimal = fiatValue.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return currencyRepo.convertFiatToSats(fiatDecimal).getOrNull()?.toLong() ?: 0L
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

private fun BigDecimal.toCalculatorFiatRawValue(): String =
    setScale(CALCULATOR_FIAT_DECIMAL_PLACES, RoundingMode.HALF_UP).toPlainString()

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
