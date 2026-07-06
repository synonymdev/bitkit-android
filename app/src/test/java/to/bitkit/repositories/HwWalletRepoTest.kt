package to.bitkit.repositories

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.WalletBalance
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
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
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.ext.create
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
    private val activityRepo = mock<ActivityRepo>()
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
        whenever(trezorRepo.deriveWalletId(any(), any())).thenAnswer { invocation ->
            val xpubs = invocation.getArgument<Map<String, String>>(0)
            "derived-${xpubs.values.sorted().joinToString()}"
        }
        runBlocking {
            whenever(activityRepo.syncHardwareOnchainActivity(any())).thenReturn(Result.success(Unit))
        }
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(1_700_000_000))
    }

    private fun createRepo() = HwWalletRepo(
        trezorRepo = trezorRepo,
        activityRepo = activityRepo,
        hwWalletStore = hwWalletStore,
        settingsStore = settingsStore,
        clock = clock,
        ioDispatcher = testDispatcher,
    )

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
                activities = listOf(watcherActivity(amount = 10_562_411uL)),
                transactionDetails = emptyList(),
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
        verify(activityRepo).syncHardwareOnchainActivity((wallet.activities.single() as Activity.Onchain).v1)
    }

    @Test
    fun `balances from multiple address-type watchers are summed per device`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        val wallet = sut.wallets.value.single()
        assertEquals(150uL, wallet.balanceSats)
        assertEquals(100uL, wallet.fundingBalanceSats)
        assertEquals(150uL, sut.totalSats.value)
    }

    @Test
    fun `merges duplicate tx activities from multiple address-type watchers`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = listOf(watcherActivity(amount = 50uL, txid = "shared")),
                transactionDetails = emptyList(),
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
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev2|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = listOf(watcherActivity(amount = 50uL, txid = "shared")),
                transactionDetails = emptyList(),
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
    fun `merges duplicate sent tx activities without subtracting fee twice`() = test {
        val sut = createRepo()
        val fee = 1_000uL

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                activities = listOf(
                    watcherActivity(amount = 40_000uL, txid = "sent-shared", txType = PaymentType.SENT, fee = fee),
                ),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                activities = listOf(
                    watcherActivity(amount = 20_000uL, txid = "sent-shared", txType = PaymentType.SENT, fee = fee),
                ),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        val activity = sut.wallets.value.single().activities.single() as Activity.Onchain
        assertEquals(PaymentType.SENT, activity.v1.txType)
        assertEquals(60_000uL, activity.v1.value)
        assertEquals(fee, activity.v1.fee)
    }

    @Test
    fun `preserves activity timestamp across watcher refreshes`() = test {
        val sut = createRepo()
        val pendingActivity = watcherActivity(
            amount = 100uL,
            txid = "pending",
            blockHeight = null,
            timestamp = 1_800_000_000uL,
            confirmations = 0u,
        )

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(pendingActivity),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        val firstTimestamp = (sut.wallets.value.single().activities.single() as Activity.Onchain).v1.timestamp

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(pendingActivity),
                transactionDetails = emptyList(),
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
    fun `starts native segwit watcher regardless of monitored address types`() = test {
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

        verify(trezorRepo).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any(), any())
        verify(trezorRepo, never()).startWatcher(eq("dev1|taproot"), any(), any(), any(), anyOrNull(), any(), any())
        verify(trezorRepo, never()).startWatcher(eq("dev1|legacy"), any(), any(), any(), anyOrNull(), any(), any())
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
            walletId = any(),
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
            walletId = any(),
        )
    }

    @Test
    fun `restarts active watchers when wallet id changes`() = test {
        val derivedWalletId = "derived-zpubNS"
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = "legacy-wallet-id")))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever(trezorRepo.stopWatcher(any())).thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        val order = inOrder(trezorRepo)
        order.verify(trezorRepo).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
            walletId = eq("legacy-wallet-id"),
        )

        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = derivedWalletId)))
        runCurrent()

        order.verify(trezorRepo).stopWatcher("dev1|nativeSegwit")
        order.verify(trezorRepo).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
            walletId = eq(derivedWalletId),
        )
    }

    @Test
    fun `starts watchers with derived wallet id when store value is blank`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = "")))
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        verify(trezorRepo).startWatcher(
            watcherId = eq("dev1|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
            walletId = eq("derived-zpubNS"),
        )
    }

    @Test
    fun `retries watcher start after failure`() = test {
        wheneverStartWatcher().thenReturn(Result.failure(AppError("start failed")), Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any(), any())

        advanceTimeBy(30.seconds)
        runCurrent()

        verify(trezorRepo, times(2)).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull(), any(), any())
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
                activities = listOf(watcherActivity(amount = 100uL)),
                transactionDetails = emptyList(),
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
                activities = listOf(
                    watcherActivity(amount = 100uL),
                    watcherActivity(amount = 50uL, txid = "t2"),
                ),
                transactionDetails = emptyList(),
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
                activities = listOf(
                    watcherActivity(amount = 100uL),
                    watcherActivity(amount = 50uL, txid = "t2"),
                ),
                transactionDetails = emptyList(),
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
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = listOf(watcherActivity(amount = 50uL, txid = "shared")),
                transactionDetails = emptyList(),
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
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 40uL),
                activities = listOf(
                    watcherActivity(amount = 60uL, txid = "t3", txType = PaymentType.SENT),
                ),
                transactionDetails = emptyList(),
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

        verify(trezorRepo).startWatcher(eq("ble1|nativeSegwit"), any(), any(), any(), anyOrNull(), any(), any())
        verify(trezorRepo, never()).startWatcher(eq("usb1|nativeSegwit"), any(), any(), any(), anyOrNull(), any(), any())

        watcherEvents.emit(
            "ble1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 421_900uL),
                activities = listOf(watcherActivity(amount = 421_900uL)),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val wallet = sut.wallets.value.single()
        assertEquals(421_900uL, wallet.balanceSats)
        assertEquals(421_900uL, wallet.fundingBalanceSats)
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
            knownDevices = listOf(
                device.copy(xpubs = mapOf("nativeSegwit" to "zpubNS", "taproot" to "zpubTR")),
            )
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.failure(AppError("stop failed")))
        val sut = createRepo()
        runCurrent()

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        runCurrent()

        // Dropping the native-segwit xpub stops the watcher; a failed stop keeps ghost balance visible.
        storeData.value = HwWalletData(
            knownDevices = listOf(device.copy(xpubs = mapOf("taproot" to "zpubTR"))),
        )
        runCurrent()
        assertEquals(100uL, sut.totalSats.value)

        // Stop succeeds on a later sync: the watcher data is finally dropped.
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        storeData.value = HwWalletData(
            knownDevices = listOf(
                device.copy(xpubs = mapOf("taproot" to "zpubTR"), lastConnectedAt = 2L),
            ),
        )
        runCurrent()
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
                activities = emptyList(), transactionDetails = emptyList(),
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
            walletId = any(),
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
    fun `forwards warm up known device to the trezor repo`() = test {
        val sut = createRepo()

        sut.warmUpKnownDevice("dev1")

        verify(trezorRepo).warmUpKnownDevice("dev1")
    }

    @Test
    fun `composeFundingTransaction returns composed fee data`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val composeResult = ComposeResult.Success(
            psbt = "psbt",
            fee = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
        )
        whenever(
            trezorRepo.composeTransaction(
                extendedKey = any(),
                outputs = any(),
                feeRates = any(),
                network = any(),
                accountType = anyOrNull(),
                coinSelection = any(),
            )
        ).thenReturn(Result.success(listOf(composeResult)))
        val sut = createRepo()

        val result = sut.composeFundingTransaction(
            deviceId = "dev1",
            address = "bc1qtest",
            sats = 25_000uL,
            satsPerVByte = 2uL,
        )

        assertEquals(true, result.isSuccess)
        assertEquals("psbt", result.getOrThrow().psbt)
        assertEquals(1_250uL, result.getOrThrow().miningFeeSats)
        assertEquals(26_250uL, result.getOrThrow().totalSpent)
        assertEquals(2uL, result.getOrThrow().satsPerVByte)
    }

    @Test
    fun `composeFundingTransaction does not sign when compose fails`() = test {
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
        val sut = createRepo()

        val result = sut.composeFundingTransaction(
            deviceId = "dev1",
            address = "bc1qtest",
            sats = 25_000uL,
            satsPerVByte = 2uL,
        )

        assertEquals(true, result.isFailure)
        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
        verify(trezorRepo, never()).broadcastRawTx(any())
        verify(trezorRepo, never()).disconnectStaleSession(any())
    }

    @Test
    fun `signAndBroadcastFunding returns txid and composed fee data`() = test {
        val signedTx = TrezorSignedTx(
            signatures = emptyList(),
            serializedTx = "rawtx",
            txid = "signed-txid",
        )
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 3.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.success(signedTx))
        whenever(trezorRepo.broadcastRawTx("rawtx")).thenReturn(Result.success("broadcast-txid"))
        val sut = createRepo()

        val result = sut.signAndBroadcastFunding("dev1", funding)

        assertEquals(true, result.isSuccess)
        assertEquals("broadcast-txid", result.getOrThrow().txId)
        assertEquals(1_250uL, result.getOrThrow().miningFeeSats)
        assertEquals(3uL, result.getOrThrow().feeRate)
        assertEquals(26_250uL, result.getOrThrow().totalSpent)
    }

    @Test
    fun `signAndBroadcastFunding disconnects stale session when sign fails`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.failure(AppError("sign failed")))
        whenever(trezorRepo.disconnectStaleSession("dev1")).thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.signAndBroadcastFunding("dev1", funding)

        assertEquals(true, result.isFailure)
        verify(trezorRepo).disconnectStaleSession("dev1")
        verify(trezorRepo, never()).broadcastRawTx(any())
    }

    @Test
    fun `signAndBroadcastFunding keeps session when user cancels on device`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))
        val sut = createRepo()

        val result = sut.signAndBroadcastFunding("dev1", funding)

        assertEquals(true, result.isFailure)
        verify(trezorRepo, never()).disconnectStaleSession(any())
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
            walletId = any(),
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

    private fun watcherActivity(
        amount: ULong,
        txid: String = "t1",
        txType: PaymentType = PaymentType.RECEIVED,
        blockHeight: UInt? = 850_000u,
        timestamp: ULong? = 1_700_000_000uL,
        confirmations: UInt = 3u,
        fee: ULong = 0uL,
    ) = Activity.Onchain(
        OnchainActivity.create(
            walletId = "wallet0",
            id = txid,
            txType = txType,
            txId = txid,
            value = amount,
            fee = fee,
            address = "",
            timestamp = timestamp ?: 0uL,
            confirmed = blockHeight != null && confirmations > 0u,
        )
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
            any(),
        )
    )
}
