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

class BackupTimedSheetTest : BaseUnitTest() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var walletRepo: WalletRepo
    private lateinit var sut: BackupTimedSheet

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
            MutableStateFlow(BalanceState(totalOnchainSats = 1000U))
        )

        sut = BackupTimedSheet(settingsStore, walletRepo)
    }

    @Test
    fun `type is BACKUP`() {
        assertTrue(sut.type == TimedSheetType.BACKUP)
    }

    @Test
    fun `priority is 4`() {
        assertTrue(sut.priority == 4)
    }

    @Test
    fun `should not show when backup verified`() = test {
        settingsFlow.value = defaultSettings.copy(backupVerified = true)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when no balance`() = test {
        whenever(walletRepo.balanceState).thenReturn(
            MutableStateFlow(BalanceState(totalOnchainSats = 0U))
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show within timeout period`() = test {
        val currentTime = System.currentTimeMillis()
        settingsFlow.value = defaultSettings.copy(
            backupVerified = false,
            backupWarningIgnoredMillis = currentTime - 1000
        )

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should show after timeout with balance and unverified backup`() = test {
        val oldTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000)
        settingsFlow.value = defaultSettings.copy(
            backupVerified = false,
            backupWarningIgnoredMillis = oldTime
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `should show when never dismissed`() = test {
        settingsFlow.value = defaultSettings.copy(
            backupVerified = false,
            backupWarningIgnoredMillis = 0L
        )

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `updates ignored timestamp on dismissed`() = test {
        sut.onDismissed()

        verify(settingsStore).update(any())
        assertTrue(settingsFlow.value.backupWarningIgnoredMillis > 0)
    }
}
