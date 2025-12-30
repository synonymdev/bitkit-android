package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BalanceState
import to.bitkit.models.ConvertedAmount
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType
import java.math.BigDecimal
import java.util.Locale

class HighBalanceTimedSheetTest : BaseUnitTest() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var walletRepo: WalletRepo
    private lateinit var currencyRepo: CurrencyRepo
    private lateinit var sut: HighBalanceTimedSheet

    private val defaultSettings = SettingsData()
    private val settingsFlow = MutableStateFlow(defaultSettings)

    @Before
    fun setUp() = runBlocking {
        settingsStore = mock()
        walletRepo = mock()
        currencyRepo = mock()

        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(settingsStore.update(any())).then {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            flowOf(settingsFlow.value)
        }

        sut = HighBalanceTimedSheet(settingsStore, walletRepo, currencyRepo)
    }

    @Test
    fun `type is HIGH_BALANCE`() {
        assertTrue(sut.type == TimedSheetType.HIGH_BALANCE)
    }

    @Test
    fun `priority is 1`() {
        assertTrue(sut.priority == 1)
    }

    @Test
    fun `should not show when balance below threshold`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 10000U))
        )
        whenever(currencyRepo.convertSatsToFiat(10000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("100"),
                    formatted = "100.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 10000L,
                    locale = Locale.US
                )
            )
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when USD conversion fails`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.failure(Exception("Network error"))
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when max warnings reached`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("600"),
                    formatted = "600.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 100000L,
                    locale = Locale.US
                )
            )
        )
        settingsFlow.value = defaultSettings.copy(balanceWarningTimes = 3)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show within timeout period`() = test {
        val currentTime = System.currentTimeMillis()
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("600"),
                    formatted = "600.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 100000L,
                    locale = Locale.US
                )
            )
        )
        settingsFlow.value = defaultSettings.copy(
            balanceWarningTimes = 0,
            balanceWarningIgnoredMillis = currentTime - 1000
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should show when balance over threshold and timeout passed`() = test {
        val oldTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000)
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("600"),
                    formatted = "600.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 100000L,
                    locale = Locale.US
                )
            )
        )
        settingsFlow.value = defaultSettings.copy(
            balanceWarningTimes = 0,
            balanceWarningIgnoredMillis = oldTime
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `should show when never dismissed and balance over threshold`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("600"),
                    formatted = "600.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 100000L,
                    locale = Locale.US
                )
            )
        )
        settingsFlow.value = defaultSettings.copy(
            balanceWarningTimes = 0,
            balanceWarningIgnoredMillis = 0L
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `resets warning times when balance drops below threshold`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 10000U))
        )
        whenever(currencyRepo.convertSatsToFiat(10000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("100"),
                    formatted = "100.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 10000L,
                    locale = Locale.US
                )
            )
        )
        settingsFlow.value = defaultSettings.copy(balanceWarningTimes = 2)

        sut.shouldShow()

        verify(settingsStore).update(any())
        assertEquals(0, settingsFlow.value.balanceWarningTimes)
    }

    @Test
    fun `increments warning times and updates timestamp on dismissed`() = test {
        settingsFlow.value = defaultSettings.copy(balanceWarningTimes = 1)

        sut.onDismissed()

        verify(settingsStore).update(any())
        assertEquals(2, settingsFlow.value.balanceWarningTimes)
        assertTrue(settingsFlow.value.balanceWarningIgnoredMillis > 0)
    }

    @Test
    fun `should show up to 3 times maximum`() = test {
        val oldTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000)
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 100000U))
        )
        whenever(currencyRepo.convertSatsToFiat(100000L, "USD")).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("600"),
                    formatted = "600.00",
                    symbol = "$",
                    currency = "USD",
                    flag = "",
                    sats = 100000L,
                    locale = Locale.US
                )
            )
        )

        settingsFlow.value = defaultSettings.copy(
            balanceWarningTimes = 2,
            balanceWarningIgnoredMillis = oldTime
        )
        assertTrue(sut.shouldShow())

        settingsFlow.value = defaultSettings.copy(
            balanceWarningTimes = 3,
            balanceWarningIgnoredMillis = oldTime
        )
        assertFalse(sut.shouldShow())
    }
}
