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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.TrezorData
import to.bitkit.data.TrezorStore
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class HwWalletRepoTest : BaseUnitTest() {

    private val trezorRepo = mock<TrezorRepo>()
    private val trezorStore = mock<TrezorStore>()

    private lateinit var storeData: MutableStateFlow<TrezorData>
    private lateinit var trezorState: MutableStateFlow<TrezorState>
    private lateinit var watcherEvents: MutableSharedFlow<Pair<String, WatcherEvent>>

    private val device = KnownDevice(
        id = "dev1",
        name = null,
        path = "ble:AA:BB",
        transportType = KnownDeviceTransportType.BLUETOOTH,
        label = "Trezor",
        model = "Safe 5",
        lastConnectedAt = 0L,
    )

    @Before
    fun setUp() {
        storeData = MutableStateFlow(TrezorData(knownDevices = listOf(device)))
        trezorState = MutableStateFlow(TrezorState())
        watcherEvents = MutableSharedFlow(extraBufferCapacity = 8)
        whenever(trezorStore.data).thenReturn(storeData)
        whenever(trezorRepo.state).thenReturn(trezorState)
        whenever(trezorRepo.watcherEvents).thenReturn(watcherEvents)
    }

    @Test
    fun `lists a known device with zero balance before any watcher event`() = test {
        val sut = HwWalletRepo(trezorRepo, trezorStore, testDispatcher)

        val wallet = sut.hardwareWallets.value.single()
        assertEquals("dev1", wallet.id)
        assertEquals("Trezor", wallet.name)
        assertEquals(0uL, wallet.balanceSats)
        assertEquals(0uL, sut.totalHardwareSats.value)
    }

    @Test
    fun `transactions changed event sets device balance and maps activity`() = test {
        val sut = HwWalletRepo(trezorRepo, trezorStore, testDispatcher)

        watcherEvents.emit(
            "dev1|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 10_562_411uL),
                transactions = listOf(receivedTransaction(amount = 10_562_411uL)),
                txCount = 1u,
                blockHeight = 850_000u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val wallet = sut.hardwareWallets.value.single()
        assertEquals(10_562_411uL, wallet.balanceSats)
        assertEquals(10_562_411uL, sut.totalHardwareSats.value)
        assertEquals(1, wallet.activities.size)
        assertEquals(1, sut.hardwareActivities.value.size)
        assertEquals(Activity.Onchain::class, wallet.activities.single()::class)
    }

    @Test
    fun `balances from multiple address-type watchers are summed per device`() = test {
        val sut = HwWalletRepo(trezorRepo, trezorStore, testDispatcher)

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
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        assertEquals(150uL, sut.hardwareWallets.value.single().balanceSats)
        assertEquals(150uL, sut.totalHardwareSats.value)
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
