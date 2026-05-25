package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
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
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FxRate
import to.bitkit.models.MoneyType
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.models.widget.resolveCalculatorSatsValue
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
    private var lastRates: ImmutableList<FxRate>? = null
    private var activeInput: MoneyType? = null

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

    fun onInputSelected(input: MoneyType) {
        activeInput = input
    }

    fun onInputDismissed() {
        activeInput = null
    }

    fun onBtcInputChanged(rawValue: String) {
        activeInput = MoneyType.BITCOIN
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
        activeInput = MoneyType.FIAT
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
        val previousRates = lastRates
        lastCurrencyKey = currencyKey
        lastRates = currencyState.rates

        val currencyChanged = previousCurrencyKey != null &&
            previousCurrencyKey.selectedCurrency != currencyKey.selectedCurrency
        val displayUnitChanged = previousCurrencyKey != null &&
            previousCurrencyKey.displayUnit != currencyKey.displayUnit
        val ratesChanged = previousRates != null && previousRates != currencyState.rates
        val isInitialSync = previousCurrencyKey == null
        val nextActiveValues = deriveActiveValues(
            activeValues = activeValues,
            isInitialSync = isInitialSync,
            displayUnitChanged = displayUnitChanged,
            currencyKey = currencyKey,
        )
        val refreshSource = nextActiveValues.refreshSource(activeInput)
        if (refreshSource == MoneyType.FIAT) {
            return refreshBitcoinFromFiat(
                activeValues = activeValues,
                nextActiveValues = nextActiveValues,
                displayUnit = currencyState.displayUnit,
            )
        }

        val shouldRefreshFiat = isInitialSync || currencyChanged || ratesChanged || shouldHydrateFiatFromStoredBtc(
            storedBtcValue = storedValues.btcValue,
            storedFiatValue = storedValues.fiatValue,
            currentFiatValue = nextActiveValues.fiatValue,
            displayUnit = currencyState.displayUnit,
        )

        if (!shouldRefreshFiat) return persistCanonicalValues(activeValues, nextActiveValues)

        return refreshFiatFromBitcoin(
            activeValues = activeValues,
            nextActiveValues = nextActiveValues,
            displayUnit = currencyState.displayUnit,
        )
    }

    private fun refreshFiatFromBitcoin(
        activeValues: CalculatorValues,
        nextActiveValues: CalculatorValues,
        displayUnit: BitcoinDisplayUnit,
    ): CalculatorValues {
        if (nextActiveValues.btcValue.isEmpty() ||
            isZeroBtcValue(nextActiveValues.btcValue, displayUnit)
        ) {
            return persistCanonicalValues(activeValues, nextActiveValues)
        }

        val convertedFiat = convertSatsToFiat(nextActiveValues.resolveCalculatorSatsValue())
        if (convertedFiat.isEmpty()) return persistCanonicalValues(activeValues, nextActiveValues)

        val updatedValues = nextActiveValues.copy(fiatValue = convertedFiat)
        persistCanonicalValuesIfNeeded(
            activeValues = activeValues,
            nextActiveValues = updatedValues,
        )
        return updatedValues
    }

    private fun refreshBitcoinFromFiat(
        activeValues: CalculatorValues,
        nextActiveValues: CalculatorValues,
        displayUnit: BitcoinDisplayUnit,
    ): CalculatorValues {
        if (nextActiveValues.fiatValue.isEmpty()) return persistCanonicalValues(activeValues, nextActiveValues)

        val satsValue = convertFiatToSats(nextActiveValues.fiatValue)
        val updatedValues = nextActiveValues.copy(
            btcValue = calculatorSatsToBtcValue(satsValue, displayUnit),
            satsValue = satsValue,
            displayUnit = displayUnit,
        )
        persistCanonicalValuesIfNeeded(
            activeValues = activeValues,
            nextActiveValues = updatedValues,
        )
        return updatedValues
    }

    private fun persistCanonicalValues(
        activeValues: CalculatorValues,
        nextActiveValues: CalculatorValues,
    ): CalculatorValues {
        persistCanonicalValuesIfNeeded(
            activeValues = activeValues,
            nextActiveValues = nextActiveValues,
        )
        return nextActiveValues
    }

    private fun deriveActiveValues(
        activeValues: CalculatorValues,
        isInitialSync: Boolean,
        displayUnitChanged: Boolean,
        currencyKey: CalculatorCurrencyKey,
    ): CalculatorValues {
        if (activeValues.btcValue.isEmpty()) {
            return activeValues.copy(
                satsValue = 0L,
                displayUnit = currencyKey.displayUnit,
            )
        }

        val satsValue = activeValues.resolveSatsValue(currencyKey.displayUnit)
        val shouldCanonicalize = shouldCanonicalizeBtcValue(
            values = activeValues,
            isInitialSync = isInitialSync,
            displayUnitChanged = displayUnitChanged,
            currencyKey = currencyKey,
        )
        val btcValue = if (shouldCanonicalize) {
            calculatorSatsToBtcValue(satsValue, currencyKey.displayUnit)
        } else {
            activeValues.btcValue
        }
        return activeValues.copy(
            btcValue = btcValue,
            satsValue = satsValue,
            displayUnit = currencyKey.displayUnit,
        )
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
        }
    }

    private fun CalculatorValues.resolveSatsValue(displayUnit: BitcoinDisplayUnit): Long {
        if (satsValue != null || this.displayUnit != null || fiatValue.isEmpty()) {
            return resolveCalculatorSatsValue()
        }
        val btcSatsValue = resolveCalculatorSatsValue()
        val fiatSatsValue = convertFiatToSats(fiatValue)
        if (shouldRecoverLegacyWholeBtcFromFiat(fiatSatsValue, displayUnit)) {
            return fiatSatsValue
        }
        return btcSatsValue
    }

    private fun CalculatorValues.shouldRecoverLegacyWholeBtcFromFiat(
        fiatSatsValue: Long,
        displayUnit: BitcoinDisplayUnit,
    ): Boolean {
        if (displayUnit.isModern()) return false
        if (btcValue.any { it == '.' || it == ',' }) return false
        val fiatBtcValue = calculatorSatsToBtcValue(fiatSatsValue, displayUnit).toBigDecimalOrNull()
            ?: return false
        val storedBtcValue = btcValue.toBigDecimalOrNull() ?: return false
        return storedBtcValue.compareTo(fiatBtcValue) == 0
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

internal fun CalculatorValues.refreshSource(activeInput: MoneyType?): MoneyType {
    activeInput?.let { return it }
    return if (btcValue.isEmpty() && fiatValue.isNotEmpty()) {
        MoneyType.FIAT
    } else {
        MoneyType.BITCOIN
    }
}

internal fun isZeroBtcValue(
    btcValue: String,
    displayUnit: BitcoinDisplayUnit,
): Boolean = when (displayUnit) {
    BitcoinDisplayUnit.MODERN -> btcValue == "0"
    BitcoinDisplayUnit.CLASSIC -> btcValue.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0
}

private fun shouldCanonicalizeBtcValue(
    values: CalculatorValues,
    isInitialSync: Boolean,
    displayUnitChanged: Boolean,
    currencyKey: CalculatorCurrencyKey,
): Boolean {
    return isInitialSync ||
        displayUnitChanged ||
        values.satsValue == null ||
        values.displayUnit != currencyKey.displayUnit
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
