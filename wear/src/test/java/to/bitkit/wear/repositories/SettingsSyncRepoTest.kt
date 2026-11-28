package to.bitkit.wear.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
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
import to.bitkit.wear.services.PhoneBackupUnreadable
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncRepoTest : BaseUnitTest() {
    private companion object {
        const val NOW = 1_795_867_200_000L
        const val TOKEN = 4
        const val WAIT_SECS = 10L
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
    private var elapsedMs = 90_000L
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
        sut = SettingsSyncRepo(
            testDispatcher,
            seedStore,
            reader,
            settingsStore,
            sparkService,
            walletRepo,
            { elapsedMs },
            { NOW },
        )
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

            assertTrue(sut.sync(isForced = true).isSuccess, raw)
        }

        verify(settingsStore, times(2)).clearQuickPay()
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a backup that cannot be decrypted turns quickpay off at once`() = test {
        read = { throw PhoneBackupUnreadable() }

        assertTrue(sut.sync().isSuccess)

        verify(settingsStore).clearQuickPay()
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `an import right after another reads nothing, unless the wearer asked or a minute passed`() = test {
        sut.sync().getOrThrow()

        elapsedMs += SettingsSyncRepo.MIN_READ_GAP.inWholeMilliseconds - 1
        assertTrue(sut.sync().isSuccess)
        assertEquals(1, reads.size)

        assertTrue(sut.sync(isForced = true).isSuccess)
        assertEquals(2, reads.size)

        elapsedMs += SettingsSyncRepo.MIN_READ_GAP.inWholeMilliseconds
        assertTrue(sut.sync().isSuccess)
        assertEquals(3, reads.size)
        verify(settingsStore, times(3)).applyPhoneSettings(SETTINGS, NOW)
    }

    @Test
    fun `a missing backup counts as read, a failed read does not, and another wallet's read never does`() = test {
        read = { throw IOException("offline") }
        assertTrue(sut.sync().isFailure)
        read = { null }
        assertTrue(sut.sync().isSuccess)
        assertTrue(sut.sync().isSuccess)
        assertEquals(2, reads.size)

        // A wipe and a restore later the last read was the removed wallet's.
        whenever(sparkService.wipeToken()).thenReturn(TOKEN + 2)
        whenever(sparkService.isCurrent(TOKEN + 2)).thenReturn(true)
        assertTrue(sut.sync().isSuccess)
        assertEquals(3, reads.size)
    }

    @Test
    fun `a second import that waited for the first joins it and reads nothing`() = test {
        val gate = CompletableDeferred<Unit>()
        read = {
            gate.await()
            BACKUP
        }
        val first = async { sut.sync() }
        val second = async { sut.sync() }

        gate.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, reads.size)
    }

    @Test
    fun `a read is given up the moment the wallet stops being ready`() = test {
        for (status in listOf(WalletStatus.Locked, WalletStatus.Paused, WalletStatus.Offline)) {
            walletState.update { it.copy(status = WalletStatus.Ready) }
            var isReadEnded = false
            read = {
                try {
                    awaitCancellation()
                } finally {
                    isReadEnded = true
                }
            }
            val result = async { sut.sync(isForced = true) }
            assertFalse(result.isCompleted)

            walletState.update { it.copy(status = status) }

            assertIs<SettingsSyncError.NotReady>(result.await().exceptionOrNull(), "$status")
            assertTrue(isReadEnded, "$status")
        }
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
        var isReadEnded = false
        read = {
            try {
                awaitCancellation()
            } finally {
                isReadEnded = true
            }
        }

        val result = async { sut.sync() }
        testScheduler.advanceTimeBy(SettingsSyncRepo.READ_TIMEOUT.inWholeMilliseconds + 1)

        assertIs<SettingsSyncError.Timeout>(result.await().exceptionOrNull())
        assertTrue(isReadEnded)
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a read that blocks its thread holds back neither the import nor its caller`() {
        val isReading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isReadEnded = CountDownLatch(1)
        val blocking = SettingsSyncRepo(
            Dispatchers.IO,
            seedStore,
            { _, _ ->
                isReading.countDown()
                release.await()
                isReadEnded.countDown()
                BACKUP
            },
            settingsStore,
            sparkService,
            walletRepo,
            { elapsedMs },
            { NOW },
        )
        try {
            runBlocking {
                val result = async(Dispatchers.Default) { blocking.sync() }
                assertTrue(isReading.await(WAIT_SECS, TimeUnit.SECONDS))

                // The wallet stops being ready: the import answers though the read is stuck.
                walletState.update { it.copy(status = WalletStatus.Locked) }
                val failure = withTimeout(WAIT_SECS.seconds) { result.await() }.exceptionOrNull()
                assertIs<SettingsSyncError.NotReady>(failure)

                // So does a caller that stops waiting, as a payment does after its 5 s.
                walletState.update { it.copy(status = WalletStatus.Ready) }
                val waited = withTimeout(WAIT_SECS.seconds) { withTimeoutOrNull(50) { blocking.sync(isForced = true) } }
                assertNull(waited)
            }
        } finally {
            release.countDown()
        }
        // The second import was only left waiting, so it lands once its read ends.
        assertTrue(isReadEnded.await(WAIT_SECS, TimeUnit.SECONDS))
        runBlocking { verify(settingsStore, timeout(WAIT_SECS * 1_000)).applyPhoneSettings(SETTINGS, NOW) }
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
    fun `an import whose caller stopped waiting still lands, and the next call finds it`() = test {
        val gate = CompletableDeferred<Unit>()
        read = {
            gate.await()
            BACKUP
        }

        val result = async { sut.sync() }
        result.cancel()
        verifyNoMoreInteractions(settingsStore)
        val next = async { sut.sync() }
        gate.complete(Unit)

        assertTrue(next.await().isSuccess)
        assertEquals(1, reads.size)
        verify(settingsStore).applyPhoneSettings(SETTINGS, NOW)
        verifyNoMoreInteractions(settingsStore)
    }

    @Test
    fun `a caller that goes away after the read still gets the outcome written whole`() = test {
        val stored = CompletableDeferred<Unit>()
        whenever(settingsStore.applyPhoneSettings(any(), any())).doSuspendableAnswer { stored.await() }
        val result = async { sut.sync() }

        // A wipe overtakes the import while it is stored, and the caller leaves.
        whenever(sparkService.isCurrent(TOKEN)).thenReturn(false)
        result.cancel()
        stored.complete(Unit)

        val order = inOrder(settingsStore)
        order.verify(settingsStore).applyPhoneSettings(SETTINGS, NOW)
        order.verify(settingsStore).clearPhoneSettings()
    }
}
