package to.bitkit.wear.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.security.FakeDeviceLock
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {
    private val seedStore = mock<SeedStore>()
    private val sparkService = mock<WatchSparkService>()
    private val settingsStore = mock<SettingsStore>()
    private val deviceLock = FakeDeviceLock()
    private val seed = WalletSeed(mnemonic = "words", passphrase = null)

    private lateinit var sut: WalletRepo

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(MutableSharedFlow<SdkEvent>())
        sut = WalletRepo(testDispatcher, seedStore, sparkService, settingsStore, deviceLock)
    }

    @Test
    fun `start connects the stored seed and loads the balance`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(sparkService).connect(seed)
        verify(sparkService).syncWallet()
        assertEquals(WalletState(WalletStatus.Ready, 21_000uL), sut.walletState.value)
    }

    @Test
    fun `start without a seed fails and never connects`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.NotFound()))

        val result = sut.start()

        assertIs<SeedStoreError.NotFound>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any())
        assertEquals(WalletStatus.Missing, sut.walletState.value.status)
        assertNull(sut.walletState.value.balanceSats)
    }

    @Test
    fun `a sync that never finishes times out as an error`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.syncWallet()).doSuspendableAnswer { awaitCancellation() }

        val result = sut.start()

        assertIs<WalletError.Timeout>(result.exceptionOrNull())
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `a connect that never finishes times out as an error`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.connect(seed)).doSuspendableAnswer { awaitCancellation() }

        val result = sut.start()

        assertIs<WalletError.Timeout>(result.exceptionOrNull())
        verify(sparkService, never()).syncWallet()
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `wipe deletes spark storage, then the seed and the settings`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertTrue(result.isSuccess)
        inOrder(sparkService, seedStore, settingsStore) {
            verify(sparkService).wipeStorage()
            verify(seedStore).wipe()
            verify(settingsStore).reset()
        }
        assertFalse(sparkService.isWiping)
        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `wipe still deletes the seed when spark storage fails, and reports the failure`() = test {
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertIs<SparkError.WipeFailed>(result.exceptionOrNull())
        verify(seedStore).wipe()
    }

    @Test
    fun `start wipes the wallet and leaves a notice when the seed key is lost`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.start()

        assertIs<SeedStoreError.KeyInvalidated>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any())
        inOrder(sparkService, seedStore, settingsStore) {
            verify(sparkService).wipeStorage()
            verify(seedStore).wipe()
            verify(settingsStore).reset()
            verify(settingsStore).setRemovedNoticePending(true)
        }
        assertEquals(WalletState(WalletStatus.Removed), sut.walletState.value)
    }

    @Test
    fun `start keeps the wallet when the seed key is refused because the watch is locked`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.DeviceLocked()))

        sut.start()

        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
        verify(seedStore, never()).wipe()
    }

    @Test
    fun `start never reads the seed while the watch is locked`() = test {
        deviceLock.isLocked.update { true }

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        verify(seedStore, never()).load()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `locking the watch disconnects spark and unlocking reconnects it while the app is on screen`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.setForeground(true)
        sut.start()

        deviceLock.isLocked.update { true }

        verify(sparkService).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)

        deviceLock.isLocked.update { false }

        verify(sparkService, times(2)).connect(seed)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `unlocking does not reconnect in the background, coming to the front does`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        deviceLock.isLocked.update { true }

        deviceLock.isLocked.update { false }
        verify(sparkService, times(1)).connect(seed)
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)

        sut.setForeground(true)
        verify(sparkService, times(2)).connect(seed)
    }

    @Test
    fun `a lock the system did not announce is noticed when the app comes to the front`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        deviceLock.pendingIsLocked = true

        sut.setForeground(true)

        verify(sparkService).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `a lock that lands while connecting disconnects before syncing`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.connect(seed)).thenAnswer {
            deviceLock.pendingIsLocked = true
            deviceLock.refresh()
            true
        }

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        verify(sparkService, atLeastOnce()).disconnect()
        verify(sparkService, never()).syncWallet()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `restore saves the seed and reports a failure`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(seedStore.save(seed)).thenReturn(Result.success(Unit))
        assertTrue(sut.restore(seed).isSuccess)

        whenever(seedStore.save(seed)).thenReturn(Result.failure(SeedStoreError.Failed()))
        assertIs<SeedStoreError.Failed>(sut.restore(seed).exceptionOrNull())

        whenever(sparkService.isSupported).thenReturn(false)
        assertIs<SparkError.Unsupported>(sut.restore(seed).exceptionOrNull())
        verify(seedStore, times(2)).save(seed)
    }

    private fun info(balanceSats: ULong) = GetInfoResponse(
        identityPubkey = "pubkey",
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = 1uL,
    )
}
