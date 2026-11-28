package to.bitkit.wear.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.models.BitcoinDisplayUnit
import to.bitkit.wear.models.PhoneSettings
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.QuickPayLimits
import to.bitkit.wear.security.FakeSeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.PhoneBackupReader
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncRepoTest : BaseUnitTest() {
    private companion object {
        const val NOW = 1_795_867_200_000L
        const val TOKEN = 4
        val SEED = WalletSeed("abandon about", passphrase = "pass")
        val BACKUP = """
            {"version":1,"createdAt":1,"settings":{"primaryDisplay":"FIAT","displayUnit":"CLASSIC",
            "selectedCurrency":"EUR","isQuickPayEnabled":true,"quickPayAmount":10,"quickPayDailyLimitMultiplier":3}}
        """.trimIndent().toByteArray()
        val SETTINGS = PhoneSettings(
            PrimaryDisplay.FIAT,
            BitcoinDisplayUnit.CLASSIC,
            "EUR",
            QuickPayLimits(isEnabled = true, amountUsd = 10, dailyMultiplier = 3),
        )
    }

    private val seedStore = FakeSeedStore(SEED)
    private val settingsStore = mock<SettingsStore>()
    private val sparkService = mock<WatchSparkService>()
    private val walletRepo = mock<WalletRepo>()
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, 1uL, hasSynced = true))
    private val reads = mutableListOf<Pair<WalletSeed, String>>()
    private var read: suspend () -> ByteArray? = { BACKUP }
    private val reader = PhoneBackupReader { seed, key ->
        reads += seed to key
        read()
    }

    private lateinit var sut: SettingsSyncRepo

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(sparkService.wipeToken()).thenReturn(TOKEN)
        whenever(sparkService.isCurrent(TOKEN)).thenReturn(true)
        sut = SettingsSyncRepo(testDispatcher, seedStore, reader, settingsStore, sparkService, walletRepo) { NOW }
    }

    @Test
    fun `the phone's settings backup is read with the wallet's seed and adopted`() = test {
        sut.sync().getOrThrow()

        assertEquals(listOf(SEED to "SETTINGS"), reads)
        verify(settingsStore).applyPhoneSettings(SETTINGS, NOW)
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a missing backup keeps the watch's values and turns quickpay off`() = test {
        read = { null }

        sut.sync().getOrThrow()

        verify(settingsStore).clearQuickPay()
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `an unreadable backup or a newer layout keeps the watch's values and turns quickpay off`() = test {
        for (raw in listOf("garbage", """{"version":2,"settings":{"isQuickPayEnabled":true}}""")) {
            read = { raw.toByteArray() }

            assertTrue(sut.sync().isSuccess, raw)
        }

        verify(settingsStore, times(2)).clearQuickPay()
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a read that fails changes nothing`() = test {
        read = { throw IOException("offline") }

        assertIs<IOException>(sut.sync().exceptionOrNull())

        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a read that takes too long is given up and changes nothing`() = test {
        read = { awaitCancellation() }

        val result = async { sut.sync() }
        testScheduler.advanceTimeBy(SettingsSyncRepo.READ_TIMEOUT.inWholeMilliseconds + 1)

        assertIs<SettingsSyncError.Timeout>(result.await().exceptionOrNull())
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `nothing is read unless the wallet is ready`() = test {
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }

            assertIs<SettingsSyncError.NotReady>(sut.sync().exceptionOrNull(), "$status")
        }

        assertTrue(reads.isEmpty())
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `nothing is read during a wipe or without a seed`() = test {
        whenever(sparkService.wipeToken()).thenReturn(null)
        assertIs<SparkError.WipeInProgress>(sut.sync().exceptionOrNull())

        whenever(sparkService.wipeToken()).thenReturn(TOKEN)
        seedStore.seed = null
        assertIs<SeedStoreError.NotFound>(sut.sync().exceptionOrNull())

        assertTrue(reads.isEmpty())
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `an import that a wipe overtook is taken out again`() = test {
        val gate = CompletableDeferred<Unit>()
        read = {
            gate.await()
            BACKUP
        }
        val result = async { sut.sync() }

        whenever(sparkService.isCurrent(TOKEN)).thenReturn(false)
        gate.complete(Unit)

        assertIs<SparkError.WipeInProgress>(result.await().exceptionOrNull())
        val order = inOrder(settingsStore)
        order.verify(settingsStore).applyPhoneSettings(any(), any())
        order.verify(settingsStore).clearPhoneSettings()
    }

    @Test
    fun `a caller that goes away mid-read writes nothing`() = test {
        read = { awaitCancellation() }

        val result = async { sut.sync() }
        result.cancel()

        verify(settingsStore, never()).applyPhoneSettings(any(), any())
        verifyNoMoreInteractions(settingsStore)
    }
}
