package to.bitkit.repositories

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorSignedTx
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
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.HwWalletData
import to.bitkit.data.HwWalletStore
import to.bitkit.data.PendingNameUpdate
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.WalletScope
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.services.TrezorWalletMode
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class HwWalletRepoTest : BaseUnitTest() {

    private companion object {
        const val HARDWARE_WALLET_ID = "hardware-wallet"
        const val HIDDEN_WALLET_ID = "hidden-wallet"
    }

    private val trezorRepo = mock<TrezorRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val hwWalletStore = mock<HwWalletStore>()
    private val settingsStore = mock<SettingsStore>()

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
        walletId = HARDWARE_WALLET_ID,
    )

    /** A passphrase wallet of the same physical device: same transport id, own keys and identity. */
    private val hiddenWallet = device.copy(
        xpubs = mapOf("nativeSegwit" to "zpubHidden"),
        walletId = HIDDEN_WALLET_ID,
        passphraseProtected = true,
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
        whenever(trezorRepo.deriveWalletId(any())).thenAnswer { invocation ->
            val xpubs = invocation.getArgument<Map<String, String>>(0)
            "derived-${xpubs.values.sorted().joinToString()}"
        }
        whenever {
            activityRepo.persistHwSnapshot(
                any<String>(),
                any<List<Activity>>(),
                any<List<TransactionDetails>>(),
            )
        }.thenAnswer {
            it.getArgument<List<Activity>>(1)
        }
        whenever { activityRepo.getWalletIds() }.thenReturn(Result.success(emptySet()))
        whenever { activityRepo.deleteForWallet(any()) }.thenReturn(Result.success(Unit))
        whenever { activityRepo.getTagMetadataForWallet(any()) }.thenReturn(Result.success(emptyList()))
        whenever { preActivityMetadataRepo.upsertPreActivityMetadata(any()) }.thenReturn(Result.success(Unit))
        whenever { hwWalletStore.setPendingName(any(), anyOrNull()) }.thenReturn(Unit)
    }

    private fun passphraseCapableFeatures(): TrezorFeatures =
        mock { on { passphraseProtection }.thenReturn(true) }

    private fun createRepo() = HwWalletRepo(
        trezorRepo = trezorRepo,
        activityRepo = activityRepo,
        preActivityMetadataRepo = preActivityMetadataRepo,
        hwWalletStore = hwWalletStore,
        settingsStore = settingsStore,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `lists a known device with zero balance before any watcher event`() = test {
        val sut = createRepo()

        val wallet = sut.wallets.value.single()
        assertEquals(HARDWARE_WALLET_ID, wallet.id)
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
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
        verify(activityRepo).persistHwSnapshot(
            walletId = HARDWARE_WALLET_ID,
            activities = wallet.activities,
            transactionDetails = emptyList(),
        )
    }

    @Test
    fun `unchanged watcher snapshot reuses the persisted activity`() = test {
        val sourceActivity = watcherActivity(amount = 100uL)
        val persistedActivity = Activity.Onchain(
            sourceActivity.v1.copy(isTransfer = true),
        )
        val event = transactionsChanged(
            total = 100uL,
            activities = listOf(sourceActivity),
        )
        whenever {
            activityRepo.persistHwSnapshot(
                HARDWARE_WALLET_ID,
                event.activities,
                event.transactionDetails,
            )
        }.thenReturn(Result.success(listOf(persistedActivity)))
        val sut = createRepo()

        watcherEvents.emit("hardware-wallet|nativeSegwit" to event)
        watcherEvents.emit("hardware-wallet|nativeSegwit" to event)

        assertTrue((sut.activities.value.single() as Activity.Onchain).v1.isTransfer)
        verify(activityRepo).persistHwSnapshot(
            walletId = HARDWARE_WALLET_ID,
            activities = event.activities,
            transactionDetails = event.transactionDetails,
        )
    }

    @Test
    fun `unchanged watcher snapshot reevaluates a retained pending send`() = test {
        val retainedPendingSend = watcherActivity(
            amount = 100uL,
            txType = PaymentType.SENT,
            blockHeight = null,
            confirmations = 0u,
        )
        val event = transactionsChanged(total = 0uL)
        whenever {
            activityRepo.persistHwSnapshot(
                HARDWARE_WALLET_ID,
                event.activities,
                event.transactionDetails,
            )
        }.thenReturn(
            Result.success(listOf(retainedPendingSend)),
            Result.success(emptyList()),
        )
        val sut = createRepo()

        watcherEvents.emit("hardware-wallet|nativeSegwit" to event)
        watcherEvents.emit("hardware-wallet|nativeSegwit" to event)

        assertTrue(sut.activities.value.isEmpty())
        verify(activityRepo, times(2)).persistHwSnapshot(
            walletId = HARDWARE_WALLET_ID,
            activities = event.activities,
            transactionDetails = event.transactionDetails,
        )
    }

    @Test
    fun `pending timestamp changes reuse snapshot until confirmation`() = test {
        val pendingActivity = watcherActivity(
            amount = 100uL,
            blockHeight = null,
            timestamp = 1_700_000_000uL,
            confirmations = 0u,
        )
        val pending = transactionsChanged(
            total = 100uL,
            activities = listOf(pendingActivity),
        )
        val refreshedPending = pending.copy(
            activities = listOf(
                Activity.Onchain(pendingActivity.v1.copy(timestamp = 1_700_000_001uL))
            ),
        )
        val confirmed = refreshedPending.copy(
            activities = listOf(
                Activity.Onchain(pendingActivity.v1.copy(timestamp = 1_700_000_001uL, confirmed = true))
            ),
        )
        val sut = createRepo()

        watcherEvents.emit("hardware-wallet|nativeSegwit" to pending)
        watcherEvents.emit("hardware-wallet|nativeSegwit" to refreshedPending)
        watcherEvents.emit("hardware-wallet|nativeSegwit" to confirmed)

        assertTrue((sut.activities.value.single() as Activity.Onchain).v1.confirmed)
        verify(activityRepo, times(2)).persistHwSnapshot(
            eq(HARDWARE_WALLET_ID),
            any<List<Activity>>(),
            any<List<TransactionDetails>>(),
        )
    }

    @Test
    fun `events from inactive address-type watchers are ignored`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "hardware-wallet|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )

        val wallet = sut.wallets.value.single()
        assertEquals(100uL, wallet.balanceSats)
        assertEquals(100uL, wallet.fundingBalanceSats)
        assertEquals(100uL, sut.totalSats.value)
    }

    @Test
    fun `merges activities in descending timestamp order`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 200uL),
                activities = listOf(
                    watcherActivity(amount = 100uL, txid = "older", timestamp = 1_600_000_000uL),
                    watcherActivity(amount = 100uL, txid = "newer", timestamp = 1_800_000_000uL),
                ),
                transactionDetails = emptyList(),
                txCount = 2u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val activities = sut.wallets.value.single().activities
        assertEquals(2, activities.size)
        assertEquals("newer", (activities[0] as Activity.Onchain).v1.txId)
        assertEquals("older", (activities[1] as Activity.Onchain).v1.txId)
    }

    @Test
    fun `inactive address-type activity does not replace active watcher activity`() = test {
        val sut = createRepo()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "hardware-wallet|taproot" to WatcherEvent.TransactionsChanged(
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
        assertEquals(100uL, activity.v1.value)
        assertEquals(100uL, sut.wallets.value.single().balanceSats)
    }

    @Test
    fun `same tx id remains scoped across hardware wallets`() = test {
        val secondWalletId = "hardware-wallet-2"
        val secondDevice = device.copy(
            id = "dev2",
            path = "ble:CC:DD",
            lastConnectedAt = 1L,
            xpubs = mapOf("nativeSegwit" to "zpubNS2"),
            walletId = secondWalletId,
        )
        storeData.value = HwWalletData(knownDevices = listOf(device, secondDevice))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        val sut = createRepo()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "hardware-wallet-2|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = listOf(
                    watcherActivity(amount = 50uL, txid = "shared", walletId = secondWalletId)
                ),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        val activities = sut.activities.value.filterIsInstance<Activity.Onchain>()
        assertEquals(2, sut.wallets.value.size)
        assertEquals(2, activities.size)
        assertEquals(setOf(HARDWARE_WALLET_ID, secondWalletId), activities.map { it.v1.walletId }.toSet())
        assertEquals(setOf(100uL, 50uL), activities.map { it.v1.value }.toSet())
    }

    @Test
    fun `inactive sent activity does not change active watcher value or fee`() = test {
        val sut = createRepo()
        val fee = 1_000uL

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
            "hardware-wallet|taproot" to WatcherEvent.TransactionsChanged(
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
        assertEquals(40_000uL, activity.v1.value)
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
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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

        verifyStartWatcher("hardware-wallet|nativeSegwit")
        verifyNoStartWatcher("hardware-wallet|taproot")
        verifyNoStartWatcher("hardware-wallet|legacy")
    }

    @Test
    fun `starts watchers on configured electrum server`() = test {
        val electrumServer = "ssl://custom.example:50002"
        settingsData.value = SettingsData(electrumServer = electrumServer)
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()

        verify(trezorRepo).startWatcher(
            watcherId = eq("hardware-wallet|nativeSegwit"),
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

        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(trezorRepo).startWatcher(
            watcherId = eq("hardware-wallet|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = eq(secondServer),
            walletId = any(),
        )
    }

    @Test
    fun `moves the watcher to the new id when the wallet id changes`() = test {
        val derivedWalletId = "derived-zpubNS"
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = "legacy-wallet-id")))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever(trezorRepo.stopWatcher(any())).thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        val order = inOrder(trezorRepo)
        order.verify(trezorRepo).startWatcher(
            watcherId = eq("legacy-wallet-id|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
            walletId = eq("legacy-wallet-id"),
        )

        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = derivedWalletId)))
        runCurrent()

        // The watcher is keyed by wallet, so a new identity starts its own watcher and the
        // watcher of the id that no longer exists is stopped afterwards.
        order.verify(trezorRepo).startWatcher(
            watcherId = eq("$derivedWalletId|nativeSegwit"),
            extendedKey = eq("zpubNS"),
            network = eq(Env.network.toCoreNetwork()),
            gapLimit = any(),
            accountType = anyOrNull(),
            electrumUrl = any(),
            walletId = eq(derivedWalletId),
        )
        order.verify(trezorRepo).stopWatcher("legacy-wallet-id|nativeSegwit")
    }

    @Test
    fun `starts watchers with derived wallet id when store value is blank`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device.copy(walletId = "")))
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        verify(trezorRepo).startWatcher(
            watcherId = eq("derived-zpubNS|nativeSegwit"),
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

        verifyStartWatcher("hardware-wallet|nativeSegwit")

        advanceTimeBy(30.seconds)
        runCurrent()

        verify(trezorRepo, times(2)).startWatcher(
            eq("hardware-wallet|nativeSegwit"),
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            any(),
        )
    }

    @Test
    fun `emits received tx only for new inbound transactions after the baseline sync`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }
        runCurrent()

        // Baseline: full history delivered on watcher start must not emit.
        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL)),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        runCurrent()
        assertEquals(0, received.size)

        // New inbound tx after the baseline emits once.
        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
        runCurrent()
        assertEquals(listOf("t1", "t2"), sut.activities.value.map { (it as Activity.Onchain).v1.txId })
        verify(activityRepo, times(2)).persistHwSnapshot(
            walletId = eq(HARDWARE_WALLET_ID),
            activities = any(),
            transactionDetails = any(),
        )
        assertEquals(
            listOf(HwWalletReceivedTx(txid = "t2", sats = 50uL, walletId = HARDWARE_WALLET_ID)),
            received,
        )

        // Re-delivering the same set (e.g. confirmation update) must not emit again.
        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
        runCurrent()
        assertEquals(1, received.size)

        job.cancel()
    }

    @Test
    fun `persistence failure keeps activity live and retries the received event`() = test {
        val baseline = listOf(watcherActivity(amount = 100uL))
        val updated = baseline + watcherActivity(amount = 50uL, txid = "retry-receive")
        whenever {
            activityRepo.persistHwSnapshot(HARDWARE_WALLET_ID, baseline, emptyList())
        }.thenReturn(Result.success(baseline))
        whenever {
            activityRepo.persistHwSnapshot(HARDWARE_WALLET_ID, updated, emptyList())
        }.thenReturn(
            Result.failure(AppError("persist failed")),
            Result.success(updated),
        )
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }
        runCurrent()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to transactionsChanged(100uL, baseline)
        )
        runCurrent()
        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to transactionsChanged(150uL, updated)
        )
        runCurrent()

        assertEquals(
            listOf("t1", "retry-receive"),
            sut.activities.value.filterIsInstance<Activity.Onchain>().map { it.v1.txId },
        )
        assertTrue(received.isEmpty())

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to transactionsChanged(150uL, updated)
        )
        runCurrent()

        assertEquals(
            listOf(
                HwWalletReceivedTx(
                    txid = "retry-receive",
                    sats = 50uL,
                    walletId = HARDWARE_WALLET_ID,
                )
            ),
            received,
        )
        job.cancel()
    }

    @Test
    fun `emits received tx once when multiple watchers report the same new tx`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }
        runCurrent()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        runCurrent()
        watcherEvents.emit(
            "hardware-wallet|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 0uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.TAPROOT,
            )
        )
        runCurrent()

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = listOf(watcherActivity(amount = 100uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        runCurrent()
        watcherEvents.emit(
            "hardware-wallet|taproot" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 50uL),
                activities = listOf(watcherActivity(amount = 50uL, txid = "shared")),
                transactionDetails = emptyList(),
                txCount = 1u,
                blockHeight = 2u,
                accountType = AccountType.TAPROOT,
            )
        )
        runCurrent()

        assertEquals(
            listOf(HwWalletReceivedTx(txid = "shared", sats = 100uL, walletId = HARDWARE_WALLET_ID)),
            received,
        )
        job.cancel()
    }

    @Test
    fun `does not emit received tx for new outbound transactions`() = test {
        val sut = createRepo()
        val received = mutableListOf<HwWalletReceivedTx>()
        val job = launch { sut.receivedTxs.collect { received += it } }

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )
        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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

        // Both transport entries share one identity, so they resolve to a single wallet watcher.
        verify(trezorRepo).startWatcher(
            eq("hardware-wallet|nativeSegwit"),
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            any(),
        )
        verify(trezorRepo, times(1)).startWatcher(any(), any(), any(), any(), anyOrNull(), any(), any())

        watcherEvents.emit(
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
        assertEquals(HARDWARE_WALLET_ID, wallet.id)
        assertEquals(setOf("ble1", "usb1"), wallet.deviceIds)
        assertEquals(TransportType.USB, wallet.transportType)
        assertEquals(true, wallet.isConnected)
    }

    @Test
    fun `lists a passphrase wallet as its own tile on the same device`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device, hiddenWallet))
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        val sut = createRepo()

        val wallets = sut.wallets.value
        assertEquals(listOf(HARDWARE_WALLET_ID, HIDDEN_WALLET_ID), wallets.map { it.id })
        assertEquals(listOf(false, true), wallets.map { it.passphraseProtected })
        assertEquals(listOf(setOf("dev1"), setOf("dev1")), wallets.map { it.deviceIds })
        verifyStartWatcher("$HARDWARE_WALLET_ID|nativeSegwit")
        verifyStartWatcher("$HIDDEN_WALLET_ID|nativeSegwit")
    }

    @Test
    fun `counts the balance of each identity on the device separately`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device, hiddenWallet))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        val sut = createRepo()

        watcherEvents.emit(
            "$HARDWARE_WALLET_ID|nativeSegwit" to transactionsChanged(total = 100uL),
        )
        watcherEvents.emit(
            "$HIDDEN_WALLET_ID|nativeSegwit" to transactionsChanged(total = 40uL),
        )

        assertEquals(listOf(100uL, 40uL), sut.wallets.value.map { it.balanceSats })
        assertEquals(140uL, sut.totalSats.value)
    }

    @Test
    fun `marks only the identity holding the session as connected`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        val sut = createRepo()

        assertEquals(listOf(false, true), sut.wallets.value.map { it.isConnected })
    }

    @Test
    fun `removeDevice reports failure when no entry tracks the wallet`() = test {
        // Nothing to forget must not read as a successful removal: the post-condition below holds
        // trivially on an empty set, so the caller would show the wallet as gone while it stays.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.removeDevice("unknown-wallet")

        assertTrue(result.isFailure)
        verify(trezorRepo, never()).forgetDevice(any(), anyOrNull(), anyOrNull())
        verify(activityRepo, never()).deleteForWallet("unknown-wallet")
    }

    @Test
    fun `removeDevice forgets only the requested identity of the device`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device, hiddenWallet))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet), listOf(device))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice(HIDDEN_WALLET_ID)

        assertTrue(result.isSuccess)
        verify(trezorRepo).forgetDevice(eq("dev1"), eq("zpubHidden"), anyOrNull())
        verify(trezorRepo).stopWatcher("$HIDDEN_WALLET_ID|nativeSegwit")
        verify(trezorRepo, never()).stopWatcher("$HARDWARE_WALLET_ID|nativeSegwit")
        verify(activityRepo).deleteForWallet(HIDDEN_WALLET_ID)
        verify(activityRepo, never()).deleteForWallet(HARDWARE_WALLET_ID)
    }

    @Test
    fun `connectWithPassphrase opens the hidden wallet and returns its identity`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val features = passphraseCapableFeatures()
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "secret") }
            .thenReturn(Result.success(mock()))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = features, walletId = HIDDEN_WALLET_ID),
        )
        val sut = createRepo()

        val result = sut.connectWithPassphrase(deviceId = "dev1", passphrase = "secret")

        assertEquals(HIDDEN_WALLET_ID, result.getOrThrow())
        verify(trezorRepo).setWalletMode(TrezorWalletMode.PASSPHRASE_HOST, "secret")
    }

    @Test
    fun `connectWithPassphrase reports a device that cannot open hidden wallets`() = test {
        // With passphrase protection off the device ignores the passphrase and reopens the standard
        // wallet, so the user would be told they already watch it instead of what is actually wrong.
        val features = mock<TrezorFeatures> { on { passphraseProtection }.thenReturn(false) }
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = features, walletId = HARDWARE_WALLET_ID),
        )
        val sut = createRepo()

        val result = sut.connectWithPassphrase(deviceId = "dev1", passphrase = "secret")

        assertTrue(result.exceptionOrNull() is HwPassphraseDisabledError)
        verify(trezorRepo, never()).setWalletMode(any(), any())
    }

    @Test
    fun `reconnectWithPassphrase reports an unreadable reopen instead of a wrong passphrase`() = test {
        // The session opened but its accounts could not be read, so nothing says the passphrase was
        // wrong; telling the user it opens a different wallet sends them to re-enter a right one.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "secret") }
            .thenAnswer {
                trezorState.value = TrezorState(
                    connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = null),
                )
                mock<TrezorFeatures>()
            }
        val sut = createRepo()

        val result = sut.reconnectWithPassphrase(HIDDEN_WALLET_ID, "secret")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is HwPassphraseMismatchError)
        verify(trezorRepo).disconnectStaleSession("dev1")
    }

    @Test
    fun `connectWithPassphrase reports a dropped session instead of disabled protection`() = test {
        // With no session there is nothing to ask about passphrase protection, and sending the user
        // to Trezor Suite to enable a setting they already have on helps nobody.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        trezorState.value = TrezorState(connected = null)
        val sut = createRepo()

        val result = sut.connectWithPassphrase(deviceId = "dev1", passphrase = "secret")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is HwPassphraseDisabledError)
        verify(trezorRepo, never()).setWalletMode(any(), any())
    }

    @Test
    fun `ensureConnected reports a reconnect failure when the standard wallet cannot be reopened`() = test {
        // Passphrase wallets return earlier, so this wallet has none to ask for; prompting leads to
        // a dead end where every entry is rejected as a mismatch.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
        )
        whenever { trezorRepo.ensureConnected("dev1") }.thenReturn(Result.success(mock()))
        whenever { trezorRepo.setWalletMode(TrezorWalletMode.STANDARD, "") }.thenReturn(Result.success(mock()))
        val sut = createRepo()

        val result = sut.ensureConnected(HARDWARE_WALLET_ID)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is HwPassphraseRequiredError)
    }

    @Test
    fun `connectWithPassphrase reports a passphrase wallet that is already watched`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        val features = passphraseCapableFeatures()
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "secret") }
            .thenReturn(Result.success(mock()))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = features, walletId = HIDDEN_WALLET_ID),
        )
        val sut = createRepo()

        val result = sut.connectWithPassphrase(deviceId = "dev1", passphrase = "secret")

        assertTrue(result.exceptionOrNull() is HwPassphraseAlreadyAddedError)
    }

    @Test
    fun `ensureConnected reopens the standard wallet when a hidden identity holds the session`() = test {
        // A session on the same transport is not the same wallet: signing the standard wallet's
        // inputs on a hidden-seed session would derive the wrong keys.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
        )
        whenever { trezorRepo.ensureConnected("dev1") }.thenReturn(Result.success(mock()))
        val reopenedFeatures = mock<TrezorFeatures>()
        val reopened = ConnectedTrezorDevice(id = "dev1", features = reopenedFeatures, walletId = HARDWARE_WALLET_ID)
        whenever { trezorRepo.setWalletMode(TrezorWalletMode.STANDARD, "") }.thenAnswer {
            trezorState.value = TrezorState(connected = reopened)
            reopenedFeatures
        }
        val sut = createRepo()

        val result = sut.ensureConnected(HARDWARE_WALLET_ID)

        assertTrue(result.isSuccess)
        verify(trezorRepo).setWalletMode(TrezorWalletMode.STANDARD, "")
    }

    @Test
    fun `ensureConnected demands the passphrase when the session resolves to no identity`() = test {
        // A session whose accounts could not be read reports no identity, and a hidden wallet is
        // only ever opened by proving one, so accepting it would sign on an unknown wallet.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = null),
        )
        whenever { trezorRepo.ensureConnected("dev1") }.thenReturn(Result.success(mock()))
        val sut = createRepo()

        val result = sut.ensureConnected(HIDDEN_WALLET_ID)

        assertTrue(result.exceptionOrNull() is HwPassphraseRequiredError)
    }

    @Test
    fun `ensureConnected accepts an unresolved session for the standard wallet`() = test {
        // Reopening the standard wallet proves nothing either, so demanding a passphrase here would
        // ask for one that does not exist.
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = null),
        )
        whenever { trezorRepo.ensureConnected("dev1") }.thenReturn(Result.success(mock()))
        val sut = createRepo()

        val result = sut.ensureConnected(HARDWARE_WALLET_ID)

        assertTrue(result.isSuccess, "err=${result.exceptionOrNull()}")
        verify(trezorRepo, never()).setWalletMode(any(), any())
    }

    @Test
    fun `signFunding refuses an unresolved session for a hidden wallet`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = null),
        )
        val sut = createRepo()

        val result = sut.signFunding(HIDDEN_WALLET_ID, funding)

        assertTrue(result.exceptionOrNull() is HwPassphraseRequiredError)
        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
    }

    @Test
    fun `ensureConnected demands the passphrase when another identity holds the session`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HARDWARE_WALLET_ID),
        )
        whenever { trezorRepo.ensureConnected("dev1") }.thenReturn(Result.success(mock()))
        val sut = createRepo()

        val result = sut.ensureConnected(HIDDEN_WALLET_ID)

        assertTrue(result.exceptionOrNull() is HwPassphraseRequiredError)
        verify(trezorRepo, never()).setWalletMode(any(), any())
    }

    @Test
    fun `signFunding refuses a session that belongs to another identity`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
        )
        val sut = createRepo()

        val result = sut.signFunding(HARDWARE_WALLET_ID, funding)

        assertTrue(result.exceptionOrNull() is HwPassphraseRequiredError)
        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
    }

    @Test
    fun `needsPassphrase only while the hidden wallet is not the live session`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        val sut = createRepo()

        assertTrue(sut.needsPassphrase(HIDDEN_WALLET_ID))
        assertFalse(sut.needsPassphrase(HARDWARE_WALLET_ID))

        trezorState.value = TrezorState(
            connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
        )

        assertFalse(sut.needsPassphrase(HIDDEN_WALLET_ID))
    }

    @Test
    fun `reconnectWithPassphrase opens the wallet without a live session`() = test {
        // No session is live here, which is the normal state when the prompt appears: going
        // through the switch helper instead would fail with "No connected Trezor".
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "secret") }
            .thenAnswer {
                trezorState.value = TrezorState(
                    connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = HIDDEN_WALLET_ID),
                )
                Result.success(mock<TrezorFeatures>())
            }
        val sut = createRepo()

        val result = sut.reconnectWithPassphrase(HIDDEN_WALLET_ID, "secret")

        assertTrue(result.isSuccess)
        verify(trezorRepo, never()).setWalletMode(any(), any())
        verify(trezorRepo, never()).disconnectStaleSession(any())
    }

    @Test
    fun `reconnectWithPassphrase drops the wallet a wrong passphrase opened and refuses to sign`() = test {
        val strayWallet = device.copy(
            xpubs = mapOf("nativeSegwit" to "zpubStray"),
            walletId = "stray-wallet",
            passphraseProtected = true,
        )
        var stored = listOf(device, hiddenWallet)
        whenever { hwWalletStore.loadKnownDevices() }.thenAnswer { stored }
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenAnswer {
            stored = stored.filterNot { it.walletId == "stray-wallet" }
            Result.success(Unit)
        }
        // A wrong passphrase derives another wallet, which reading its accounts already stored.
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "wrong") }
            .thenAnswer {
                stored = stored + strayWallet
                trezorState.value = TrezorState(
                    connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = "stray-wallet"),
                )
                Result.success(mock<TrezorFeatures>())
            }
        val sut = createRepo()

        val result = sut.reconnectWithPassphrase(HIDDEN_WALLET_ID, "wrong")

        assertTrue(result.exceptionOrNull() is HwPassphraseMismatchError)
        verify(trezorRepo).forgetDevice(eq("dev1"), eq("zpubStray"), anyOrNull())
        verify(trezorRepo).disconnectStaleSession("dev1")
    }

    @Test
    fun `reconnectWithPassphrase keeps the backup data of the wallet a wrong passphrase opened`() = test {
        // The wallet is a real one the user owns, and storing it consumed the name restored for it,
        // so dropping it here would erase a backed up name a typo was never meant to touch.
        val strayWallet = device.copy(
            xpubs = mapOf("nativeSegwit" to "zpubStray"),
            walletId = "stray-wallet",
            customLabel = "Hidden Stash",
            passphraseProtected = true,
        )
        val strayTagMetadata = listOf(preActivityMetadata().copy(walletId = "stray-wallet"))
        var stored = listOf(device, hiddenWallet)
        whenever { hwWalletStore.loadKnownDevices() }.thenAnswer { stored }
        whenever { activityRepo.getTagMetadataForWallet("stray-wallet") }
            .thenReturn(Result.success(strayTagMetadata))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenAnswer {
            stored = stored.filterNot { it.walletId == "stray-wallet" }
            Result.success(Unit)
        }
        whenever { trezorRepo.connectWithWalletMode("dev1", TrezorWalletMode.PASSPHRASE_HOST, "wrong") }
            .thenAnswer {
                stored = stored + strayWallet
                trezorState.value = TrezorState(
                    connected = ConnectedTrezorDevice(id = "dev1", features = mock(), walletId = "stray-wallet"),
                )
                Result.success(mock<TrezorFeatures>())
            }
        val sut = createRepo()

        val result = sut.reconnectWithPassphrase(HIDDEN_WALLET_ID, "wrong")

        assertTrue(result.exceptionOrNull() is HwPassphraseMismatchError)
        verify(trezorRepo).forgetDevice(any(), anyOrNull(), eq(PendingNameUpdate("stray-wallet", "Hidden Stash")))
        verify(preActivityMetadataRepo).upsertPreActivityMetadata(strayTagMetadata)
    }

    @Test
    fun `funding account resolves the requested identity on a shared device`() = test {
        storeData.value = HwWalletData(knownDevices = listOf(device, hiddenWallet))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device, hiddenWallet))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        watcherEvents.emit(
            "$HIDDEN_WALLET_ID|nativeSegwit" to transactionsChanged(total = 40uL),
        )

        val account = sut.getFundingAccount(HIDDEN_WALLET_ID).getOrThrow()
        assertEquals("zpubHidden", account.xpub)
        assertEquals(40uL, account.balanceSats)
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
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
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
    fun `store removal deletes the hardware wallet activity scope`() = test {
        // Only a wallet with activities left behind is cleaned up, so it must have some to clean.
        whenever { activityRepo.getWalletIds() }.thenReturn(Result.success(setOf(HARDWARE_WALLET_ID)))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        createRepo()
        runCurrent()

        storeData.value = HwWalletData(knownDevices = emptyList())
        runCurrent()

        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(activityRepo).deleteForWallet(HARDWARE_WALLET_ID)
    }

    @Test
    fun `startup deletes persisted activity scopes without a known device`() = test {
        whenever { activityRepo.getWalletIds() }.thenReturn(
            Result.success(setOf(WalletScope.default, HARDWARE_WALLET_ID, "orphan-wallet"))
        )
        wheneverStartWatcher().thenReturn(Result.success(Unit))

        createRepo()
        runCurrent()

        verify(activityRepo).deleteForWallet("orphan-wallet")
        verify(activityRepo, never()).deleteForWallet(WalletScope.default)
        verify(activityRepo, never()).deleteForWallet(HARDWARE_WALLET_ID)
    }

    @Test
    fun `removing a wallet deletes its activities exactly once`() = test {
        // A second delete would run Core's cascade again and take the tag metadata the removal
        // deliberately kept. The interleaving that caused this in the field — the cleanup deciding from
        // a scope set read before it takes the lock — needs real concurrency and is covered by manual
        // QA; this pins the simpler invariant that the cleanup adds no delete of its own.
        var persisted = setOf(HARDWARE_WALLET_ID)
        whenever { activityRepo.getWalletIds() }.thenAnswer { Result.success(persisted) }
        whenever { activityRepo.deleteForWallet(any()) }.thenAnswer {
            persisted = emptySet()
            Result.success(Unit)
        }
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)
        storeData.value = HwWalletData(knownDevices = emptyList())
        runCurrent()

        assertTrue(result.isSuccess)
        verify(activityRepo, times(1)).deleteForWallet(HARDWARE_WALLET_ID)
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
            "hardware-wallet|nativeSegwit" to WatcherEvent.TransactionsChanged(
                balance = walletBalance(total = 100uL),
                activities = emptyList(), transactionDetails = emptyList(),
                txCount = 0u,
                blockHeight = 1u,
                accountType = AccountType.NATIVE_SEGWIT,
            )
        )

        sut.resetState()

        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(trezorRepo).resetState()
        assertEquals(0uL, sut.totalSats.value)
    }

    @Test
    fun `removeDevice stops the device watchers and forgets it`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertEquals(true, result.isSuccess)
        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(activityRepo).deleteForWallet(HARDWARE_WALLET_ID)
        verify(trezorRepo).forgetDevice(eq("dev1"), eq("zpubNS"), anyOrNull())
    }

    @Test
    fun `removeDevice keeping backup data rewrites the tag metadata after deleting the activities`() = test {
        val named = device.copy(customLabel = "Cold Storage")
        val tagMetadata = listOf(preActivityMetadata())
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(named), emptyList())
        whenever { activityRepo.getTagMetadataForWallet(HARDWARE_WALLET_ID) }
            .thenReturn(Result.success(tagMetadata))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        assertTrue(result.isSuccess)
        // Core drops the wallet's tag metadata with its activities, so the rewrite must follow the delete.
        inOrder(activityRepo, preActivityMetadataRepo) {
            verify(activityRepo).deleteForWallet(HARDWARE_WALLET_ID)
            verify(preActivityMetadataRepo).upsertPreActivityMetadata(tagMetadata)
        }
    }

    @Test
    fun `removeDevice keeping backup data reports unreadable data without touching the wallet`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        whenever { activityRepo.getTagMetadataForWallet(HARDWARE_WALLET_ID) }
            .thenReturn(Result.failure(AppError("core unavailable")))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        // Raised before anything is deleted, so the wallet survives and the user keeps the choice.
        assertTrue(result.exceptionOrNull() is HwBackupDataUnreadableError)
        verify(activityRepo, never()).deleteForWallet(any())
        verify(trezorRepo, never()).forgetDevice(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `removeDevice keeping backup data stores the wallet name before forgetting the device`() = test {
        val named = device.copy(customLabel = "Cold Storage")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(named), emptyList())
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        assertTrue(result.isSuccess)
        // Carried by the write that forgets the entry, so the store never publishes a device list
        // still holding this wallet, which would restart the watcher of the wallet being removed.
        verify(trezorRepo).forgetDevice(
            eq("dev1"),
            eq("zpubNS"),
            eq(PendingNameUpdate(HARDWARE_WALLET_ID, "Cold Storage")),
        )
    }

    @Test
    fun `removeDevice keeping backup data stores no name for a wallet that was never renamed`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        assertTrue(result.isSuccess)
        verify(trezorRepo).forgetDevice(any(), anyOrNull(), eq(PendingNameUpdate(HARDWARE_WALLET_ID, null)))
    }

    @Test
    fun `removeDevice keeping backup data keeps the tags of a wallet that was never renamed`() = test {
        // The name and the tags are kept independently: a wallet is far more likely to carry tags than
        // a name the user bothered to set, and having no name must not cost it its tags.
        val tagMetadata = listOf(preActivityMetadata())
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        whenever { activityRepo.getTagMetadataForWallet(HARDWARE_WALLET_ID) }
            .thenReturn(Result.success(tagMetadata))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        assertTrue(result.isSuccess)
        assertNull(device.customLabel)
        verify(preActivityMetadataRepo).upsertPreActivityMetadata(tagMetadata)
        verify(trezorRepo).forgetDevice(any(), anyOrNull(), eq(PendingNameUpdate(HARDWARE_WALLET_ID, null)))
    }

    @Test
    fun `removeDevice without keeping backup data drops the name and the tag metadata`() = test {
        val named = device.copy(customLabel = "Cold Storage")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(named), emptyList())
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = false)

        assertTrue(result.isSuccess)
        verify(trezorRepo).forgetDevice(any(), anyOrNull(), eq(PendingNameUpdate(HARDWARE_WALLET_ID, null)))
        verify(activityRepo, never()).getTagMetadataForWallet(any())
        verify(preActivityMetadataRepo, never()).upsertPreActivityMetadata(any())
    }

    @Test
    fun `removeDevice keeps nothing by default`() = test {
        val named = device.copy(customLabel = "Cold Storage")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(named), emptyList())
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertTrue(result.isSuccess)
        verify(trezorRepo).forgetDevice(any(), anyOrNull(), eq(PendingNameUpdate(HARDWARE_WALLET_ID, null)))
        verify(preActivityMetadataRepo, never()).upsertPreActivityMetadata(any())
    }

    @Test
    fun `removeDevice still removes the wallet when the tag metadata cannot be rewritten`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        whenever { activityRepo.getTagMetadataForWallet(HARDWARE_WALLET_ID) }
            .thenReturn(Result.success(listOf(preActivityMetadata())))
        whenever { preActivityMetadataRepo.upsertPreActivityMetadata(any()) }
            .thenReturn(Result.failure(AppError("core unavailable")))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID, keepBackupData = true)

        // The activities are already gone and the watchers stopped, so there is nothing to roll back to:
        // the tags are lost rather than the removal reported as failed.
        assertTrue(result.isSuccess)
        verify(trezorRepo).forgetDevice(eq("dev1"), eq("zpubNS"), anyOrNull())
    }

    @Test
    fun `removeDevice fails when forget reports credential cleanup failure despite the device being gone`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), emptyList())
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.failure(AppError("clear failed")))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertEquals(true, result.isFailure)
        verify(trezorRepo).forgetDevice(eq("dev1"), eq("zpubNS"), anyOrNull())
    }

    @Test
    fun `removeDevice fails before forgetting when watcher stop fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.failure(AppError("stop failed")))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertEquals(true, result.isFailure)
        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(trezorRepo, never()).forgetDevice(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `removeDevice keeps the device when scoped activity cleanup fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        whenever { activityRepo.deleteForWallet(HARDWARE_WALLET_ID) }
            .thenReturn(Result.failure(AppError("delete failed")))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertTrue(result.isFailure)
        verify(activityRepo).deleteForWallet(HARDWARE_WALLET_ID)
        verify(trezorRepo, never()).forgetDevice(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `removeDevice forgets every entry of a device paired over both transports`() = test {
        val bleEntry = device.copy(id = "ble1", lastConnectedAt = 1L, xpubs = mapOf("nativeSegwit" to "zpubNS"))
        val usbEntry = bleEntry.copy(id = "usb1", transportType = TransportType.USB, lastConnectedAt = 2L)
        storeData.value = HwWalletData(knownDevices = listOf(bleEntry, usbEntry))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(bleEntry, usbEntry), emptyList())
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        sut.removeDevice(HARDWARE_WALLET_ID)

        verify(trezorRepo).stopWatcher("hardware-wallet|nativeSegwit")
        verify(trezorRepo).forgetDevice(eq("ble1"), eq("zpubNS"), anyOrNull())
        verify(trezorRepo).forgetDevice(eq("usb1"), eq("zpubNS"), anyOrNull())
    }

    @Test
    fun `removeDevice fails when the device is still present afterwards`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), listOf(device))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)

        assertEquals(true, result.isFailure)
    }

    @Test
    fun `removeDevice restarts watchers when removal verification fails`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device), listOf(device))
        wheneverStartWatcher().thenReturn(Result.success(Unit))
        whenever { trezorRepo.stopWatcher(any()) }.thenReturn(Result.success(Unit))
        whenever { trezorRepo.forgetDevice(any(), anyOrNull(), anyOrNull()) }.thenReturn(Result.success(Unit))
        val sut = createRepo()
        runCurrent()

        val result = sut.removeDevice(HARDWARE_WALLET_ID)
        runCurrent()

        assertEquals(true, result.isFailure)
        verify(trezorRepo, times(2)).startWatcher(
            watcherId = eq("hardware-wallet|nativeSegwit"),
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
    fun `warms up the transport entry of the requested wallet`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        sut.warmUpKnownDevice(HARDWARE_WALLET_ID)
        runCurrent()

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
            walletId = HARDWARE_WALLET_ID,
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
    fun `maxSpendableFunding subtracts the fee from a send-max compose`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        whenever(
            trezorRepo.composeTransactionOffline(
                extendedKey = any(),
                outputs = eq(listOf(ComposeOutput.SendMax(address = "bc1qtest"))),
                feeRates = eq(listOf(2.0f)),
                network = any(),
                accountType = anyOrNull(),
                coinSelection = any(),
            )
        ).thenReturn(
            Result.success(
                listOf(
                    ComposeResult.Success(
                        psbt = "psbt",
                        fee = 1_250uL,
                        feeRate = 2.0f,
                        totalSpent = 26_250uL,
                    )
                )
            )
        )
        val sut = createRepo()

        val result = sut.maxSpendableFunding(
            walletId = HARDWARE_WALLET_ID,
            address = "bc1qtest",
            satsPerVByte = 2uL,
        )

        assertEquals(25_000uL, result.getOrThrow())
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
            walletId = HARDWARE_WALLET_ID,
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
    fun `signFunding returns signed transaction and composed fee data`() = test {
        val signedTx = TrezorSignedTx(
            signatures = emptyList(),
            serializedTx = "rawtx",
            txid = null,
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
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.signFunding(HARDWARE_WALLET_ID, funding)

        assertEquals(true, result.isSuccess)
        assertEquals("rawtx", result.getOrThrow().serializedTx)
        assertEquals(1_250uL, result.getOrThrow().miningFeeSats)
        assertEquals(3uL, result.getOrThrow().feeRate)
        assertEquals(26_250uL, result.getOrThrow().totalSpent)
        verify(trezorRepo, never()).broadcastRawTx(any())
    }

    @Test
    fun `broadcastFunding returns txid and signed fee data`() = test {
        val signedTx = HwFundingSignedTx(
            serializedTx = "rawtx",
            miningFeeSats = 1_250uL,
            feeRate = 3uL,
            totalSpent = 26_250uL,
        )
        whenever(trezorRepo.broadcastRawTx("rawtx")).thenReturn(Result.success("broadcast-txid"))
        val sut = createRepo()

        val result = sut.broadcastFunding(signedTx)

        assertEquals(true, result.isSuccess)
        assertEquals("broadcast-txid", result.getOrThrow().txId)
        assertEquals(1_250uL, result.getOrThrow().miningFeeSats)
        assertEquals(3uL, result.getOrThrow().feeRate)
        assertEquals(26_250uL, result.getOrThrow().totalSpent)
        verify(trezorRepo, never()).signTxFromPsbt(any(), anyOrNull())
    }

    @Test
    fun `broadcastFunding returns core-derived txid when transaction is already known`() = test {
        val signedTx = HwFundingSignedTx(
            serializedTx = "rawtx",
            miningFeeSats = 1_250uL,
            feeRate = 3uL,
            totalSpent = 26_250uL,
        )
        whenever(trezorRepo.broadcastRawTx("rawtx"))
            .thenReturn(Result.success("core-derived-txid"))
        val sut = createRepo()

        val result = sut.broadcastFunding(signedTx)

        assertEquals("core-derived-txid", result.getOrThrow().txId)
    }

    @Test
    fun `signFunding disconnects stale session when THP channel fails`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.failure(TrezorException.ProtocolException("THP decryption error: aead::Error")))
        whenever(trezorRepo.disconnectStaleSession("dev1")).thenReturn(Result.success(Unit))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.signFunding(HARDWARE_WALLET_ID, funding)

        assertEquals(true, result.isFailure)
        verify(trezorRepo).disconnectStaleSession("dev1")
        verify(trezorRepo, never()).broadcastRawTx(any())
    }

    @Test
    fun `signFunding keeps session for non-session signing error`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.failure(AppError("invalid PSBT")))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.signFunding(HARDWARE_WALLET_ID, funding)

        assertEquals(true, result.isFailure)
        verify(trezorRepo, never()).disconnectStaleSession(any())
    }

    @Test
    fun `signFunding keeps session when user cancels on device`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_250uL,
            feeRate = 2.0f,
            totalSpent = 26_250uL,
            satsPerVByte = 2uL,
        )
        whenever(trezorRepo.signTxFromPsbt("psbt", Env.network.toTrezorCoinType()))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.signFunding(HARDWARE_WALLET_ID, funding)

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

    private fun transactionsChanged(
        total: ULong,
        activities: List<Activity> = emptyList(),
    ) = WatcherEvent.TransactionsChanged(
        balance = walletBalance(total),
        activities = activities,
        transactionDetails = emptyList(),
        txCount = activities.size.toUInt(),
        blockHeight = 1u,
        accountType = AccountType.NATIVE_SEGWIT,
    )

    @Suppress("LongParameterList")
    private fun watcherActivity(
        amount: ULong,
        txid: String = "t1",
        txType: PaymentType = PaymentType.RECEIVED,
        blockHeight: UInt? = 850_000u,
        timestamp: ULong? = 1_700_000_000uL,
        confirmations: UInt = 3u,
        fee: ULong = 0uL,
        walletId: String = HARDWARE_WALLET_ID,
    ) = Activity.Onchain(
        OnchainActivity.create(
            walletId = walletId,
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

        val result = sut.setDeviceLabel(HARDWARE_WALLET_ID, "  My Cold Wallet  ")

        assertTrue(result.isSuccess)
        verify(hwWalletStore).saveKnownDevices(listOf(device.copy(customLabel = "My Cold Wallet")))
    }

    @Test
    fun `setDeviceLabel caps the persisted custom label`() = test {
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(device))
        val sut = createRepo()

        val result = sut.setDeviceLabel(HARDWARE_WALLET_ID, "a".repeat(51))

        assertTrue(result.isSuccess)
        verify(hwWalletStore).saveKnownDevices(listOf(device.copy(customLabel = "a".repeat(50))))
    }

    @Test
    fun `setDeviceLabel clears the custom label when blank`() = test {
        val labelled = device.copy(customLabel = "Old")
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(labelled))
        val sut = createRepo()

        sut.setDeviceLabel(HARDWARE_WALLET_ID, "   ")

        verify(hwWalletStore).saveKnownDevices(listOf(labelled.copy(customLabel = null)))
    }

    @Test
    fun `setDeviceLabel applies to every entry sharing the wallet identity`() = test {
        val sharedXpubs = mapOf("nativeSegwit" to "zpubShared")
        val ble = device.copy(id = "ble1", xpubs = sharedXpubs)
        val usb = device.copy(id = "usb1", transportType = TransportType.USB, xpubs = sharedXpubs)
        whenever(hwWalletStore.loadKnownDevices()).thenReturn(listOf(ble, usb))
        val sut = createRepo()

        sut.setDeviceLabel(HARDWARE_WALLET_ID, "Shared")

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

    private fun preActivityMetadata() = PreActivityMetadata(
        walletId = HARDWARE_WALLET_ID,
        paymentId = "hw-txid",
        tags = listOf("cold"),
        paymentHash = null,
        txId = "hw-txid",
        address = null,
        isReceive = false,
        feeRate = 0uL,
        isTransfer = false,
        channelId = null,
        createdAt = 1uL,
    )

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

    private suspend fun verifyStartWatcher(watcherId: String) {
        verify(trezorRepo).startWatcher(eq(watcherId), any(), any(), any(), anyOrNull(), any(), any())
    }

    private suspend fun verifyNoStartWatcher(watcherId: String) {
        verify(trezorRepo, never()).startWatcher(eq(watcherId), any(), any(), any(), anyOrNull(), any(), any())
    }
}
