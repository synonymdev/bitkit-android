package to.bitkit.wear.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
import to.bitkit.wear.security.FakeNetworkMonitor
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.SparkSync
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
    private val networkMonitor = FakeNetworkMonitor()
    private val seed = WalletSeed(mnemonic = "words", passphrase = null)

    private lateinit var sut: WalletRepo

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(MutableSharedFlow<SdkEvent>())
        whenever { sparkService.syncWallet() }.thenReturn(synced)
        whenever { seedStore.exists() }.thenReturn(false)
        whenever { settingsStore.isWipePending() }.thenReturn(false)
        sut = WalletRepo(testDispatcher, seedStore, sparkService, settingsStore, deviceLock, networkMonitor)
    }

    @Test
    fun `start connects the stored seed and loads the balance`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(sparkService).connect(seed, 0)
        verify(sparkService).syncWallet()
        assertEquals(WalletState(WalletStatus.Ready, 21_000uL, hasSynced = true), sut.walletState.value)
    }

    @Test
    fun `start without a seed fails and never connects`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.NotFound()))

        val result = sut.start()

        assertIs<SeedStoreError.NotFound>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any(), any())
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
        whenever(sparkService.connect(seed, 0)).doSuspendableAnswer { awaitCancellation() }

        val result = sut.start()

        assertIs<WalletError.Timeout>(result.exceptionOrNull())
        verify(sparkService, never()).syncWallet()
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `wipe notes itself, then deletes the seed, spark storage and the settings`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertTrue(result.isSuccess)
        inOrder(sparkService, seedStore, settingsStore) {
            verify(sparkService).beginWipe()
            verify(settingsStore).beginWipe(false)
            verify(seedStore).wipe()
            verify(sparkService).wipeStorage()
            verify(settingsStore).finishWipe()
            verify(sparkService).endWipe()
        }
        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `wipe still deletes spark storage when the seed fails, reports it and keeps its note`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.failure(SeedStoreError.Failed()))

        val result = sut.wipe()

        assertIs<SeedStoreError.Failed>(result.exceptionOrNull())
        verify(sparkService).wipeStorage()
        verify(settingsStore, never()).finishWipe()
        verify(sparkService).endWipe()
    }

    @Test
    fun `wipe reports a spark storage failure and keeps its note`() = test {
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertIs<SparkError.WipeFailed>(result.exceptionOrNull())
        verify(seedStore).wipe()
        verify(settingsStore, never()).finishWipe()
    }

    @Test
    fun `wipe still deletes everything when its note cannot be written`() = test {
        whenever(settingsStore.beginWipe(any())).thenAnswer { throw SeedStoreError.Failed() }
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        assertTrue(sut.wipe().isSuccess)

        verify(seedStore).wipe()
        verify(sparkService).wipeStorage()
        verify(settingsStore).finishWipe()
    }

    @Test
    fun `a cancelled caller does not stop the wipe`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))
        whenever(sparkService.wipeStorage()).doSuspendableAnswer { yield() }

        val caller = launch { sut.wipe() }
        caller.cancelAndJoin()

        verify(settingsStore).finishWipe()
        verify(sparkService).endWipe()
    }

    @Test
    fun `start wipes the wallet and leaves a notice when the seed key is lost`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.start()

        assertIs<SeedStoreError.KeyInvalidated>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any(), any())
        inOrder(sparkService, seedStore, settingsStore) {
            verify(settingsStore).beginWipe(true)
            verify(seedStore).wipe()
            verify(sparkService).wipeStorage()
            verify(settingsStore).finishWipe()
        }
        assertEquals(WalletState(WalletStatus.Removed), sut.walletState.value)
    }

    @Test
    fun `start keeps the wallet for every seed error but a lost key`() = test {
        for (error in listOf(SeedStoreError.Failed(), SeedStoreError.Corrupted(), SeedStoreError.DeviceLocked())) {
            whenever(seedStore.load()).thenReturn(Result.failure(error))

            sut.start()
        }

        verify(seedStore, never()).wipe()
        verify(sparkService, never()).wipeStorage()
        verify(settingsStore, never()).beginWipe(any())
    }

    @Test
    fun `a start that a wipe overtook leaves the wiped state alone`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))
        whenever(sparkService.connect(seed, 0)).thenAnswer {
            whenever(sparkService.wipeCount).thenReturn(1)
            throw SparkError.WipeInProgress()
        }

        val result = sut.start()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        assertEquals(WalletStatus.Connecting, sut.walletState.value.status)
        sut.wipe()
        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `starts run one at a time`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        var running = 0
        var maxRunning = 0
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            maxRunning = maxOf(maxRunning, ++running)
            yield()
            running--
            synced
        }

        listOf(launch { sut.start() }, launch { sut.start() }).joinAll()

        assertEquals(1, maxRunning)
        verify(sparkService, times(2)).syncWallet()
    }

    @Test
    fun `a balance read while the wallet is wiped is dropped`() = test {
        whenever(sparkService.isWiping).thenReturn(true)
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))

        sut.refreshBalance()

        assertNull(sut.walletState.value.balanceSats)
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

        verify(sparkService, times(2)).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `unlocking does not reconnect in the background, coming to the front does`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        deviceLock.isLocked.update { true }

        deviceLock.isLocked.update { false }
        verify(sparkService, times(1)).connect(seed, 0)
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)

        sut.setForeground(true)
        verify(sparkService, times(2)).connect(seed, 0)
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
        whenever(sparkService.connect(seed, 0)).thenAnswer {
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

    @Test
    fun `restore forgets the state of the wallet before it`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(seedStore.save(seed)).thenReturn(Result.success(Unit))
        sut.start()

        sut.restore(seed).getOrThrow()

        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `restore deletes spark storage left without a seed before it saves`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(seedStore.save(seed)).thenReturn(Result.success(Unit))

        sut.restore(seed).getOrThrow()

        inOrder(sparkService, seedStore) {
            verify(sparkService).wipeStorage()
            verify(seedStore).save(seed)
        }
    }

    @Test
    fun `restore saves nothing when the storage left before it cannot be deleted`() = test {
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }

        assertIs<SparkError.WipeFailed>(sut.restore(seed).exceptionOrNull())

        verify(seedStore, never()).save(any())
    }

    @Test
    fun `acknowledging the removal finishes when its caller is cancelled`() = test {
        whenever(settingsStore.setRemovedNoticePending(false)).doSuspendableAnswer { yield() }

        val caller = launch { sut.acknowledgeRemoval() }
        caller.cancelAndJoin()

        verify(settingsStore).setRemovedNoticePending(false)
    }

    @Test
    fun `a sync that does not reach spark before any sync this session is an error`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(0uL))
        whenever(sparkService.syncWallet()).thenReturn(offline)

        val result = sut.start()

        assertIs<WalletError.NotSynced>(result.exceptionOrNull())
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
        assertFalse(sut.walletState.value.hasSynced)
    }

    @Test
    fun `a sync that does not reach spark after one that did keeps the balance as offline`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.syncWallet()).thenReturn(offline)

        sut.start()

        assertEquals(WalletState(WalletStatus.Offline, 21_000uL, hasSynced = true), sut.walletState.value)
    }

    @Test
    fun `losing the network turns a ready wallet offline, and its return syncs it again`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.setForeground(true)
        sut.start()

        networkMonitor.isOnline.update { false }

        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
        assertEquals(21_000uL, sut.walletState.value.balanceSats)

        networkMonitor.isOnline.update { true }

        verify(sparkService, times(2)).syncWallet()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `the network returning syncs an offline wallet only once the app is on screen`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        networkMonitor.isOnline.update { false }

        networkMonitor.isOnline.update { true }
        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)

        sut.setForeground(true)
        verify(sparkService, times(2)).syncWallet()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `coming to the front while still offline does not sync`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        networkMonitor.isOnline.update { false }

        sut.setForeground(true)

        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
    }

    @Test
    fun `the network returning leaves an error for the wearer to retry`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.syncWallet()).thenReturn(offline)
        sut.setForeground(true)
        networkMonitor.isOnline.update { false }
        sut.start()

        networkMonitor.isOnline.update { true }

        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `wipe forgets that the wallet synced`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))
        sut.start()

        sut.wipe()

        assertFalse(sut.walletState.value.hasSynced)
    }

    private val synced = SparkSync(error = null, syncedAtSecs = 1uL)
    private val offline = SparkSync(error = "Network(message=dns error)", syncedAtSecs = null)

    private fun info(balanceSats: ULong) = GetInfoResponse(
        identityPubkey = "pubkey",
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = 1uL,
    )
}
