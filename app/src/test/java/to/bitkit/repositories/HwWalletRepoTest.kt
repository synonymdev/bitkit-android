package to.bitkit.repositories

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WalletBalance
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.HwWalletData
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.TransportType
import to.bitkit.models.toCoreNetwork
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Suppress("LargeClass")
class HwWalletRepoTest : BaseUnitTest() {

    private val trezorRepo = mock<TrezorRepo>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val settingsStore = mock<SettingsStore>()
    private val clock = mock<Clock>()

    private lateinit var storeData: MutableStateFlow<HwWalletData>
    private lateinit var settingsData: MutableStateFlow<SettingsData>
    private lateinit var trezorState: MutableStateFlow<TrezorState>
    private lateinit var watcherEvents: MutableSharedFlow<Pair<String, WatcherEvent>>

    private val device = KnownDevice(
        id = "dev1",
        name = null,
        path = "ble:AA:BB",
        transportType = TransportType.BLUETOOTH,
        label = "Trezor",
        model = "Safe 5",
        lastConnectedAt = 0L,
        xpubs = mapOf("nativeSegwit" to "zpubNS"),
    )

    @Before
    fun setUp() {
        storeData = MutableStateFlow(HwWalletData(knownDevices = listOf(device)))
        settingsData = MutableStateFlow(SettingsData())
        trezorState = MutableStateFlow(TrezorState())
        watcherEvents = MutableSharedFlow(extraBufferCapacity = 8)
        whenever(hwWalletStore.data).thenReturn(storeData)
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(trezorRepo.state).thenReturn(trezorState)
        whenever(trezorRepo.watcherEvents).thenReturn(watcherEvents)
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(1_700_000_000))
    }

    private fun createRepo() = HwWalletRepo(trezorRepo, hwWalletStore, settingsStore, clock, testDispatcher)

    @Test
    fun `lists a known device with zero balance before any watcher event`() = test {
        val sut = createRepo()

        val wallet = sut.wallets.value.single()
        assertEquals("dev1", wallet.id)
        assertEquals(setOf("dev1"), wallet.deviceIds)
        assertEquals("Trezor", wallet.name)
        assertEquals(0uL, wallet.balanceSats)
        assertEquals(true, sut.walletsLoaded.value)
        assertEquals(0uL, sut.totalSats.value)
    }

    @Test
    fun `does not expose known devices before xpubs are captured`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(xpubs = emptyMap())))

        val sut = createRepo()

        assertEquals(emptyList(), sut.wallets.value)
    }

    @Test
    fun `uses vendor-prefixed model as name when device label is missing`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(label = null, model = "Safe 7")))

        val sut = createRepo()

        assertEquals("Trezor Safe 7", sut.wallets.value.single().name)
    }

    @Test
    fun `uses vendor-prefixed model as name when device label is the factory default`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(label = "Safe 7", model = "Safe 7")))

        val sut = createRepo()

        assertEquals("Trezor Safe 7", sut.wallets.value.single().name)
    }

    @Test
    fun `transactions changed event sets device balance and maps activity`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 10_562_411uL),
                transactions = listOf(receivedTransaction(amount = 10_562_411uL)),
                txCount = 1u,
                blockHeight = 850_000u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val wallet = sut.wallets.value.single()
        assertEquals(10_562_411uL, wallet.balanceSats)
        assertEquals(10_562_411uL, sut.totalSats.value)
        assertEquals(1, wallet.activities.size)
        assertEquals(1, sut.activities.value.size)
        assertEquals(Activity.Onchain::class, wallet.activities.single()::class)
    }

    @Test
    fun `balances from multiple address-type watchers are summed per device`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        assertEquals(150uL, sut.wallets.value.single().balanceSats)
        assertEquals(150uL, sut.totalSats.value)
    }

    @Test
    fun `merges duplicate tx activities from multiple address-type watchers`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(receivedTransaction(amount = 100uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                transactions = listOf(receivedTransaction(amount = 50uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        val activity = sut.wallets.value.single().activities.single() as Activity.Onchain
        assertEquals(PaymentType.RECEIVED, activity.v1.txType)
        assertEquals(150uL, activity.v1.value)
        assertEquals(150uL, sut.wallets.value.single().balanceSats)
    }

    @Test
    fun `merges duplicate tx activities across hardware wallets`() = test {
        val secondDevice = device.copy(
            id = "dev2",
            path = "ble:CC:DD",
            lastConnectedAt = 1L,
            xpubs = mapOf("nativeSegwit" to "zpubNS2"),
        )
        storeData.value = HwWalletData(knownDevices = listOf(device, secondDevice))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(receivedTransaction(amount = 100uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev2|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                transactions = listOf(receivedTransaction(amount = 50uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val activity = sut.activities.value.single() as Activity.Onchain
        assertEquals(2, sut.wallets.value.size)
        assertEquals(PaymentType.RECEIVED, activity.v1.txType)
        assertEquals(150uL, activity.v1.value)
    }

    @Test
    fun `preserves generated timestamp for pending tx refreshes`() = test {
        whenever(clock.now())
            .thenReturn(Instant.fromEpochSeconds(1_800_000_000))
            .thenReturn(Instant.fromEpochSeconds(1_800_000_060))
        val sut = createRepo()
        val pendingTx = receivedTransaction(amount = 100uL).copy(
            txid = "pending",
            blockHeight = null,
            timestamp = null,
            confirmations = 0u,
        )

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(pendingTx),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        val firstTimestamp = (sut.wallets.value.single().activities.single() as Activity.Onchain).v1.timestamp

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(pendingTx),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        val refreshedTimestamp = (sut.wallets.value.single().activities.single() as Activity.Onchain).v1.timestamp

        assertEquals(1_800_000_000uL, firstTimestamp)
        assertEquals(firstTimestamp, refreshedTimestamp)
    }

    @Test
    fun `starts watchers only for the address types the user monitors`() = test {
        storeData.value = HwWalletData(
            knownDevices = listOf(
                device.copy(
                    xpubs = mapOf(
                        "nativeSegwit" to "zpubNS",
                        "taproot" to "zpubTR",
                        "legacy" to "xpubLG",
                    )
                )
            )
        )
        settingsData.value = SettingsData(addressTypesToMonitor = listOf("nativeSegwit", "taproot"))
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any())
        verify(trezorRepo).startWatcher(eq("dev1|taproot"), any(), any(), any(), anyOrNull(), any())
        verify(trezorRepo, never()).startWatcher(eq("dev1|legacy"), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `starts watchers on configured electrum server`() = test {
        val electrumServer = "ssl://custom.example:50002"
        settingsData.value = SettingsData(electrumServer = electrumServer)
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = eq(electrumServer),
        )
    }

    @Test
    fun `restarts active watchers when electrum server changes`() = test {
        val firstServer = "ssl://first.example:50002"
        val secondServer = "ssl://second.example:50002"
        settingsData.value = SettingsData(electrumServer = firstServer)
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever(trezorRepo.stopWatcher(any())).thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        settingsData.value = settingsData.value.copy(electrumServer = secondServer)
        runCurrent()

        verify(trezorRepo).stopWatcher("dev1|nativeSegwit")
        verify(trezorRepo).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = eq(secondServer),
        )
    }

    @Test
    fun `retries watcher start after failure`() = test {
        wheneverStartWatcher().thenReturn(Result.failure(AppError("start failed")), Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any())

        advanceTimeBy(30.seconds)
        runCurrent()

        verify(trezorRepo, times(2)).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `emits received tx only for new inbound transactions after the baseline sync`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }

        // Baseline: full history delivered on watcher start must not emit.
        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(receivedTransaction(amount = 100uL)),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        assertEquals(0, received.size)

        // New inbound tx after the baseline emits once.
        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 150uL),
                transactions = listOf(
                    receivedTransaction(amount = 100uL),
                    receivedTransaction(amount = 50uL).copy(txid = "t2"),
                ),
                txCount = 2u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        assertEquals(listOf(HwWalletReceivedTx(txid = "t2", sats = 50uL)), received)

        // Re-delivering the same set (e.g. confirmation update) must not emit again.
        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 150uL),
                transactions = listOf(
                    receivedTransaction(amount = 100uL),
                    receivedTransaction(amount = 50uL).copy(txid = "t2"),
                ),
                txCount = 2u,
                blockHeight = 3u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        assertEquals(1, received.size)

        job.cancel()
    }

    @Test
    fun `emits received tx once when multiple watchers report the same new tx`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = listOf(receivedTransaction(amount = 100uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                transactions = listOf(receivedTransaction(amount = 50uL).copy(txid = "shared")),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.TAPROOT,
            )
        )

        assertEquals(listOf(HwWalletReceivedTx(txid = "shared", sats = 100uL)), received)
        job.cancel()
    }

    @Test
    fun `does not emit received tx for new outbound transactions`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 40uL),
                transactions = listOf(
                    receivedTransaction(amount = 60uL).copy(txid = "t3", direction = TxDirection.SENT),
                ),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        assertEquals(0, received.size)
        job.cancel()
    }

    @Test
    fun `shows one wallet without double counting when paired over bluetooth and usb`() = test {
        val bleEntry = device.copy(id = "ble1", lastConnectedAt = 1L, xpubs = mapOf("nativeSegwit" to "zpubNS"))
        val usbEntry = bleEntry.copy(id = "usb1", transportType = TransportType.USB, lastConnectedAt = 2L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        val sut = createRepo()

        verify(trezorRepo).startWatcher(eq("ble1|nativeSegwit"), any(), any(), any(), anyOrNull(), any())
        verify(trezorRepo, never()).startWatcher(eq("usb1|nativeSegwit"), any(), any(), any(), anyOrNull(), any())

        watcherEvents.emit(
            "ble1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 421_900uL),
                transactions = listOf(receivedTransaction(amount = 421_900uL)),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val wallet = sut.wallets.value.single()
        assertEquals(421_900uL, wallet.balanceSats)
        assertEquals(421_900uL, sut.totalSats.value)
        assertEquals(1, wallet.activities.size)
        assertEquals(setOf("ble1", "usb1"), wallet.deviceIds)
        assertEquals(TransportType.USB, wallet.transportType)
    }

    @Test
    fun `connected entry wins identity for a wallet paired over both transports`() = test {
        val bleEntry = device.copy(id = "ble1", lastConnectedAt = 2L, xpubs = mapOf("nativeSegwit" to "zpubNS"))
        val usbEntry = bleEntry.copy(id = "usb1", transportType = TransportType.USB, lastConnectedAt = 1L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "usb1", features = mock()),
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        val sut = createRepo()

        val wallet = sut.wallets.value.single()
        assertEquals("usb1", wallet.id)
        assertEquals(setOf("ble1", "usb1"), wallet.deviceIds)
        assertEquals(TransportType.USB, wallet.transportType)
        assertEquals(true, wallet.isConnected)
    }

    @Test
    fun `keeps a stale watcher until stopping it succeeds`() = test {
        storeData.value = HwWalletData(
            knownDevices = listOf(device.copy(xpubs = mapOf("nativeSegwit" to "zpubNS")))
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.failure(AppError("stop failed")))
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        // Stop fails: the watcher data must survive so the balance is not silently wrong.
        settingsData.value = SettingsData(addressTypesToMonitor = emptyList())
        assertEquals(100uL, sut.totalSats.value)

        // Stop succeeds on a later sync: the watcher data is finally dropped.
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        settingsData.value = SettingsData(addressTypesToMonitor = listOf("taproot"))
        assertEquals(0uL, sut.totalSats.value)
    }

    @Test
    fun `resetState clears store and stops active watchers`() = test {
        storeData.value = HwWalletData(
            knownDevices = listOf(device.copy(xpubs = mapOf("nativeSegwit" to "zpubNS")))
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                transactions = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        sut.resetState()

        verify(trezorRepo).stopWatcher("dev1|nativeSegwit")
        verify(trezorRepo).resetState()
        assertEquals(0uL, sut.totalSats.value)
    }

    @Test
    fun `removeDevice stops the device watchers and forgets it`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice("dev1")

        assertEquals(true, result.isSuccess)
        verify(trezorRepo).stopWatcher("dev1|nativeSegwit")
        verify(trezorRepo).forgetDevice("dev1")
    }

    @Test
    fun `removeDevice fails when forget reports credential cleanup failure despite the device being gone`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        whenever { trezorRepo.forgetDevice(any()) }.thenReturn(Result.failure(AppError("clear failed")))
        val sut = createRepo()

        val result = sut.removeDevice("dev1")

        assertEquals(true, result.isFailure)
        verify(trezorRepo).forgetDevice("dev1")
    }

    @Test
    fun `removeDevice fails before forgetting when watcher stop fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.failure(AppError("stop failed")))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice("dev1")

        assertEquals(true, result.isFailure)
        verify(trezorRepo).stopWatcher("dev1|nativeSegwit")
        verify(trezorRepo, never()).forgetDevice(any())
    }

    @Test
    fun `removeDevice forgets every entry of a device paired over both transports`() = test {
        val bleEntry = device.copy(id = "ble1", lastConnectedAt = 1L, xpubs = mapOf("nativeSegwit" to "zpubNS"))
        val usbEntry = bleEntry.copy(id = "usb1", transportType = TransportType.USB, lastConnectedAt = 2L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(bleEntry, usbEntry), emptyList())
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        sut.removeDevice("usb1")

        verify(trezorRepo).stopWatcher("ble1|nativeSegwit")
        verify(trezorRepo).forgetDevice("ble1")
        verify(trezorRepo).forgetDevice("usb1")
    }

    @Test
    fun `removeDevice fails when the device is still present afterwards`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), listOf(device))
        whenever { trezorRepo.forgetDevice(any()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice("dev1")

        assertEquals(true, result.isFailure)
    }

    @Test
    fun `removeDevice restarts watchers when removal verification fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), listOf(device))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice("dev1")
        runCurrent()

        assertEquals(true, result.isFailure)
        verify(trezorRepo, times(2)).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = any(),
            network = any(),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
        )
    }

    @Test
    fun `forwards transport restored to the trezor repo`() = test {
        val sut = createRepo()

        sut.onTransportRestored(TransportType.USB)

        verify(trezorRepo).onTransportRestored(TransportType.USB)
    }

    @Test
    fun `forwards app foregrounded to the trezor repo`() = test {
        val sut = createRepo()

        sut.onAppForegrounded()

        verify(trezorRepo).onAppForegrounded()
    }

    @Test
    fun `signAndBroadcastFunding disconnects stale session when compose fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        whenever(
            trezorRepo.composeTransaction(
                extendedKey = any(),
                outputs = any(),
                feeRates = any(),
                network = any(),
                accountType = anyOrNull(),
                coinSelection = any(),
            )
        ).thenReturn(Result.failure(AppError("compose failed")))
        whenever(trezorRepo.disconnectStaleSession("dev1")).thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.signAndBroadcastFunding(
            deviceId = "dev1",
            address = "bc1qtest",
            sats = 25_000uL,
            satsPerVByte = 2uL,
        )

        assertEquals(true, result.isFailure)
        verify(trezorRepo).disconnectStaleSession("dev1")
        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
        verify(trezorRepo, never()).broadcastRawTx(any())
    }

    @Test
    fun `forwards pairing code calls to the trezor repo`() = test {
        val sut = createRepo()

        sut.submitPairingCode("123456")
        sut.cancelPairingCode()

        verify(trezorRepo).submitPairingCode("123456")
        verify(trezorRepo).cancelPairingCode()
    }

    @Test
    fun `starts watchers on the network configured in Env`() = test {
        storeData.value = HwWalletData(
            knownDevices = listOf(device.copy(xpubs = mapOf("nativeSegwit" to "zpubNS")))
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(
            watcherId = any(),
            extendedKey = any(),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
        )
    }

    private fun walletBalance(total: ULong) = WalletBalance(
        confirmed = total,
        immature = 0uL,
        trustedPending = 0uL,
        untrustedPending = 0uL,
        spendable = total,
        total = total,
    )

    private fun receivedTransaction(amount: ULong) = HistoryTransaction(
        txid = "t1",
        received = amount,
        sent = 0uL,
        net = amount.toLong(),
        fee = null,
        amount = amount,
        direction = TxDirection.RECEIVED,
        blockHeight = 850_000u,
        timestamp = 1_700_000_000uL,
        confirmations = 3u,
    )

    @Test
    fun `scan delegates to trezorRepo`() = test {
        whenever(trezorRepo.scan(includeBluetooth = false)).thenReturn(Result.success(emptyList()))
        val sut = createRepo()

        sut.scan(includeBluetooth = false)

        verify(trezorRepo).scan(includeBluetooth = false)
    }

    @Test
    fun `connect delegates to trezorRepo`() = test {
        val features = mock<TrezorFeatures>()
        whenever(trezorRepo.connect("dev1")).thenReturn(Result.success(features))
        val sut = createRepo()

        sut.connect("dev1")

        verify(trezorRepo).resetWalletSelection()
        verify(trezorRepo).connect("dev1")
    }

    @Test
    fun `setDeviceLabel persists the trimmed custom label on the matching device`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.setDeviceLabel("dev1", "  My Cold Wallet  ")

        assertTrue(result.isSuccess)
        verify(hwWalletStore).saveKnownDevices(listOf(device.copy(customLabel = "My Cold Wallet")))
    }

    @Test
    fun `setDeviceLabel caps the persisted custom label`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.setDeviceLabel("dev1", "a".repeat(51))

        assertTrue(result.isSuccess)
        verify(hwWalletStore).saveKnownDevices(listOf(device.copy(customLabel = "a".repeat(50))))
    }

    @Test
    fun `setDeviceLabel clears the custom label when blank`() = test {
        val labelled = device.copy(customLabel = "Old")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(labelled))
        val sut = createRepo()

        sut.setDeviceLabel("dev1", "   ")

        verify(hwWalletStore).saveKnownDevices(listOf(labelled.copy(customLabel = null)))
    }

    @Test
    fun `setDeviceLabel applies to every entry sharing the wallet identity`() = test {
        val sharedXpubs = mapOf("nativeSegwit" to "zpubShared")
        val ble = device.copy(id = "ble1", xpubs = sharedXpubs)
        val usb = device.copy(id = "usb1", transportType = TransportType.USB, xpubs = sharedXpubs)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(ble, usb))
        val sut = createRepo()

        sut.setDeviceLabel("usb1", "Shared")

        verify(hwWalletStore).saveKnownDevices(
            listOf(ble.copy(customLabel = "Shared"), usb.copy(customLabel = "Shared")),
        )
    }

    @Test
    fun `wallet name prefers the custom label over the device label`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(customLabel = "My Cold Wallet")))
        val sut = createRepo()

        assertEquals("My Cold Wallet", sut.wallets.value.single().name)
    }

    private suspend fun wheneverStartWatcher() = whenever(
        trezorRepo.startWatcher(
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
        )
    )
}
