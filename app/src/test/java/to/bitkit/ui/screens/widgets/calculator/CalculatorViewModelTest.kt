package to.bitkit.ui.screens.widgets.calculator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.BaseUnitTest
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

    private lateinit var sut: CalculatorViewModel

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        widgetsData.value = WidgetsData()
        currencyState.value = CurrencyState()
        lastConvertedSats = 0L

        whenever(widgetsRepo.widgetsDataFlow).thenReturn(widgetsData)
        whenever(widgetsRepo.showWidgetTitles).thenReturn(flowOf(true))
        whenever(currencyRepo.currencyState).thenReturn(currencyState)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer {
            val sats = it.getArgument<Long>(0)
            lastConvertedSats = sats
            ConvertedAmount(
                value = BigDecimal(currentFiatValue()),
                formatted = currentFiatValue(),
                symbol = currencyState.value.currencySymbol,
                currency = currencyState.value.selectedCurrency,
                flag = "",
                sats = sats,
            )
        }
        whenever(currencyRepo.convertFiatToSats(any<BigDecimal>(), anyOrNull())).thenAnswer { 12_345uL }
        whenever { widgetsRepo.updateCalculatorValues(any()) }.thenAnswer {
            val calculatorValues = it.getArgument<CalculatorValues>(0)
            widgetsData.value = widgetsData.value.copy(calculatorValues = calculatorValues)
            Unit
        }
    }

    @Test
    fun `init hydrates fiat value from stored btc`() = test {
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
        assertEquals("6.25", widgetsData.value.calculatorValues.fiatValue)
    }

    @Test
    fun `init refreshes fiat value when stored fiat already exists`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "1.00",
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
        assertEquals(CalculatorValues(btcValue = "", fiatValue = ""), widgetsData.value.calculatorValues)
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
        assertEquals(CalculatorValues(btcValue = "", fiatValue = ""), widgetsData.value.calculatorValues)
    }

    @Test
    fun `currency change refreshes fiat from active btc value`() = test {
        widgetsData.value = WidgetsData(
            calculatorValues = CalculatorValues(
                btcValue = "10000",
                fiatValue = "6.25",
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
    }

    private fun createSut() = CalculatorViewModel(
        widgetsRepo = widgetsRepo,
        currencyRepo = currencyRepo,
    )

    private fun currentFiatValue() = when (currencyState.value.selectedCurrency) {
        "EUR" -> "5.50"
        else -> "6.25"
    }
}
