package to.bitkit.ui.screens.widgets.calculator

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.WidgetsData
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.MoneyType
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorViewModelTest : BaseUnitTest() {

    private val widgetsRepo: WidgetsRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val widgetsData = MutableStateFlow(WidgetsData())
    private val currencyState = MutableStateFlow(CurrencyState())
    private var lastConvertedSats = 0L
    private var fiatConversionValue: String? = null
    private var fiatConversionFormatted: String? = null
    private var fiatToSatsValue: ULong? = 12_345uL
    private var updateCalculatorValuesCalls = 0

    private lateinit var sut: CalculatorViewModel

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        widgetsData.value = WidgetsData()
        currencyState.value = CurrencyState()
        lastConvertedSats = 0L
        fiatConversionValue = null
        fiatConversionFormatted = null
        fiatToSatsValue = 12_345uL
        updateCalculatorValuesCalls = 0

        whenever(widgetsRepo.widgetsDataFlow).thenReturn(widgetsData)
        whenever(currencyRepo.currencyState).thenReturn(currencyState)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer {
            val sats = it.getArgument<Long>(0)
            lastConvertedSats = sats
            val fiatValue = fiatConversionValue ?: currentFiatValue()
            ConvertedAmount(
                value = BigDecimal(fiatValue),
                formatted = fiatConversionFormatted ?: fiatValue,
                symbol = currencyState.value.currencySymbol,
                currency = currencyState.value.selectedCurrency,
                flag = "",
                sats = sats,
            )
        }
        whenever(currencyRepo.convertFiatToSats(any<BigDecimal>(), anyOrNull())).thenAnswer {
            fiatToSatsValue ?: throw ServiceError.CurrencyRateUnavailable()
        }
        whenever { widgetsRepo.updateCalculatorValues(any()) }.thenAnswer {
            updateCalculatorValuesCalls++
            val calculatorValues = it.getArgument<CalculatorValues>(0)
            widgetsData.value = widgetsData.value.copy(calculatorValues = calculatorValues)
            Unit
        }
    }

    @Test
    fun `init backfills sats and hydrates fiat from legacy btc`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "",
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.MODERN, widgetsData.value.calculatorValues.displayUnit)
        assertEquals("6.25", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `init refreshes fiat value when stored fiat already exists`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "1.00",
                satsValue = 10_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals("6.25", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `onBtcInputChanged sanitizes converts and persists values`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("0888,,,,,,,.00000000")
        advanceUntilIdle()

        assertEquals("88800000000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(
            CalculatorValues(
                btcValue = "88800000000",
                fiatValue = "6.25",
                satsValue = 88_800_000_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            ),
            widgetsData.value.calculatorValues,
        )
    }

    @Test
    fun `onBtcInputChanged clears both values when input is empty`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("")
        advanceUntilIdle()

        assertEquals("", sut.uiState.value.btcValue)
        assertEquals("", sut.uiState.value.fiatValue)
        assertEquals(
            CalculatorValues(
                btcValue = "",
                fiatValue = "",
                satsValue = 0L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            ),
            widgetsData.value.calculatorValues,
        )
    }

    @Test
    fun `onBtcInputChanged converts classic btc input to sats`() = test {
        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("0.00010000")
        advanceUntilIdle()

        assertEquals("0.00010000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(10_000L, lastConvertedSats)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.CLASSIC, widgetsData.value.calculatorValues.displayUnit)
    }

    @Test
    fun `onBtcInputChanged preserves classic btc text while editing`() = test {
        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("0.")
        advanceUntilIdle()

        assertEquals("0.", sut.uiState.value.btcValue)
        assertEquals("0.", widgetsData.value.calculatorValues.btcValue)
        assertEquals(0L, widgetsData.value.calculatorValues.satsValue)

        sut.onBtcInputChanged("0.1")
        advanceUntilIdle()

        assertEquals("0.1", sut.uiState.value.btcValue)
        assertEquals("0.1", widgetsData.value.calculatorValues.btcValue)
        assertEquals(10_000_000L, widgetsData.value.calculatorValues.satsValue)
    }

    @Test
    fun `onBtcInputChanged stores fiat conversion without grouping`() = test {
        fiatConversionValue = "39621.05"
        fiatConversionFormatted = "39,621.05"
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("59500000")
        advanceUntilIdle()

        assertEquals("39621.05", sut.uiState.value.fiatValue)
        assertEquals("39621.05", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `onFiatInputChanged sanitizes converts and persists values`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onFiatInputChanged("12.345")
        advanceUntilIdle()

        assertEquals("12345", sut.uiState.value.btcValue)
        assertEquals("12.34", sut.uiState.value.fiatValue)
        assertEquals(
            CalculatorValues(
                btcValue = "12345",
                fiatValue = "12.34",
                satsValue = 12_345L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            ),
            widgetsData.value.calculatorValues,
        )
    }

    @Test
    fun `onFiatInputChanged clears both values when input is empty`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onFiatInputChanged("")
        advanceUntilIdle()

        assertEquals("", sut.uiState.value.btcValue)
        assertEquals("", sut.uiState.value.fiatValue)
        assertEquals(
            CalculatorValues(
                btcValue = "",
                fiatValue = "",
                satsValue = 0L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            ),
            widgetsData.value.calculatorValues,
        )
    }

    @Test
    fun `currency change refreshes fiat from active btc value`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "6.25",
                satsValue = 10_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            )
        )
        sut = createSut()
        advanceUntilIdle()

        currencyState.value = CurrencyState(
            selectedCurrency = "EUR",
            currencySymbol = "EUR",
            displayUnit = BitcoinDisplayUnit.MODERN,
        )
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("5.50", sut.uiState.value.fiatValue)
        assertEquals("EUR", sut.uiState.value.selectedCurrency)
        assertEquals("EUR", sut.uiState.value.currencySymbol)
        assertEquals("5.50", widgetsData.value.calculatorValues.fiatValue)
        assertEquals(BitcoinDisplayUnit.MODERN, widgetsData.value.calculatorValues.displayUnit)
    }

    @Test
    fun `rate refresh preserves bitcoin input as source`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("10000")
        advanceUntilIdle()

        fiatConversionValue = "7.50"
        currencyState.value = currencyState.value.copy(
            rates = persistentListOf(fxRate(lastPrice = "75000")),
        )
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("7.50", sut.uiState.value.fiatValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals("7.50", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `rate refresh skips persist when fiat display value is unchanged`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onBtcInputChanged("10000")
        advanceUntilIdle()

        val updatesBeforeRateRefresh = updateCalculatorValuesCalls
        currencyState.value = currencyState.value.copy(
            rates = persistentListOf(fxRate(lastPrice = "62501")),
        )
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(updatesBeforeRateRefresh, updateCalculatorValuesCalls)
    }

    @Test
    fun `currency change preserves fiat input as source`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onFiatInputChanged("12.34")
        advanceUntilIdle()

        fiatToSatsValue = 54_321uL
        currencyState.value = CurrencyState(
            selectedCurrency = "EUR",
            currencySymbol = "EUR",
            displayUnit = BitcoinDisplayUnit.MODERN,
            rates = persistentListOf(fxRate(quote = "EUR", currencySymbol = "EUR")),
        )
        advanceUntilIdle()

        assertEquals("54321", sut.uiState.value.btcValue)
        assertEquals("12.34", sut.uiState.value.fiatValue)
        assertEquals(54_321L, widgetsData.value.calculatorValues.satsValue)
        assertEquals("12.34", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `active fiat refresh preserves bitcoin when conversion is unavailable`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onFiatInputChanged("12.34")
        advanceUntilIdle()

        fiatToSatsValue = null
        val updatesBeforeRateRefresh = updateCalculatorValuesCalls
        currencyState.value = currencyState.value.copy(
            rates = persistentListOf(fxRate(lastPrice = "62501")),
        )
        advanceUntilIdle()

        assertEquals("12345", sut.uiState.value.btcValue)
        assertEquals("12.34", sut.uiState.value.fiatValue)
        assertEquals(12_345L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(updatesBeforeRateRefresh, updateCalculatorValuesCalls)
    }

    @Test
    fun `active empty fiat refresh preserves bitcoin value`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onInputSelected(MoneyType.FIAT)
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "",
                satsValue = 10_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            )
        )
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("", sut.uiState.value.fiatValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
    }

    @Test
    fun `display unit change preserves btc amount`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "6.25",
                satsValue = 10_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            )
        )
        sut = createSut()
        advanceUntilIdle()

        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        advanceUntilIdle()

        assertEquals("0.00010000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(10_000L, lastConvertedSats)
        assertEquals("0.00010000", widgetsData.value.calculatorValues.btcValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.CLASSIC, widgetsData.value.calculatorValues.displayUnit)
    }

    @Test
    fun `init formats stored sats for current display unit`() = test {
        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "6.25",
                satsValue = 10_000L,
                displayUnit = BitcoinDisplayUnit.MODERN,
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("0.00010000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals("0.00010000", widgetsData.value.calculatorValues.btcValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.CLASSIC, widgetsData.value.calculatorValues.displayUnit)
    }

    @Test
    fun `init backfills legacy integer btc as sats`() = test {
        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "",
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("0.00010000", sut.uiState.value.btcValue)
        assertEquals("6.25", sut.uiState.value.fiatValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.CLASSIC, widgetsData.value.calculatorValues.displayUnit)
    }

    @Test
    fun `init preserves legacy btc when stored fiat is stale`() = test {
        fiatToSatsValue = 5_000uL
        fiatConversionValue = "12.50"
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "6.25",
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("10000", sut.uiState.value.btcValue)
        assertEquals("12.50", sut.uiState.value.fiatValue)
        assertEquals(10_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.MODERN, widgetsData.value.calculatorValues.displayUnit)
        assertEquals("12.50", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `init backfills legacy whole btc from stored fiat`() = test {
        currencyState.value = CurrencyState(displayUnit = BitcoinDisplayUnit.CLASSIC)
        fiatToSatsValue = 100_000_000uL
        fiatConversionValue = "65000.00"
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "1",
                fiatValue = "65000.00",
            )
        )
        sut = createSut()
        advanceUntilIdle()

        assertEquals("1.00000000", sut.uiState.value.btcValue)
        assertEquals("65000.00", sut.uiState.value.fiatValue)
        assertEquals(100_000_000L, widgetsData.value.calculatorValues.satsValue)
        assertEquals(BitcoinDisplayUnit.CLASSIC, widgetsData.value.calculatorValues.displayUnit)
    }

    private fun createSut() = CalculatorViewModel(
        widgetsRepo = widgetsRepo,
        currencyRepo = currencyRepo,
    )

    private fun currentFiatValue() = when (currencyState.value.selectedCurrency) {
        "EUR" -> "5.50"
        else -> "6.25"
    }

    private fun fxRate(
        quote: String = "USD",
        currencySymbol: String = "$",
        lastPrice: String = "62500",
    ) = FxRate(
        symbol = "BTC$quote",
        lastPrice = lastPrice,
        base = "BTC",
        baseName = "Bitcoin",
        quote = quote,
        quoteName = quote,
        currencySymbol = currencySymbol,
        currencyFlag = "",
        lastUpdatedAt = 1L,
    )
}
