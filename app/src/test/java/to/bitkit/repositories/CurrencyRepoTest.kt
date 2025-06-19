package to.bitkit.repositories

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.services.CurrencyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CurrencyRepoTest : BaseUnitTest() {
    private val currencyService: CurrencyService = mock()
    private val settingsStore: SettingsStore = mock()
    private val cacheStore: CacheStore = mock()
    private val toastEventBus: ToastEventBus = mock()

    private lateinit var sut: CurrencyRepo

    private val testRates = listOf(
        FxRate(
            symbol = "BTCUSD",
            lastPrice = "50000.00",
            base = "BTC",
            baseName = "Bitcoin",
            quote = "USD",
            quoteName = "US Dollar",
            currencySymbol = "$",
            currencyFlag = "🇺🇸",
            lastUpdatedAt = System.currentTimeMillis()
        ),
        FxRate(
            symbol = "BTCEUR",
            lastPrice = "45000.00",
            base = "BTC",
            baseName = "Bitcoin",
            quote = "EUR",
            quoteName = "Euro",
            currencySymbol = "€",
            currencyFlag = "🇪🇺",
            lastUpdatedAt = System.currentTimeMillis()
        )
    )

    @Before
    fun setUp() {
        // Set up default mocks
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
    }

    private fun createSut(): CurrencyRepo {
        return CurrencyRepo(
            bgDispatcher = testDispatcher,
            currencyService = currencyService,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            enablePolling = false,
        )
    }

    @Test
    fun `initial state should have default values`() = test {
        sut = createSut()

        // Use timeout to prevent hanging
        sut.currencyState.test(timeout = 1000.milliseconds) {
            val initialState = awaitItem()
            assertEquals(emptyList<FxRate>(), initialState.rates)
            assertEquals("USD", initialState.selectedCurrency)
            assertEquals("$", initialState.currencySymbol)
            assertEquals(BitcoinDisplayUnit.MODERN, initialState.displayUnit)
            assertEquals(PrimaryDisplay.BITCOIN, initialState.primaryDisplay)
            assertFalse(initialState.hasStaleData)
            assertNull(initialState.error)
        }
    }

    @Test
    fun `convertSatsToFiat should handle rate properties correctly`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = testRates)))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(selectedCurrency = "USD")))

        sut = createSut()
        val result = sut.convertSatsToFiat(100_000L) // 100k sats = 0.001 BTC

        // Wait for initial state to be set up, then test conversion
        sut.currencyState.take(1).test(timeout = 1000.milliseconds) {
            awaitItem() // Wait for state to be initialized

            assertTrue(result.isSuccess)
            val converted = result.getOrThrow()
            assertEquals(BigDecimal("50.000000000"), converted.value) // 0.001 * 50000
            assertEquals("50.00", converted.formatted)
            assertEquals("$", converted.symbol)
            assertEquals("USD", converted.currency)
            assertEquals("🇺🇸", converted.flag)
            assertEquals(100_000L, converted.sats)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `convertFiatToSats should use correct rate and precision`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = testRates)))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(selectedCurrency = "EUR")))

        sut = createSut()

        sut.currencyState.take(1).test(timeout = 1000.milliseconds) {
            awaitItem() // Wait for state to be initialized

            val result = sut.convertFiatToSats(BigDecimal("45.00")) // 45 EUR / 45000 = 0.001 BTC
            assertTrue(result.isSuccess)
            assertEquals(100_000uL, result.getOrThrow()) // 0.001 BTC in sats
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should detect stale data based on lastUpdatedAt`() = test {
        val oldRates = listOf(
            testRates[0].copy(
                lastUpdatedAt = System.currentTimeMillis() - 1000 * 60 * 60 * 3 // 3 hours ago
            )
        )

        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = oldRates)))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(selectedCurrency = "USD")))
        wheneverBlocking { currencyService.fetchLatestRates() }.thenThrow(RuntimeException("API error"))

        sut = createSut()
        sut.triggerRefresh()

        sut.currencyState.test(timeout = 2000.milliseconds) {
            val staleState = awaitItem()
            assertTrue(staleState.hasStaleData)
            assertEquals(oldRates, staleState.rates)
        }
    }

    @Test
    fun `getCurrentRate should match by quote currency`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = testRates)))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(selectedCurrency = "EUR")))

        sut = createSut()
        val rate = sut.getCurrentRate("EUR")

        sut.currencyState.take(1).test(timeout = 1000.milliseconds) {
            awaitItem() // Wait for state to be initialized

            assertEquals(testRates[1], rate)
            assertEquals(45000.0, rate?.rate)
            assertEquals("€", rate?.currencySymbol)
            assertEquals("🇪🇺", rate?.currencyFlag)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state should update when settings change`() = test {
        val testSettings = SettingsData(
            selectedCurrency = "EUR",
            displayUnit = BitcoinDisplayUnit.CLASSIC,
            primaryDisplay = PrimaryDisplay.FIAT
        )

        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(cachedRates = testRates)))
        whenever(settingsStore.data).thenReturn(flowOf(testSettings))

        sut = createSut()

        sut.currencyState.test(timeout = 1000.milliseconds) {
            val updatedState = awaitItem()
            assertEquals("EUR", updatedState.selectedCurrency)
            assertEquals("€", updatedState.currencySymbol)
            assertEquals(BitcoinDisplayUnit.CLASSIC, updatedState.displayUnit)
            assertEquals(PrimaryDisplay.FIAT, updatedState.primaryDisplay)
        }
    }
}
