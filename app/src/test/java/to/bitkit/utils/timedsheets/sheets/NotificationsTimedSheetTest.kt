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

class NotificationsTimedSheetTest : BaseUnitTest() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var walletRepo: WalletRepo
    private lateinit var sut: NotificationsTimedSheet

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
            MutableStateFlow(BalanceState(totalLightningSats = 1000UL))
        )

        sut = NotificationsTimedSheet(settingsStore, walletRepo)
    }

    @Test
    fun `type is NOTIFICATIONS`() {
        assertTrue(sut.type == TimedSheetType.NOTIFICATIONS)
    }

    @Test
    fun `priority is 3`() {
        assertTrue(sut.priority == 3)
    }

    @Test
    fun `should not show when notifications granted`() = test {
        settingsFlow.value = defaultSettings.copy(notificationsGranted = true)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when no lightning balance`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalLightningSats = 0UL))
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show within one week timeout`() = test {
        val sixDaysAgo = System.currentTimeMillis() - (6L * 24 * 60 * 60 * 1000)
        settingsFlow.value = defaultSettings.copy(
            notificationsGranted = false,
            notificationsIgnoredMillis = sixDaysAgo
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should show after one week timeout with lightning balance`() = test {
        val eightDaysAgo = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000)
        settingsFlow.value = defaultSettings.copy(
            notificationsGranted = false,
            notificationsIgnoredMillis = eightDaysAgo
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `should show when never dismissed`() = test {
        settingsFlow.value = defaultSettings.copy(
            notificationsGranted = false,
            notificationsIgnoredMillis = 0L
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `updates ignored timestamp on dismissed`() = test {
        sut.onDismissed()

        verify(settingsStore).update(any())
        assertTrue(settingsFlow.value.notificationsIgnoredMillis > 0)
    }
}
