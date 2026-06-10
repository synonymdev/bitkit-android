package to.bitkit.repositories

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WalletBalance
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.HwWalletData
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.models.HwTransportType
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.toCoreNetwork
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class HwWalletRepoTest : BaseUnitTest() {

    private val trezorRepo = mock<TrezorRepo>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val settingsStore = mock<SettingsStore>()
    private val clock = Clock.System

    private lateinit var storeData: MutableStateFlow<HwWalletData>
    private lateinit var settingsData: MutableStateFlow<SettingsData>
    private lateinit var trezorState: MutableStateFlow<TrezorState>
    private lateinit var watcherEvents: MutableSharedFlow<Pair<String, WatcherEvent>>

    private val device = KnownDevice(
        id = "dev1",
        name = null,
        path = "ble:AA:BB",
        transportType = HwTransportType.BLUETOOTH,
        label = "Trezor",
        model = "Safe 5",
        lastConnectedAt = 0L,
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
    }

    private fun createRepo() = HwWalletRepo(trezorRepo, hwWalletStore, settingsStore, clock, testDispatcher)

    @Test
    fun `lists a known device with zero balance before any watcher event`() = test {
        val sut = createRepo()

        val wallet = sut.wallets.value.single()
        assertEquals("dev1", wallet.id)
        assertEquals("Trezor", wallet.name)
        assertEquals(0uL, wallet.balanceSats)
        assertEquals(0uL, sut.totalSats.value)
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
        whenever(trezorRepo.startWatcher(any(), any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(eq("dev1|nativeSegwit"), any(), any(), any(), anyOrNull())
        verify(trezorRepo).startWatcher(eq("dev1|taproot"), any(), any(), any(), anyOrNull())
        verify(trezorRepo, never()).startWatcher(eq("dev1|legacy"), any(), any(), any(), anyOrNull())
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
        val usbEntry = bleEntry.copy(id = "usb1", transportType = HwTransportType.USB, lastConnectedAt = 2L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        whenever(trezorRepo.startWatcher(any(), any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))

        val sut = createRepo()

        verify(trezorRepo).startWatcher(eq("ble1|nativeSegwit"), any(), any(), any(), anyOrNull())
        verify(trezorRepo, never()).startWatcher(eq("usb1|nativeSegwit"), any(), any(), any(), anyOrNull())

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
        assertEquals(HwTransportType.USB, wallet.transportType)
    }

    @Test
    fun `connected entry wins identity for a wallet paired over both transports`() = test {
        val bleEntry = device.copy(id = "ble1", lastConnectedAt = 2L, xpubs = mapOf("nativeSegwit" to "zpubNS"))
        val usbEntry = bleEntry.copy(id = "usb1", transportType = HwTransportType.USB, lastConnectedAt = 1L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "usb1", features = mock()),
        )
        whenever(trezorRepo.startWatcher(any(), any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))

        val sut = createRepo()

        val wallet = sut.wallets.value.single()
        assertEquals("usb1", wallet.id)
        assertEquals(HwTransportType.USB, wallet.transportType)
        assertEquals(true, wallet.isConnected)
    }

    @Test
    fun `starts watchers on the network configured in Env`() = test {
        storeData.value = HwWalletData(
            knownDevices = listOf(device.copy(xpubs = mapOf("nativeSegwit" to "zpubNS")))
        )
        whenever(trezorRepo.startWatcher(any(), any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(
            watcherId = any(),
            extendedKey = any(),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
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
}
