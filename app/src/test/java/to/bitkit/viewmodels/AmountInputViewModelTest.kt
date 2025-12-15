package to.bitkit.viewmodels

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FIAT_DECIMALS
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.STUB_RATE
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.services.CurrencyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.KEY_000
import to.bitkit.ui.components.KEY_DECIMAL
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPadType
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@Suppress("LargeClass")
@OptIn(ExperimentalTime::class)
class AmountInputViewModelTest : BaseUnitTest() {
    private lateinit var viewModel: AmountInputViewModel
    private lateinit var currencyRepo: CurrencyRepo

    private val currencyService = mock<CurrencyService>()
    private val settingsStore = mock<SettingsStore>()
    private val cacheStore = mock<CacheStore>()
    private val clock = mock<Clock>()

    @Suppress("SpellCheckingInspection")
    private val testRates = listOf(
        FxRate(
            symbol = "BTCUSD",
            lastPrice = STUB_RATE.toString(),
            base = "BTC",
            baseName = "Bitcoin",
            quote = "USD",
            quoteName = "US Dollar",
            currencySymbol = "$",
            currencyFlag = "🇺🇸",
            lastUpdatedAt = System.currentTimeMillis()
        )
    )

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = testRates)))

        currencyRepo = CurrencyRepo(
            bgDispatcher = testDispatcher,
            currencyService = currencyService,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            enablePolling = false,
            clock = clock,
        )

        viewModel = AmountInputViewModel(currencyRepo)
    }

    private fun mockCurrency(
        primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
        displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    ) = CurrencyState(
        rates = testRates,
        selectedCurrency = "USD",
        currencySymbol = "$",
        primaryDisplay = primaryDisplay,
        displayUnit = displayUnit
    )

    // MARK: - Modern Bitcoin Tests

    @Test
    fun `modern bitcoin input builds correctly`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        viewModel.handleNumberPadInput("1", currency)
        assertEquals("1", viewModel.uiState.value.text)
        assertEquals(1L, viewModel.uiState.value.sats)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("10", viewModel.uiState.value.text)
        assertEquals(10L, viewModel.uiState.value.sats)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("100", viewModel.uiState.value.text)
        assertEquals(100L, viewModel.uiState.value.sats)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1 000", viewModel.uiState.value.text)
        assertEquals(1000L, viewModel.uiState.value.sats)
    }

    @Test
    fun `modern bitcoin max amount enforcement`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        // Type max amount
        "999999999".forEach { digit ->
            viewModel.handleNumberPadInput(digit.toString(), currency)
        }
        assertEquals(AmountInputViewModel.MAX_AMOUNT, viewModel.uiState.value.sats)

        // Try to exceed max amount - should be blocked
        viewModel.handleNumberPadInput("0", currency)
        assertEquals(AmountInputViewModel.MAX_AMOUNT, viewModel.uiState.value.sats)
        assertNotNull(viewModel.uiState.value.errorKey)
    }

    @Test
    fun `modern bitcoin number pad type is INTEGER`() {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)
        assertEquals(NumberPadType.INTEGER, viewModel.getNumberPadType(currency))
    }

    @Test
    fun `modern bitcoin allows 000 button`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_000, currency)
        assertEquals("1 000", viewModel.uiState.value.text)
        assertEquals(1000L, viewModel.uiState.value.sats)
    }

    // MARK: - Classic Bitcoin Tests

    @Test
    fun `classic bitcoin decimal input`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        viewModel.handleNumberPadInput("1", currency)
        assertEquals("1", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("1.", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1.0", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1.00", viewModel.uiState.value.text)
    }

    @Test
    fun `classic bitcoin max decimals enforcement`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        // Build up to max decimals
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)

        repeat(8) {
            viewModel.handleNumberPadInput("0", currency)
        }
        assertEquals("1.00000000", viewModel.uiState.value.text)

        // Try to add more decimals - should be blocked
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1.00000000", viewModel.uiState.value.text) // Should not change
    }

    @Test
    fun `classic bitcoin max amount enforcement`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        // Type "10" in classic Bitcoin - should be blocked (exceeds max amount)
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput("0", currency)

        // Should not allow "10" because 10 BTC > 999,999,999 sats
        assertTrue(viewModel.uiState.value.sats <= AmountInputViewModel.MAX_AMOUNT)
    }

    @Test
    fun `classic bitcoin number pad type is DECIMAL`() {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        assertEquals(NumberPadType.DECIMAL, viewModel.getNumberPadType(currency))
    }

    // MARK: - Fiat Tests

    @Test
    fun `fiat number pad type is DECIMAL`() {
        val currency = mockCurrency(PrimaryDisplay.FIAT)
        assertEquals(NumberPadType.DECIMAL, viewModel.getNumberPadType(currency))
    }

    @Test
    fun `fiat max decimals is 2`() {
        val currency = mockCurrency(PrimaryDisplay.FIAT)
        assertEquals(FIAT_DECIMALS, viewModel.getMaxDecimals(currency))
    }

    // MARK: - Edge Cases

    @Test
    fun `empty input plus decimal becomes 0 point`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("0.", viewModel.uiState.value.text)
    }

    @Test
    fun `leading zeros are prevented`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        // Start with 0, then type a digit - should replace 0
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("", viewModel.uiState.value.text) // Modern Bitcoin shows empty for 0

        viewModel.handleNumberPadInput("1", currency)
        assertEquals("1", viewModel.uiState.value.text)
    }

    @Test
    fun `delete from 0 point clears entire input`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("0.", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("", viewModel.uiState.value.text)
    }

    @Test
    fun `delete operations work correctly`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        // Build up "1.50"
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("5", currency)
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1.50", viewModel.uiState.value.text)

        // Delete back to "1."
        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1.5", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1.", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("", viewModel.uiState.value.text)
    }

    @Test
    fun `multiple decimal points are ignored`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("5", currency)
        assertEquals("1.5", viewModel.uiState.value.text)

        // Try to add another decimal point
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("1.5", viewModel.uiState.value.text) // Should not change
    }

    @Test
    fun `error state clears automatically`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        // Type max amount
        "999999999".forEach { digit ->
            viewModel.handleNumberPadInput(digit.toString(), currency)
        }

        // Try to exceed max amount - should trigger error
        viewModel.handleNumberPadInput("0", currency)
        assertNotNull(viewModel.uiState.value.errorKey)
        assertEquals("0", viewModel.uiState.value.errorKey)

        // Wait for error to clear
        delay(AmountInputViewModel.ERROR_DELAY_MS + 100)
        assertNull(viewModel.uiState.value.errorKey)
    }

    @Test
    fun `placeholder shows correctly for different currencies`() {
        // Modern Bitcoin - empty input
        val modernBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)
        assertEquals("0", viewModel.getPlaceholder(modernBtc))

        // Classic Bitcoin - empty input
        val classicBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        assertEquals(
            "0.00000000",
            viewModel.getPlaceholder(classicBtc)
        )

        // Fiat - empty input
        val fiat = mockCurrency(PrimaryDisplay.FIAT)
        assertEquals("0.00", viewModel.getPlaceholder(fiat))
    }

    @Test
    fun `max length enforcement`() {
        // Modern Bitcoin max length
        val modernBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)
        assertEquals(AmountInputViewModel.MAX_MODERN_LENGTH, viewModel.getMaxLength(modernBtc))

        // Classic Bitcoin max length
        val classicBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        assertEquals(AmountInputViewModel.MAX_DECIMAL_LENGTH, viewModel.getMaxLength(classicBtc))

        // Fiat max length
        val fiat = mockCurrency(PrimaryDisplay.FIAT)
        assertEquals(AmountInputViewModel.MAX_DECIMAL_LENGTH, viewModel.getMaxLength(fiat))
    }

    @Test
    fun `clear input resets all state`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)

        assertEquals("100", viewModel.uiState.value.text)
        assertEquals(100L, viewModel.uiState.value.sats)

        viewModel.clearInput()

        assertEquals("", viewModel.uiState.value.text)
        assertEquals(0L, viewModel.uiState.value.sats)
        assertNull(viewModel.uiState.value.errorKey)
    }

    @Test
    fun `setSats sets correct display text`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        viewModel.setSats(12345L, currency)
        assertEquals(12345L, viewModel.uiState.value.sats)
        assertEquals("12 345", viewModel.uiState.value.text)
    }

    @Test
    fun `setSats works with fiat currency`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(primaryDisplay = PrimaryDisplay.FIAT)))

        viewModel.setSats(100000L, currency)
        assertEquals(100000L, viewModel.uiState.value.sats)
        assertEquals("115.15", viewModel.uiState.value.text)

        viewModel.switchUnit(currency)
        assertEquals("100 000", viewModel.uiState.value.text)
    }

    @Test
    fun `fiat grouping separators work correctly`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        viewModel.handleNumberPadInput("1", currency)
        assertEquals("1", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("10", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("100", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1,000", viewModel.uiState.value.text)
    }

    @Test
    fun `delete from formatted text works correctly`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to "1,000.00"
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1,000", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("1,000.00", viewModel.uiState.value.text)

        // Delete character by character
        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1,000.0", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1,000.", viewModel.uiState.value.text)

        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("1,000", viewModel.uiState.value.text)
    }

    @Test
    fun `multiple leading zeros behavior`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Multiple zeros should be ignored until a non-zero digit is entered
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput("1", currency)
        assertEquals("1", viewModel.uiState.value.text)
    }

    @Test
    fun `empty input plus decimal becomes 0 point for all currencies`() = test {
        // Test for fiat
        val fiatCurrency = mockCurrency(PrimaryDisplay.FIAT)
        viewModel.handleNumberPadInput(KEY_DECIMAL, fiatCurrency)
        assertEquals("0.", viewModel.uiState.value.text)

        // Clear and test for classic Bitcoin
        viewModel.clearInput()
        val classicBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        viewModel.handleNumberPadInput(KEY_DECIMAL, classicBtc)
        assertEquals("0.", viewModel.uiState.value.text)
    }

    @Test
    fun `dynamic placeholder behavior for classic bitcoin`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        // Empty input should show full decimal placeholder
        assertEquals("0.00000000", viewModel.getPlaceholder(currency))

        // Typing "1" should show remaining decimals
        viewModel.handleNumberPadInput("1", currency)
        assertEquals(".00000000", viewModel.getPlaceholder(currency))

        // Typing "1." should show remaining decimals
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("00000000", viewModel.getPlaceholder(currency))

        // Typing "1.5" should show remaining decimals
        viewModel.handleNumberPadInput("5", currency)
        assertEquals("0000000", viewModel.getPlaceholder(currency))
    }

    @Test
    fun `dynamic placeholder behavior for fiat`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Empty input should show decimal placeholder
        assertEquals("0.00", viewModel.getPlaceholder(currency))

        // Typing "1" should show decimal placeholder
        viewModel.handleNumberPadInput("1", currency)
        assertEquals(".00", viewModel.getPlaceholder(currency))

        // Typing "1." should show remaining decimals
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("00", viewModel.getPlaceholder(currency))

        // Typing "1.5" should show remaining decimal
        viewModel.handleNumberPadInput("5", currency)
        assertEquals("0", viewModel.getPlaceholder(currency))
    }

    @Test
    fun `placeholder with leading zero for fiat`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // "0" should show decimal placeholder
        viewModel.handleNumberPadInput("0", currency)
        assertEquals(".00", viewModel.getPlaceholder(currency))

        // "0." should show remaining decimals
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        assertEquals("00", viewModel.getPlaceholder(currency))
    }

    @Test
    fun `placeholder after delete operations`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to "1.50"
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("5", currency)
        viewModel.handleNumberPadInput("0", currency)
        assertEquals("", viewModel.getPlaceholder(currency))

        // Delete to "1.5" should show remaining decimal
        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("0", viewModel.getPlaceholder(currency))

        // Delete to "1." should show remaining decimals
        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals("00", viewModel.getPlaceholder(currency))

        // Delete to "1" should show decimal placeholder
        viewModel.handleNumberPadInput(KEY_DELETE, currency)
        assertEquals(".00", viewModel.getPlaceholder(currency))
    }

    // MARK: - Blocked Input Tests (State Should Not Change)

    @Test
    fun `blocked input in fiat with full decimals doesn't change amountSats`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to fiat with 2 decimals: "24.21"
        viewModel.handleNumberPadInput("2", currency)
        viewModel.handleNumberPadInput("4", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("2", currency)
        viewModel.handleNumberPadInput("1", currency)

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another digit - should be blocked
        viewModel.handleNumberPadInput("5", currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked input in classic bitcoin with 8 decimals doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)

        // Build up to classic Bitcoin with 8 decimals: "0.12345678"
        viewModel.handleNumberPadInput("0", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        repeat(8) { digit ->
            viewModel.handleNumberPadInput((digit + 1).toString(), currency)
        }

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another decimal digit - should be blocked
        viewModel.handleNumberPadInput("9", currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked input at max decimal length doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to max decimal length (20 characters)
        val maxLengthText = "1".repeat(AmountInputViewModel.MAX_DECIMAL_LENGTH)
        maxLengthText.forEach { digit ->
            if (viewModel.uiState.value.text.length < AmountInputViewModel.MAX_DECIMAL_LENGTH) {
                viewModel.handleNumberPadInput(digit.toString(), currency)
            }
        }

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another digit - should be blocked
        viewModel.handleNumberPadInput("9", currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked input at max modern bitcoin length doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        // Build up to max modern length (10 digits)
        val maxLengthText = "1".repeat(AmountInputViewModel.MAX_MODERN_LENGTH)
        maxLengthText.forEach { digit ->
            viewModel.handleNumberPadInput(digit.toString(), currency)
        }

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another digit - should be blocked
        viewModel.handleNumberPadInput("9", currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked decimal point when already exists doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to "12.34"
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput("2", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("3", currency)
        viewModel.handleNumberPadInput("4", currency)

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another decimal point - should be blocked
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked leading zeros don't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Start with "0"
        viewModel.handleNumberPadInput("0", currency)

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add another "0" - should be blocked (replaced with same value)
        viewModel.handleNumberPadInput("0", currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `blocked triple zero button when exceeding limits doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)

        // Build up to 8 digits (close to max of 10)
        "12345678".forEach { digit ->
            viewModel.handleNumberPadInput(digit.toString(), currency)
        }

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add "000" - should be blocked (would exceed max length)
        viewModel.handleNumberPadInput(KEY_000, currency)

        // Both display and sats should remain unchanged
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `toggle currency then blocked input preserves original amount`() = test {
        val modernBtc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)
        val fiat = mockCurrency(PrimaryDisplay.FIAT)

        // Enter modern Bitcoin amount
        viewModel.handleNumberPadInput("1", modernBtc)
        viewModel.handleNumberPadInput("0", modernBtc)
        viewModel.handleNumberPadInput("0", modernBtc)
        val originalSats = viewModel.uiState.value.sats

        // Simulate toggle to fiat (would show something like "1.00" with 2 decimals)
        viewModel.setSats(originalSats, fiat)

        // Try to add another digit in fiat mode - should be blocked if at decimal limit
        viewModel.handleNumberPadInput(KEY_DECIMAL, fiat)
        viewModel.handleNumberPadInput("0", fiat)
        viewModel.handleNumberPadInput("0", fiat)

        val fiatSatsAfterFullInput = viewModel.uiState.value.sats

        // Try to add another digit - should be blocked
        viewModel.handleNumberPadInput("5", fiat)

        // Sats amount should not change from the previous valid state
        assertEquals(fiatSatsAfterFullInput, viewModel.uiState.value.sats)
    }

    @Test
    fun `delete key works even at input limits`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to fiat with 2 decimals: "12.34"
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput("2", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("3", currency)
        viewModel.handleNumberPadInput("4", currency)

        val initialSats = viewModel.uiState.value.sats

        // Delete should still work even at decimal limit
        viewModel.handleNumberPadInput(KEY_DELETE, currency)

        // Display should change and sats should be different
        assertEquals("12.3", viewModel.uiState.value.text)
        assertTrue(viewModel.uiState.value.sats != initialSats)
    }

    @Test
    fun `blocked decimals beyond fiat limit doesn't change state`() = test {
        val currency = mockCurrency(PrimaryDisplay.FIAT)

        // Build up to "1.23" (max 2 decimals for fiat)
        viewModel.handleNumberPadInput("1", currency)
        viewModel.handleNumberPadInput(KEY_DECIMAL, currency)
        viewModel.handleNumberPadInput("2", currency)
        viewModel.handleNumberPadInput("3", currency)

        val initialDisplay = viewModel.uiState.value.text
        val initialSats = viewModel.uiState.value.sats

        // Try to add more decimal digits - should be blocked
        viewModel.handleNumberPadInput("4", currency)
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)

        viewModel.handleNumberPadInput("5", currency)
        assertEquals(initialDisplay, viewModel.uiState.value.text)
        assertEquals(initialSats, viewModel.uiState.value.sats)
    }

    @Test
    fun `switchUnit preserves sats amount`() = test {
        val btc = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        val fiat = mockCurrency(PrimaryDisplay.FIAT)

        // Enter Bitcoin amount
        viewModel.handleNumberPadInput("0", btc)
        viewModel.handleNumberPadInput(KEY_DECIMAL, btc)
        viewModel.handleNumberPadInput("0", btc)
        viewModel.handleNumberPadInput("1", btc)
        val originalSats = viewModel.uiState.value.sats

        // Toggle to fiat
        viewModel.switchUnit(fiat)
        val satsAfterToggle = viewModel.uiState.value.sats

        // Toggle back to bitcoin
        viewModel.switchUnit(btc)
        val finalSats = viewModel.uiState.value.sats

        // Sats amount should be preserved throughout
        assertEquals(originalSats, satsAfterToggle)
        assertEquals(originalSats, finalSats)
        assertTrue("Amount should be greater than 0", originalSats > 0)
    }

    @Test
    fun `classic round trip conversion maintains original amount`() = test {
        val btcClassic = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        val fiat = mockCurrency(PrimaryDisplay.FIAT)
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    primaryDisplay = btcClassic.primaryDisplay,
                    displayUnit = btcClassic.displayUnit,
                )
            )
        )

        viewModel.handleNumberPadInput("5", btcClassic)
        val originalSats = viewModel.uiState.value.sats

        // Switch unit to fiat
        viewModel.switchUnit(btcClassic)
        val fiatDisplay = viewModel.uiState.value.text
        val fiatSats = viewModel.uiState.value.sats

        // Switch unit back to bitcoin
        viewModel.switchUnit(fiat)
        val finalDisplay = viewModel.uiState.value.text
        val finalSats = viewModel.uiState.value.sats

        assertEquals("Sats amount should be preserved", originalSats, fiatSats)
        assertEquals("Sats amount should be preserved after round trip", originalSats, finalSats)
        assertEquals("Display should return to original value after round trip", "500 000 000", finalDisplay)
        assertNotEquals("Fiat conversion should not be $0.10 for substantial Bitcoin amount", "0.10", fiatDisplay)
        assertTrue("Original amount should be substantial (5 BTC)", originalSats >= 500_000_000L)
    }

    @Test
    fun `switchUnit with decimal amounts preserves precision`() = test {
        val btcClassic = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    primaryDisplay = btcClassic.primaryDisplay,
                    displayUnit = btcClassic.displayUnit,
                )
            )
        )
        val currencyRepo = CurrencyRepo(
            bgDispatcher = testDispatcher,
            currencyService = currencyService,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            enablePolling = false,
            clock = clock,
        )

        // Enter precise Bitcoin amount: 0.00000092 BTC (92 sats)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput(KEY_DECIMAL, btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("9", btcClassic)
        viewModel.handleNumberPadInput("2", btcClassic)

        val originalSats = viewModel.uiState.value.sats
        val originalDisplay = viewModel.uiState.value.text

        // Switch to fiat and back
        val btcState = currencyRepo.currencyState.value.copy(primaryDisplay = PrimaryDisplay.BITCOIN)
        viewModel.switchUnit(btcState) // Bitcoin -> Fiat
        val fiatState = currencyRepo.currencyState.value.copy(primaryDisplay = PrimaryDisplay.FIAT)
        viewModel.switchUnit(fiatState) // Fiat -> Bitcoin

        val finalSats = viewModel.uiState.value.sats
        val finalDisplay = viewModel.uiState.value.text

        // Precision should be maintained
        assertEquals("Precise sats amount should be preserved", originalSats, finalSats)
        assertEquals("Decimal precision should be preserved", originalDisplay, finalDisplay)
        assertEquals("Should be exactly 92 sats", 92L, originalSats)
    }

    @Test
    fun `switchUnit handles empty and partial input safely`() = test {
        val btcClassic = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        val fiat = mockCurrency(PrimaryDisplay.FIAT)

        // Test with empty input
        viewModel.switchUnit(fiat)
        assertEquals("Empty input should remain 0 sats", 0L, viewModel.uiState.value.sats)
        assertEquals("Empty input should have empty display", "", viewModel.uiState.value.text)

        // Test with partial input "0."
        viewModel.handleNumberPadInput("0", fiat)
        viewModel.handleNumberPadInput(KEY_DECIMAL, fiat)
        val partialSats = viewModel.uiState.value.sats

        // Toggle to Bitcoin and back
        viewModel.switchUnit(btcClassic)
        viewModel.switchUnit(fiat)

        // Should handle gracefully without crashes
        assertEquals("Partial input sats should be preserved", partialSats, viewModel.uiState.value.sats)

        // Test toggling with just decimal point from Bitcoin side
        viewModel.clearInput()
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput(KEY_DECIMAL, btcClassic)

        // Should not crash when toggling
        try {
            viewModel.switchUnit(fiat)
            // If we get here without exception, test passes
            assertTrue("Should not crash with partial Bitcoin input", true)
        } catch (e: Exception) {
            assertTrue("Should not crash with partial Bitcoin input, but got: ${e.message}", false)
        }
    }

    @Test
    fun `switchUnit updates display text correctly`() = test {
        val btcModern = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.MODERN)
        val fiat = mockCurrency(PrimaryDisplay.FIAT)

        // Start with modern Bitcoin: 1000 sats
        viewModel.handleNumberPadInput("1", btcModern)
        viewModel.handleNumberPadInput("0", btcModern)
        viewModel.handleNumberPadInput("0", btcModern)
        viewModel.handleNumberPadInput("0", btcModern)

        val modernDisplay = viewModel.uiState.value.text
        val satsAmount = viewModel.uiState.value.sats

        // Note: Since we're using NoopAmountHandler, we can't actually test currency conversion
        // But we can test that switchUnit doesn't crash and preserves sats amount
        viewModel.switchUnit(fiat)
        val afterToggleSats = viewModel.uiState.value.sats

        // Verify display format for modern Bitcoin
        assertEquals("Modern Bitcoin should show formatted sats", "1 000", modernDisplay)
        assertEquals("Sats amount should remain constant after toggle", satsAmount, afterToggleSats)
        assertTrue("Amount should be 1000 sats", satsAmount == 1000L)

        // Verify toggle doesn't crash (basic functionality test)
        assertTrue("Toggle operation should complete without error", true)
    }

    @Test
    fun `classic conversion calculations are accurate`() = test {
        val btcClassic = mockCurrency(PrimaryDisplay.BITCOIN, BitcoinDisplayUnit.CLASSIC)
        whenever(settingsStore.data).thenReturn(
            flowOf(
                SettingsData(
                    primaryDisplay = PrimaryDisplay.BITCOIN,
                    displayUnit = BitcoinDisplayUnit.CLASSIC
                )
            )
        )

        while (currencyRepo.currencyState.value.rates.isEmpty()) {
            delay(1.milliseconds)
        }

        // Test case 1: Use realistic amount that doesn't exceed MAX_AMOUNT
        // Input "5" BTC (5 * 100,000,000 = 500,000,000 sats, which is under MAX_AMOUNT)
        viewModel.handleNumberPadInput("5", btcClassic)
        val largeBtcSats = viewModel.uiState.value.sats

        // Toggle from Bitcoin to Fiat - pass current Bitcoin state
        val currentBtcState = currencyRepo.currencyState.value.copy(primaryDisplay = PrimaryDisplay.BITCOIN)
        viewModel.switchUnit(currentBtcState)
        val largeBtcFiatDisplay = viewModel.uiState.value.text

        // 5 BTC is a substantial amount and should not convert to tiny values like $0.10
        assertNotEquals("5 BTC should not convert to $0.10", "0.10", largeBtcFiatDisplay)
        assertNotEquals("5 BTC should not convert to $0", "0", largeBtcFiatDisplay)
        assertTrue("5 BTC should convert to substantial sats amount", largeBtcSats >= 500_000_000L) // 5 BTC in sats

        // Test case 2: Small amounts should convert correctly
        viewModel.clearInput()
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput(KEY_DECIMAL, btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("0", btcClassic)
        viewModel.handleNumberPadInput("1", btcClassic)
        val smallBtcSats = viewModel.uiState.value.sats

        // Toggle from Bitcoin to Fiat - pass current Bitcoin state
        viewModel.switchUnit(currentBtcState)
        val smallBtcFiatDisplay = viewModel.uiState.value.text

        // 0.001 BTC should convert to reasonable fiat amount (not 0 or extremely large)
        assertTrue("Small BTC amount should have reasonable sats value", smallBtcSats > 0)
        assertTrue(
            "Small BTC should convert to reasonable fiat",
            smallBtcFiatDisplay.isNotEmpty() && smallBtcFiatDisplay != "0"
        )

        // Test case 3: Verify conversion consistency
        val fiatAmount = smallBtcFiatDisplay.replace(",", "").toDoubleOrNull()
        assertNotNull("Fiat display should be parseable as number (got: '$smallBtcFiatDisplay')", fiatAmount)
        if (fiatAmount != null) {
            assertTrue("Converted fiat should be positive", fiatAmount > 0)
        }
    }
}
