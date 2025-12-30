package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType

class QuickPayTimedSheetTest : BaseUnitTest() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var walletRepo: WalletRepo
    private lateinit var sut: QuickPayTimedSheet

    private val defaultSettings = SettingsData()
    private val settingsFlow = MutableStateFlow(defaultSettings)

    @Before
    fun setUp() = runBlocking {
        settingsStore = mock()
        walletRepo = mock()

        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(settingsStore.update(any())).then {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            flowOf(settingsFlow.value)
        }

        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalLightningSats = 1000U))
        )

        sut = QuickPayTimedSheet(settingsStore, walletRepo)
    }

    @Test
    fun `type is QUICK_PAY`() {
        assertTrue(sut.type == TimedSheetType.QUICK_PAY)
    }

    @Test
    fun `priority is 2`() {
        assertTrue(sut.priority == 2)
    }

    @Test
    fun `should not show when intro already seen`() = test {
        settingsFlow.value = defaultSettings.copy(quickPayIntroSeen = true)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when QuickPay already enabled`() = test {
        settingsFlow.value = defaultSettings.copy(
            quickPayIntroSeen = false,
            isQuickPayEnabled = true
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when no lightning balance`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalLightningSats = 0U))
        )
        settingsFlow.value = defaultSettings.copy(
            quickPayIntroSeen = false,
            isQuickPayEnabled = false
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should show with lightning balance and intro not seen`() = test {
        settingsFlow.value = defaultSettings.copy(
            quickPayIntroSeen = false,
            isQuickPayEnabled = false
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `updates quickPayIntroSeen on dismissed`() = test {
        settingsFlow.value = defaultSettings.copy(quickPayIntroSeen = false)

        sut.onDismissed()

        verify(settingsStore).update(any())
        assertTrue(settingsFlow.value.quickPayIntroSeen)
    }
}
