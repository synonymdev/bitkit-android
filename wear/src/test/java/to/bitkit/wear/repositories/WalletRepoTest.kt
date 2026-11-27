package to.bitkit.wear.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import to.bitkit.wear.security.DeviceLock
import to.bitkit.wear.security.FakeDeviceLock
import to.bitkit.wear.security.FakeForegroundMonitor
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRepoTest : BaseUnitTest() {
    private val seedStore = mock<SeedStore>()
    private val sparkService = mock<WatchSparkService>()
    private val settingsStore = mock<SettingsStore>()
    private val deviceLock = FakeDeviceLock()
    private val networkMonitor = FakeNetworkMonitor()
    private val foregroundMonitor = FakeForegroundMonitor()
    private val seed = WalletSeed(mnemonic = "words", passphrase = null)

    private lateinit var sut: WalletRepo

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(MutableSharedFlow<SdkEvent>())
        whenever { sparkService.syncWallet() }.thenReturn(synced)
        whenever { seedStore.exists() }.thenReturn(false)
        whenever { seedStore.wipe() }.thenReturn(Result.success(Unit))
        whenever { settingsStore.isWipePending() }.thenReturn(false)
        whenever(sparkService.wipeToken()).thenReturn(0)
        whenever(sparkService.isCurrent(any())).thenReturn(true)
        sut = WalletRepo(
            testDispatcher,
            seedStore,
            sparkService,
            settingsStore,
            deviceLock,
            networkMonitor,
            foregroundMonitor,
        )
    }

    override fun onTestEnd() = foregroundMonitor.isForeground.update { false }

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
            verify(settingsStore).beginWipe(false)
            verify(sparkService).beginWipe()
            verify(seedStore).wipe()
            verify(sparkService).wipeStorage()
            verify(settingsStore).finishWipe()
            verify(sparkService).endWipe()
        }
        assertEquals(WalletState(WalletStatus.Missing), sut.walletState.value)
    }

    @Test
    fun `wipe still deletes spark storage when the seed fails, reports it and keeps its note`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.failure(SeedStoreError.Failed()))

        val result = sut.wipe()

        assertIs<SeedStoreError.Failed>(result.exceptionOrNull())
        verify(sparkService).wipeStorage()
        verify(settingsStore, never()).finishWipe()
        verify(sparkService).endWipe()
        assertEquals(WalletState(WalletStatus.Error), sut.walletState.value)
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
    fun `wipe writes its note again when the first write fails`() = test {
        var writes = 0
        whenever(settingsStore.beginWipe(any())).thenAnswer { if (++writes == 1) throw SeedStoreError.Failed() }
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        assertTrue(sut.wipe().isSuccess)

        verify(settingsStore, times(2)).beginWipe(false)
        verify(seedStore).wipe()
        verify(settingsStore).finishWipe()
    }

    @Test
    fun `a removal whose note cannot be written deletes nothing and fails`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(settingsStore.beginWipe(any())).thenAnswer { throw SeedStoreError.Failed() }
        sut.start()

        assertIs<SeedStoreError.Failed>(sut.wipe().exceptionOrNull())

        verify(settingsStore, times(2)).beginWipe(false)
        verify(sparkService, never()).beginWipe()
        verify(seedStore, never()).wipe()
        verify(sparkService, never()).wipeStorage()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a lost key wipes the wallet even when the note cannot be written`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))
        whenever(settingsStore.beginWipe(any())).thenAnswer { throw SeedStoreError.Failed() }

        sut.start()

        verify(settingsStore, times(2)).beginWipe(true)
        verify(seedStore).wipe()
        verify(sparkService).wipeStorage()
        assertEquals(WalletStatus.Removed, sut.walletState.value.status)
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
    fun `a lost key wipe reports removed as its only status after connecting`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        val statuses = mutableListOf<WalletStatus>()
        val collector = launch { sut.walletState.collect { statuses += it.status } }

        sut.start()
        collector.cancelAndJoin()

        assertEquals(listOf(WalletStatus.Idle, WalletStatus.Connecting, WalletStatus.Removed), statuses)
    }

    @Test
    fun `a lost key wipe that fails still ends removed and keeps its note`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeInProgress() }

        sut.start()

        verify(settingsStore, never()).finishWipe()
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
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }
        whenever(sparkService.connect(seed, 0)).doSuspendableAnswer {
            sut.wipe()
            whenever(sparkService.isCurrent(0)).thenReturn(false)
            throw SparkError.WipeInProgress()
        }

        val result = sut.start()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        assertEquals(WalletState(WalletStatus.Error), sut.walletState.value)
    }

    @Test
    fun `a start that fails while a wipe runs leaves the state for the wipe to set`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        val wipeStorage = CompletableDeferred<Unit>()
        whenever(sparkService.wipeStorage()).doSuspendableAnswer {
            wipeStorage.await()
            throw SparkError.WipeFailed("dir")
        }
        var wipe: Job? = null
        whenever(sparkService.connect(seed, 0)).doSuspendableAnswer {
            wipe = launch { sut.wipe() }
            whenever(sparkService.isCurrent(0)).thenReturn(false)
            throw SparkError.WipeInProgress()
        }

        val result = sut.start()
        val statusDuringWipe = sut.walletState.value.status
        wipeStorage.complete(Unit)
        wipe?.join()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        assertEquals(WalletStatus.Connecting, statusDuringWipe)
        assertEquals(WalletState(WalletStatus.Error), sut.walletState.value)
    }

    @Test
    fun `a wipe that ends before a start reports connecting keeps its status, and no seed is read`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }
        sut.wipe()
        whenever(sparkService.wipeToken()).thenAnswer {
            whenever(sparkService.isCurrent(0)).thenReturn(false)
            0
        }

        val result = sut.start()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        verify(seedStore, never()).load()
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletState(WalletStatus.Error), sut.walletState.value)
    }

    @Test
    fun `a start begun while a wipe runs reads no seed, connects nothing and leaves the state alone`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.wipeToken()).thenReturn(null)

        val result = sut.start()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        verify(seedStore, never()).load()
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `a start that finished after a wipe ended is dropped and never ready`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            sut.wipe()
            whenever(sparkService.isCurrent(0)).thenReturn(false)
            synced
        }

        val result = sut.start()

        assertIs<SparkError.WipeInProgress>(result.exceptionOrNull())
        assertEquals(WalletStatus.Missing, sut.walletState.value.status)
    }

    @Test
    fun `start finishes a wipe that did not complete instead of connecting`() = test {
        whenever(settingsStore.isWipePending()).thenReturn(true)
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.NotFound()))

        sut.start()

        inOrder(seedStore) {
            verify(seedStore).wipe()
            verify(seedStore).load()
        }
        verify(sparkService).wipeStorage()
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletStatus.Missing, sut.walletState.value.status)
    }

    @Test
    fun `start refuses while a wipe that did not complete cannot be finished`() = test {
        whenever(settingsStore.isWipePending()).thenReturn(true)
        whenever(seedStore.wipe()).thenReturn(Result.failure(SeedStoreError.Failed()))
        whenever(seedStore.load()).thenReturn(Result.success(seed))

        val result = sut.start()

        assertIs<SeedStoreError.Failed>(result.exceptionOrNull())
        verify(seedStore, never()).load()
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `start refuses when the wipe note cannot be read`() = test {
        whenever(settingsStore.isWipePending()).thenAnswer { throw SeedStoreError.Failed() }
        whenever(seedStore.load()).thenReturn(Result.success(seed))

        val result = sut.start()

        assertIs<SeedStoreError.Failed>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
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
        foregroundMonitor.isForeground.update { false }
        deviceLock.isLocked.update { true }

        deviceLock.isLocked.update { false }
        verify(sparkService, times(1)).connect(seed, 0)
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)

        foregroundMonitor.isForeground.update { true }
        verify(sparkService, times(2)).connect(seed, 0)
    }

    @Test
    fun `a lock the system did not announce is noticed when the app comes to the front`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        foregroundMonitor.isForeground.update { false }
        deviceLock.pendingIsLocked = true

        foregroundMonitor.isForeground.update { true }

        verify(sparkService, times(2)).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `a start that finds a lock the system did not announce disconnects a wallet left connected`() = test {
        val unannouncedLock = mock<DeviceLock>()
        whenever(unannouncedLock.isLocked).thenReturn(MutableStateFlow(false))
        val sut = WalletRepo(
            testDispatcher,
            seedStore,
            sparkService,
            settingsStore,
            unannouncedLock,
            networkMonitor,
            foregroundMonitor,
        )
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.syncWallet()).thenReturn(offline)
        sut.start()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
        verify(sparkService, never()).disconnect()
        whenever(unannouncedLock.isLocked).thenReturn(MutableStateFlow(true))

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        verify(sparkService).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `a lock that lands while a removal runs leaves the wallet missing`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        val disconnect = CompletableDeferred<Unit>()
        whenever(sparkService.disconnect()).doSuspendableAnswer { disconnect.await() }
        deviceLock.isLocked.update { true }

        sut.wipe()
        disconnect.complete(Unit)

        assertEquals(WalletState(WalletStatus.Missing), sut.walletState.value)
    }

    @Test
    fun `a lock that lands while a lost key wipe runs leaves the wallet removed`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.KeyInvalidated()))
        val disconnect = CompletableDeferred<Unit>()
        whenever(sparkService.disconnect()).doSuspendableAnswer { disconnect.await() }
        whenever(sparkService.wipeStorage()).doSuspendableAnswer { deviceLock.isLocked.update { true } }

        sut.start()
        disconnect.complete(Unit)

        verify(sparkService).disconnect()
        assertEquals(WalletState(WalletStatus.Removed), sut.walletState.value)
    }

    @Test
    fun `the window regaining focus reads the lock state and reconnects after an unlock`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        deviceLock.pendingIsLocked = true
        sut.refreshLock()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)

        deviceLock.pendingIsLocked = false
        sut.refreshLock()

        verify(sparkService, times(2)).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a lock that lands while syncing leaves the wallet locked and disconnected, never ready`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(sparkService.syncWallet()).thenAnswer {
            deviceLock.isLocked.update { true }
            synced
        }

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        verify(sparkService, atLeastOnce()).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `a lock and unlock that land while the balance loads reconnect, never ready when disconnected`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        var reads = 0
        whenever(sparkService.getInfo()).thenAnswer {
            if (++reads == 1) {
                deviceLock.isLocked.update { true }
                deviceLock.isLocked.update { false }
            }
            info(21_000uL)
        }

        sut.start()

        inOrder(sparkService) {
            verify(sparkService).connect(seed, 0)
            verify(sparkService, atLeastOnce()).disconnect()
            verify(sparkService).connect(seed, 0)
        }
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
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
        foregroundMonitor.isForeground.update { false }
        networkMonitor.isOnline.update { false }

        networkMonitor.isOnline.update { true }
        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)

        foregroundMonitor.isForeground.update { true }
        verify(sparkService, times(2)).syncWallet()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `coming to the front while still offline does not sync`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        foregroundMonitor.isForeground.update { false }
        networkMonitor.isOnline.update { false }

        foregroundMonitor.isForeground.update { true }

        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
    }

    @Test
    fun `the network returning leaves an error for the wearer to retry`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.syncWallet()).thenReturn(offline)
        networkMonitor.isOnline.update { false }
        sut.start()

        networkMonitor.isOnline.update { true }

        verify(sparkService, times(1)).syncWallet()
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
    }

    @Test
    fun `the network returning while a start fails offline starts once more`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        networkMonitor.isOnline.update { false }
        var syncs = 0
        whenever(sparkService.syncWallet()).thenAnswer {
            if (++syncs > 1) return@thenAnswer synced
            networkMonitor.isOnline.update { true }
            offline
        }

        val result = sut.start()

        assertTrue(result.isSuccess)
        assertEquals(2, syncs)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `the network returning while the first start fails starts once more`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        networkMonitor.isOnline.update { false }
        var syncs = 0
        whenever(sparkService.syncWallet()).thenAnswer {
            if (++syncs > 1) return@thenAnswer synced
            networkMonitor.isOnline.update { true }
            offline
        }

        sut.start()

        assertEquals(2, syncs)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a failed start is not repeated when the network returned in the background or not at all`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        networkMonitor.isOnline.update { false }
        whenever(sparkService.syncWallet()).thenReturn(offline)

        sut.start()
        verify(sparkService, times(2)).syncWallet()

        whenever(sparkService.syncWallet()).thenAnswer {
            foregroundMonitor.isForeground.update { false }
            networkMonitor.isOnline.update { true }
            offline
        }
        sut.start()

        verify(sparkService, times(3)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
    }

    @Test
    fun `the app leaving the screen disconnects spark after the grace period`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()

        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE - 1.seconds)
        verify(sparkService, never()).disconnect()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)

        testScheduler.advanceTimeBy(2.seconds)

        verify(sparkService).disconnect()
        assertEquals(WalletState(WalletStatus.Paused, 21_000uL, hasSynced = true), sut.walletState.value)
    }

    @Test
    fun `the app returning within the grace period keeps spark connected`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()

        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE - 1.seconds)
        foregroundMonitor.isForeground.update { true }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE)

        verify(sparkService, never()).disconnect()
        verify(sparkService, times(1)).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `the app returning after spark was disconnected in the background connects again`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)

        foregroundMonitor.isForeground.update { true }

        verify(sparkService, times(2)).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `nothing starts in the background, and the start runs once the app is on screen`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        foregroundMonitor.isForeground.update { false }

        val result = sut.start()

        assertIs<WalletError.Backgrounded>(result.exceptionOrNull())
        verify(seedStore, never()).load()
        verify(sparkService, never()).connect(any(), any())
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)

        foregroundMonitor.isForeground.update { true }

        verify(sparkService).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `the network or an unlock never starts the wallet in the background`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)

        networkMonitor.isOnline.update { false }
        networkMonitor.isOnline.update { true }
        deviceLock.isLocked.update { true }
        deviceLock.isLocked.update { false }

        verify(sparkService, times(1)).connect(seed, 0)
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)
    }

    @Test
    fun `a start the background overtook disconnects and stays paused`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            foregroundMonitor.isForeground.update { false }
            delay(WalletRepo.BACKGROUND_GRACE + 1.seconds)
            synced
        }

        val result = sut.start()

        assertIs<WalletError.Overtaken>(result.exceptionOrNull())
        verify(sparkService, atLeastOnce()).disconnect()
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)
    }

    @Test
    fun `the app returning while the pause disconnects lets it finish, then connects again`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        val disconnecting = CompletableDeferred<Unit>()
        whenever(sparkService.disconnect()).doSuspendableAnswer { disconnecting.await() }
        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)
        verify(sparkService).disconnect()

        foregroundMonitor.isForeground.update { true }
        verify(sparkService, times(1)).connect(seed, 0)

        disconnecting.complete(Unit)

        verify(sparkService, times(2)).connect(seed, 0)
        verify(sparkService, times(2)).syncWallet()
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a pause whose disconnect fails still pauses, and the app returning connects again`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.disconnect()).thenAnswer { throw SparkError.NotConnected() }

        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)

        assertEquals(WalletState(WalletStatus.Paused, 21_000uL, hasSynced = true), sut.walletState.value)

        foregroundMonitor.isForeground.update { true }

        verify(sparkService, times(2)).connect(seed, 0)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a lock disconnects again after a pause whose disconnect failed`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)
        verify(sparkService, times(1)).disconnect()

        deviceLock.isLocked.update { true }

        verify(sparkService, times(2)).disconnect()
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)
    }

    @Test
    fun `a lock whose disconnect failed is tried again by the next lock and the next pause`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        deviceLock.isLocked.update { true }
        verify(sparkService, times(1)).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
        foregroundMonitor.isForeground.update { false }
        deviceLock.isLocked.update { false }

        deviceLock.isLocked.update { true }
        verify(sparkService, times(2)).disconnect()

        testScheduler.advanceTimeBy(WalletRepo.BACKGROUND_GRACE + 1.seconds)
        verify(sparkService, times(3)).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `a start that finds the watch still locked tries a disconnect that failed again`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        deviceLock.isLocked.update { true }
        verify(sparkService, times(1)).disconnect()

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        verify(sparkService, times(2)).disconnect()
        assertEquals(WalletStatus.Locked, sut.walletState.value.status)
    }

    @Test
    fun `the defensive lock guard leaves a removed wallet missing when a start outlives its removal`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            sut.wipe()
            deviceLock.isLocked.update { true }
            synced
        }

        val result = sut.start()

        assertIs<WalletError.DeviceLocked>(result.exceptionOrNull())
        assertEquals(WalletStatus.Missing, sut.walletState.value.status)
    }

    @Test
    fun `a start the app left before it connected is disconnected and paused, never ready in the background`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        var leaves = true
        whenever(sparkService.connect(seed, 0)).thenAnswer {
            if (leaves) foregroundMonitor.isForeground.update { false }
            true
        }

        val result = sut.start()

        assertIs<WalletError.Backgrounded>(result.exceptionOrNull())
        verify(sparkService).disconnect()
        verify(sparkService, never()).syncWallet()
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)

        leaves = false
        foregroundMonitor.isForeground.update { true }

        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `a start the app left while it synced is disconnected and paused, never ready in the background`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        whenever(sparkService.syncWallet()).thenAnswer {
            foregroundMonitor.isForeground.update { false }
            synced
        }

        val result = sut.start()

        assertIs<WalletError.Backgrounded>(result.exceptionOrNull())
        verify(sparkService).disconnect()
        assertEquals(WalletStatus.Paused, sut.walletState.value.status)
    }

    @Test
    fun `a start whose caller is cancelled goes on and settles`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        val syncing = CompletableDeferred<Unit>()
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            syncing.await()
            synced
        }

        val caller = launch { sut.start() }
        caller.cancel()
        assertEquals(WalletStatus.Connecting, sut.walletState.value.status)

        syncing.complete(Unit)
        caller.join()

        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
    }

    @Test
    fun `an offline wallet with a network syncs again after 5, 15 and 60 seconds, then every 60`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.syncWallet()).thenReturn(offline)
        sut.start()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)
        verify(sparkService, times(2)).syncWallet()

        testScheduler.advanceTimeBy(4.seconds)
        verify(sparkService, times(2)).syncWallet()
        testScheduler.advanceTimeBy(2.seconds)
        verify(sparkService, times(3)).syncWallet()
        testScheduler.advanceTimeBy(15.seconds)
        verify(sparkService, times(4)).syncWallet()
        testScheduler.advanceTimeBy(60.seconds)
        verify(sparkService, times(5)).syncWallet()
        testScheduler.advanceTimeBy(60.seconds)
        verify(sparkService, times(6)).syncWallet()
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)

        whenever(sparkService.syncWallet()).thenReturn(synced)
        testScheduler.advanceTimeBy(60.seconds)

        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
        testScheduler.advanceTimeBy(5.minutes)
        verify(sparkService, times(7)).syncWallet()
    }

    @Test
    fun `an offline wallet starts its waits over after it synced`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        whenever(sparkService.syncWallet()).thenReturn(offline)
        sut.start()
        testScheduler.advanceTimeBy(6.seconds)
        whenever(sparkService.syncWallet()).thenReturn(synced)
        testScheduler.advanceTimeBy(15.seconds)
        assertEquals(WalletStatus.Ready, sut.walletState.value.status)
        verify(sparkService, times(4)).syncWallet()

        whenever(sparkService.syncWallet()).thenReturn(offline)
        sut.start()
        testScheduler.advanceTimeBy(6.seconds)

        verify(sparkService, times(6)).syncWallet()
    }

    @Test
    fun `an offline wallet does not sync again without a network or in the background`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))
        sut.start()
        networkMonitor.isOnline.update { false }
        assertEquals(WalletStatus.Offline, sut.walletState.value.status)

        testScheduler.advanceTimeBy(5.minutes)
        verify(sparkService, times(1)).syncWallet()

        whenever(sparkService.syncWallet()).thenReturn(offline)
        networkMonitor.isOnline.update { true }
        verify(sparkService, times(2)).syncWallet()
        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(5.seconds)

        verify(sparkService, times(2)).syncWallet()
    }

    @Test
    fun `a launch without a seed deletes the key and the file a seed left behind`() = test {
        assertTrue(sut.recover().isSuccess)

        verify(seedStore).wipe()
        verify(sparkService).wipeStorage()
    }

    @Test
    fun `a launch still succeeds when what a seed left behind cannot be deleted`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.failure(SeedStoreError.Failed()))

        assertTrue(sut.recover().isSuccess)

        verify(sparkService).wipeStorage()
    }

    @Test
    fun `a launch with a seed stored never wipes the seed store`() = test {
        whenever(seedStore.exists()).thenReturn(true)

        assertTrue(sut.recover().isSuccess)

        verify(seedStore, never()).wipe()
        verify(sparkService, never()).wipeStorage()
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
