package to.bitkit.repositories

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WalletBalance
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
