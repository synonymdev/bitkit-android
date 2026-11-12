package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.GetAddressResponse
import com.synonym.bitkitcore.GetAddressesResponse
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.OnChainInvoice
import com.synonym.bitkitcore.Scanner
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.SyncType
import org.lightningdevkit.ldknode.TransactionDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.ProfileRemovalScope
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletResetRecovery
import to.bitkit.data.backup.VssClientCoordinator
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.models.BalanceState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.test.idleSparkRepo
import to.bitkit.ui.screens.wallets.receive.ReceiveTab
import to.bitkit.ui.screens.wallets.receive.getInvoiceForTab
import to.bitkit.usecases.DeriveBalanceStateUseCase
import to.bitkit.usecases.WipeIncomplete
import to.bitkit.usecases.WipeWalletUseCase
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {
    private lateinit var sut: WalletRepo

    private val keychain = mock<Keychain>()
    private val sparkRepo = idleSparkRepo()
    private val coreService = mock<CoreService>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()
    private val cacheStore = mock<CacheStore>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val deriveBalanceStateUseCase = mock<DeriveBalanceStateUseCase>()
    private val wipeWalletUseCase = mock<WipeWalletUseCase>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val onchainService = mock<OnchainService>()
    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val backupRepo = mock<BackupRepo>()
    private val vssClients = VssClientCoordinator()
    private val connectionRecovery = connectionRecoveryFixture(settingsStore, vssClients = vssClients)
    private val profileRemovalStore = ProfileRemovalStore.inMemoryForTesting()

    companion object Fixtures {
        const val ACTIVITY_TAG = "testTag"
        const val ADDRESS = "bc1qTest"
        const val ADDRESS_NEW = "newAddress"
        const val INVOICE = "testInvoice"
        const val INVOICE_REPLACEMENT = "replacementInvoice"
        const val SATS = 1000uL
        val error = RuntimeException("Test Error")
        val channels = listOf(
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u
                on { isChannelReady } doReturn true
                on { isUsable } doReturn true
            },
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 500_000u
                on { isChannelReady } doReturn true
                on { isUsable } doReturn true
            }
        ).toImmutableList()
        val readyButNotUsableChannels = listOf(
            mock<ChannelDetails> {
                on { inboundCapacityMsat } doReturn 1_000_000u
                on { isChannelReady } doReturn true
                on { isUsable } doReturn false
            }
        ).toImmutableList()
        private val channelReady = Event.ChannelReady(
            channelId = "testChannelId",
            userChannelId = "testUserChannelId",
            counterpartyNodeId = null,
            fundingTxo = null,
        )
    }

    @Before
    fun setUp() = runBlocking {
        whenever(coreService.decode(INVOICE)).thenReturn(Scanner.Lightning(lightningInvoice(INVOICE)))
        whenever(coreService.decode(INVOICE_REPLACEMENT))
            .thenReturn(Scanner.Lightning(lightningInvoice(INVOICE_REPLACEMENT)))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(bolt11 = "", onchainAddress = ADDRESS)))
        whenever { cacheStore.update(any()) }.thenReturn(Unit)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(backupRepo.restoreInProgress).thenReturn(MutableStateFlow(false))
        whenever(hwWalletRepo.wallets).thenReturn(MutableStateFlow(persistentListOf()))
        whenever(lightningRepo.nodeEventUpdates).thenReturn(MutableSharedFlow())
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(emptyList()))
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), any(), anyOrNull()))
            .thenReturn(Result.success(SATS))
        whenever(lightningRepo.canReceive()).thenReturn(false)
        whenever { privatePaykitAddressReservationRepo.nextReusableReceiveAddress() }
            .thenReturn(Result.success(ADDRESS_NEW))
        whenever { privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(any()) }
            .thenReturn(false)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(BalanceState()))

        whenever(coreService.onchain).thenReturn(onchainService)
        whenever(preActivityMetadataRepo.addPreActivityMetadataTags(any(), any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.removePreActivityMetadataTags(any(), any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.getPreActivityMetadata(any(), any())).thenReturn(Result.success(null))
        whenever(preActivityMetadataRepo.upsertPreActivityMetadata(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.addPreActivityMetadata(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.resetPreActivityMetadataTags(any())).thenReturn(Result.success(Unit))
        whenever(preActivityMetadataRepo.deletePreActivityMetadata(any())).thenReturn(Result.success(Unit))
        whenever { lightningRepo.addressInfosForType(any(), any(), any(), any()) }
            .thenReturn(
                Result.success(
                    listOf(
                        AddressDerivationInfo(
                            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                            index = 0,
                        ),
                    ),
                ),
            )
        sut = createSut()
    }

    private fun createSut(
        dispatcher: CoroutineDispatcher = testDispatcher,
        reservationRepo: PrivatePaykitAddressReservationRepo = privatePaykitAddressReservationRepo,
        spark: SparkRepo = sparkRepo,
        balances: DeriveBalanceStateUseCase = deriveBalanceStateUseCase,
    ) = WalletRepo(
        bgDispatcher = dispatcher,
        keychain = keychain,
        coreService = coreService,
        settingsStore = settingsStore,
        lightningRepo = lightningRepo,
        cacheStore = cacheStore,
        preActivityMetadataRepo = preActivityMetadataRepo,
        deriveBalanceStateUseCase = balances,
        wipeWalletUseCase = wipeWalletUseCase,
        privatePaykitAddressReservationRepo = reservationRepo,
        transferRepo = transferRepo,
        activityRepo = activityRepo,
        hwWalletRepo = hwWalletRepo,
        connectionRecovery = connectionRecovery,
        profileRemovalStore = profileRemovalStore,
        sparkRepo = spark,
        backupRepo = backupRepo,
        vssClients = vssClients,
    )

    @Test
    fun `incomplete wallet wipe rejects replacement seed before any write`() = test {
        val attempt = UUID.randomUUID().toString()
        profileRemovalStore.withAccess(attempt, ProfileRemovalScope.WALLET_WIPE) {
            profileRemovalStore.begin(attempt)
        }
        val result = sut.restoreWallet("replacement-test-mnemonic", null)
        assertTrue(result.isFailure)
        verify(keychain, never()).saveString(eq(Keychain.Key.BIP39_MNEMONIC.name), any())
        assertEquals(attempt, profileRemovalStore.pendingAttempt())
    }

    private fun lightningInvoice(bolt11: String, isExpired: Boolean = false) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = byteArrayOf(1, 2, 3),
        amountSatoshis = SATS,
        timestampSeconds = 0uL,
        expirySeconds = 86_400uL,
        isExpired = isExpired,
        description = "test",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )

    @Test
    fun `spark becoming ready during a restore reveals no receive address`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(backupRepo.isRestoreInProgress()).thenReturn(true)
        val sparkState = MutableStateFlow(SparkState())
        sut = createSut(spark = sparkRepoWith(sparkState))
        testScheduler.runCurrent()

        sparkState.update { it.copy(isReady = true) }
        testScheduler.runCurrent()

        verify(privatePaykitAddressReservationRepo, never()).nextReusableReceiveAddress()
    }

    @Test
    fun `spark becoming ready outside a restore refreshes the receive address`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(backupRepo.isRestoreInProgress()).thenReturn(false)
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        val sparkState = MutableStateFlow(SparkState())
        sut = createSut(spark = sparkRepoWith(sparkState))
        testScheduler.runCurrent()

        sparkState.update { it.copy(isReady = true) }
        testScheduler.runCurrent()

        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
    }

    @Test
    fun `a restore that completes after the node and spark started refreshes the receive invoice once`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        val restoring = MutableStateFlow(true)
        whenever(backupRepo.restoreInProgress).thenReturn(restoring)
        whenever(backupRepo.isRestoreInProgress()).thenAnswer { restoring.value }
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)))
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))
        val spark = sparkRepoWith(MutableStateFlow(SparkState(isReady = true)))
        whenever(spark.canReceive()).thenReturn(true)
        sut = createSut(spark = spark)
        testScheduler.runCurrent()
        verify(lightningRepo, never()).createInvoice(anyOrNull(), any(), any(), any())

        restoring.value = false
        testScheduler.runCurrent()

        verify(lightningRepo, times(1)).createInvoice(anyOrNull(), any(), any(), any())
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `a read spark balance that recovers to the shown one syncs balances`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        val sparkState = MutableStateFlow(SparkState(isReady = true, balanceSats = 0uL, keptBalanceSats = 9_000uL))
        sut = createSut(spark = sparkRepoWith(sparkState))
        testScheduler.runCurrent()
        clearInvocations(deriveBalanceStateUseCase)

        sparkState.update { it.copy(balanceSats = 9_000uL, keptBalanceSats = null) }
        testScheduler.runCurrent()

        verify(deriveBalanceStateUseCase).invoke()
    }

    @Test
    fun `Spark balance changes update spending while the node is stopped`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        val nodeState = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running))
        whenever(lightningRepo.lightningState).thenReturn(nodeState)
        whenever(lightningRepo.getBalancesAsync()).thenReturn(
            Result.success(
                BalanceDetails(
                    totalOnchainBalanceSats = 50_000uL,
                    spendableOnchainBalanceSats = 0uL,
                    totalAnchorChannelsReserveSats = 0uL,
                    totalLightningBalanceSats = 20_000uL,
                    lightningBalances = emptyList(),
                    pendingBalancesFromChannelClosures = emptyList(),
                ),
            ),
        )
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getChannelFundableBalance()).thenReturn(0uL)
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        val sparkState =
            MutableStateFlow(SparkState(isReady = true, balanceSats = 99_865uL, pendingDepositSats = 600uL))
        val spark = sparkRepoWith(sparkState)
        val balances = DeriveBalanceStateUseCase(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            transferRepo = transferRepo,
            settingsStore = settingsStore,
            hwWalletRepo = hwWalletRepo,
            sparkRepo = spark,
        )
        sut = createSut(spark = spark, balances = balances)
        testScheduler.runCurrent()
        assertEquals(119_865uL, sut.balanceState.value.totalLightningSats)

        nodeState.value = LightningState(nodeLifecycleState = NodeLifecycleState.Stopped)
        whenever(lightningRepo.getBalancesAsync()).thenReturn(
            Result.failure(NodeNotRunningError("getBalancesAsync", NodeLifecycleState.Stopped)),
        )
        for (remaining in listOf(98_659uL, 97_653uL, 0uL)) {
            sparkState.update { it.copy(balanceSats = remaining, pendingDepositSats = 100uL) }
            testScheduler.runCurrent()

            assertEquals(20_000uL + remaining, sut.balanceState.value.totalLightningSats)
            assertEquals(50_000uL, sut.balanceState.value.totalOnchainSats)
            assertEquals(100uL, sut.balanceState.value.balanceInTransferToSpending)
            assertEquals(SparkFees.sendableSats(remaining), sut.balanceState.value.maxSendLightningSats)
            verify(cacheStore).cacheBalance(sut.balanceState.value)
        }
        sparkState.update { it.copy(keptBalanceSats = 97_653uL) }
        testScheduler.runCurrent()
        assertEquals(117_653uL, sut.balanceState.value.totalLightningSats)
        assertEquals(0uL, sut.balanceState.value.maxSendLightningSats)
    }

    @Test
    fun `cached Spark contribution updates only after Spark is ready and never guesses a legacy split`() = test {
        val known = BalanceState(
            totalOnchainSats = 50_000uL,
            totalLightningSats = 120_000uL,
            balanceInTransferToSpending = 3_000uL,
            sparkBalanceSats = 100_000uL,
            sparkPendingDepositSats = 1_000uL,
        )
        val legacy = json.decodeFromString<BalanceState>("""{"totalLightningSats":120000}""")
        assertEquals(null, legacy.sparkBalanceSats)
        val restored = json.decodeFromString<BalanceState>(json.encodeToString(known))
        assertEquals(known, restored)
        val state = MutableStateFlow(SparkState())
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(
            Result.failure(NodeNotRunningError("getBalancesAsync", NodeLifecycleState.Stopped)),
        )
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(balance = restored)))
        sut = createSut(spark = sparkRepoWith(state))
        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(known, sut.balanceState.value)

        state.update { it.copy(isReady = true) }
        testScheduler.runCurrent()
        assertEquals(20_000uL, sut.balanceState.value.totalLightningSats)
        assertEquals(2_000uL, sut.balanceState.value.balanceInTransferToSpending)
        assertEquals(50_000uL, sut.balanceState.value.totalOnchainSats)

        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(balance = legacy)))
        val legacyState = MutableStateFlow(SparkState(isReady = true))
        sut = createSut(spark = sparkRepoWith(legacyState))
        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(legacy, sut.balanceState.value)
        legacyState.update { it.copy(balanceSats = 5_000uL) }
        testScheduler.runCurrent()
        assertEquals(legacy.copy(maxSendLightningSats = SparkFees.sendableSats(5_000uL)), sut.balanceState.value)
    }

    @Test
    fun `balance writes and cache hydration cannot overtake a pending update`() = test {
        var cached = AppCacheData()
        whenever(cacheStore.data).thenReturn(flow { emit(cached) })
        val first = BalanceState(totalLightningSats = 120_000uL)
        val latest = BalanceState(totalLightningSats = 20_000uL)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(first), Result.success(latest))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer {
            val balance = it.getArgument<BalanceState>(0)
            if (balance == first) {
                held.complete(Unit)
                release.await()
            }
            cached = cached.copy(balance = balance)
        }
        val older = launch { sut.syncBalances() }
        held.await()
        val newer = launch { sut.syncBalances() }
        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(null, cached.balance)
        release.complete(Unit)
        older.join()
        newer.join()
        testScheduler.runCurrent()

        assertEquals(latest, cached.balance)
        assertEquals(latest, sut.balanceState.value)
    }

    @Test
    fun `failed cache write keeps the latest visible balance during hydration`() = test {
        val latest = BalanceState(totalLightningSats = 20_000uL)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(latest))
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer { throw IOException("cache unavailable") }
        whenever(cacheStore.data).thenReturn(
            flowOf(AppCacheData(balance = BalanceState(totalLightningSats = 120_000uL))),
        )

        sut.syncBalances()
        sut.loadFromCache()
        testScheduler.runCurrent()

        assertEquals(latest, sut.balanceState.value)
    }

    @Test
    fun `wallet retirement drains balance writes and leaves Spark observation available for the next wallet`() = test {
        val state = MutableStateFlow(SparkState(isReady = true, balanceSats = 50_000uL))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cached: BalanceState? = null
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(
            Result.success(BalanceState(totalLightningSats = 50_000uL)),
        )
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer {
            held.complete(Unit)
            release.await()
            cached = it.getArgument(0)
        }
        sut = createSut(spark = sparkRepoWith(state))
        held.await()
        vssClients.withSuspendedClients(shutdown = {}) {
            sut.resetState()
            cached = null
        }
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(BalanceState(), sut.balanceState.value)
        assertEquals(null, cached)

        val next = BalanceState(
            totalLightningSats = 10_000uL,
            maxSendLightningSats = SparkFees.sendableSats(10_000uL),
        )
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(next))
        state.update { it.copy(balanceSats = 10_000uL) }
        testScheduler.runCurrent()
        assertEquals(next, sut.balanceState.value)
        assertEquals(next, cached)
    }

    @Test
    fun `balance publication preserves native limits and later channel refreshes`() = test {
        val node = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running))
        whenever(lightningRepo.lightningState).thenReturn(node)
        var cached = AppCacheData()
        whenever(cacheStore.data).thenReturn(flow { emit(cached) })
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer {
            cached = cached.copy(balance = it.getArgument(0))
        }
        val fresh = BalanceState(totalLightningSats = 20_000uL, maxSendLightningSats = 5_000uL)
        val published = mock<ChannelDetails> {
            on { isChannelReady } doReturn true
            on { isUsable } doReturn true
            on { nextOutboundHtlcLimitMsat } doReturn 2_000_000uL
        }
        whenever(deriveBalanceStateUseCase.invoke()).doSuspendableAnswer {
            node.update { it.copy(channels = listOf(published).toImmutableList()) }
            Result.success(fresh)
        }
        sut.syncBalances()
        assertEquals(5_000uL, sut.balanceState.value.maxSendLightningSats)
        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(5_000uL, sut.balanceState.value.maxSendLightningSats)

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer {
            held.complete(Unit)
            release.await()
        }
        val sync = launch { sut.syncBalances() }
        held.await()
        val recovered = mock<ChannelDetails> {
            on { isChannelReady } doReturn true
            on { isUsable } doReturn true
            on { nextOutboundHtlcLimitMsat } doReturn 7_000_000uL
        }
        node.update { it.copy(channels = listOf(recovered).toImmutableList()) }
        sut.refreshMaxSendLightning()
        assertEquals(7_000uL, sut.balanceState.value.maxSendLightningSats)
        release.complete(Unit)
        sync.join()
        assertEquals(7_000uL, sut.balanceState.value.maxSendLightningSats)
        assertEquals(20_000uL, sut.balanceState.value.totalLightningSats)
        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(7_000uL, sut.balanceState.value.maxSendLightningSats)
    }

    @Test
    fun `Spark readiness before cache hydration preserves stored wallet totals`() = test {
        var cached = AppCacheData(
            balance = BalanceState(
                totalOnchainSats = 50_000uL,
                totalLightningSats = 120_000uL,
                sparkBalanceSats = 100_000uL,
                sparkPendingDepositSats = 0uL,
            ),
        )
        val original = cached
        whenever(cacheStore.data).thenReturn(flow { emit(cached) })
        whenever(cacheStore.cacheBalance(any())).doSuspendableAnswer {
            cached = cached.copy(balance = it.getArgument(0))
        }
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(
            Result.failure(NodeNotRunningError("getBalancesAsync", NodeLifecycleState.Stopped)),
        )
        val state = MutableStateFlow(SparkState(isReady = true, balanceSats = 5_000uL))
        sut = createSut(spark = sparkRepoWith(state))
        testScheduler.runCurrent()
        assertEquals(original, cached)
        assertEquals(SparkFees.sendableSats(5_000uL), sut.balanceState.value.maxSendLightningSats)

        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(25_000uL, sut.balanceState.value.totalLightningSats)
        assertEquals(50_000uL, sut.balanceState.value.totalOnchainSats)
        assertEquals(sut.balanceState.value, cached.balance)
    }

    @Test
    fun `balance refresh can run inside a recovery startup session`() = test {
        val owner = connectionRecovery.captureOwner()
        connectionRecovery.withOperation(owner) {
            connectionRecovery.withStartup(owner) { sut.syncBalances() }
        }
        verify(cacheStore).cacheBalance(BalanceState())
    }

    private fun sparkRepoWith(state: MutableStateFlow<SparkState>): SparkRepo = mock {
        on { this.state } doReturn state
        on { nodeEvents } doReturn MutableSharedFlow()
    }

    @Test
    fun `concurrent cache loads keep receive pending until both complete`() = test {
        val firstSnapshot = CompletableDeferred<Unit>()
        val secondSnapshot = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var reads = 0
        whenever(cacheStore.data).thenReturn(
            flow {
                if (reads++ == 0) {
                    firstSnapshot.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondSnapshot.complete(Unit)
                    releaseSecond.await()
                }
                emit(AppCacheData(onchainAddress = ADDRESS))
            }
        )
        sut.loadFromCache()
        firstSnapshot.await()
        sut.loadFromCache()
        val pendingWithBothLoads = sut.walletState.value.isReceiveInvoiceUpdating
        releaseFirst.complete(Unit)
        secondSnapshot.await()
        val pendingAfterFirstLoad = sut.walletState.value.isReceiveInvoiceUpdating
        releaseSecond.complete(Unit)
        testScheduler.runCurrent()

        assertTrue(pendingWithBothLoads)
        assertTrue(pendingAfterFirstLoad)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `cancelled cache hydration releases its pending ownership`() = test {
        whenever(cacheStore.data).thenReturn(flow<AppCacheData> { throw CancellationException("cancel hydration") })

        sut.loadFromCache()
        testScheduler.runCurrent()

        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `cache hydration never exposes a quarantined public Receive address`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS, bip21 = "bitcoin:$ADDRESS")))
        whenever(privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(ADDRESS)).thenReturn(true)
        sut.loadFromCache()
        testScheduler.runCurrent()

        assertEquals("", sut.walletState.value.onchainAddress)
        assertEquals("", sut.walletState.value.bip21)
        val update = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore).update(update.capture())
        assertEquals("", update.firstValue(AppCacheData(onchainAddress = ADDRESS)).onchainAddress)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cached balance is immediate while starting node leaves Receive unvalidated`() = test {
        val cachedBalance = BalanceState(totalOnchainSats = 50_000uL)
        val address = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
        whenever(cacheStore.data).thenReturn(
            flowOf(AppCacheData(balance = cachedBalance, onchainAddress = address, bip21 = "bitcoin:$address")),
        )
        val reservations = mock<PrivatePaykitReservationStore>()
        whenever(reservations.data).thenReturn(
            flowOf(
                PrivatePaykitReservationData(reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1))),
            ),
        )
        val nodeState = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Starting))
        whenever(lightningRepo.lightningState).thenReturn(nodeState)
        val lookupStarted = CompletableDeferred<Unit>()
        val startupFailed = CompletableDeferred<Unit>()
        val failure = AppError("node startup failed")
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 1)).doSuspendableAnswer {
            lookupStarted.complete(Unit)
            startupFailed.await()
            Result.failure(failure)
        }
        val reservationRepo = PrivatePaykitAddressReservationRepo(
            testDispatcher,
            reservations,
            settingsStore,
            coreService,
            lightningRepo,
            cacheStore,
            keychain,
        )
        sut = createSut(reservationRepo = reservationRepo)

        sut.loadFromCache()
        lookupStarted.await()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(cachedBalance, sut.balanceState.value)
        assertEquals("", sut.walletState.value.onchainAddress)
        assertEquals("", sut.walletState.value.bip21)

        nodeState.value = LightningState(nodeLifecycleState = NodeLifecycleState.ErrorStarting(failure))
        startupFailed.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(cachedBalance, sut.balanceState.value)
        assertEquals("", sut.walletState.value.onchainAddress)
        assertEquals("", sut.walletState.value.bip21)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `receive refresh recovers saved ordinary address after node startup with public reservations`() = test {
        val address = "bcrt1q60d6qvf8lufpxpw6pvdnzsplxx4edspe04nyqz"
        val publicAddress = "bcrt1qqfq4x88n5y8ce2nd9qnu2t4ktp5rdvrr2sqv06"
        val cachedBalance = BalanceState(totalOnchainSats = 50_000uL)
        val cache = MutableStateFlow(
            AppCacheData(balance = cachedBalance, onchainAddress = address, bip21 = "bitcoin:$address"),
        )
        whenever(cacheStore.data).thenReturn(cache)
        whenever(cacheStore.update(any())).doSuspendableAnswer { invocation ->
            cache.value = invocation.getArgument<(AppCacheData) -> AppCacheData>(0)(cache.value)
        }
        whenever(cacheStore.setOnchainAddress(any())).doSuspendableAnswer { invocation ->
            cache.value = cache.value.copy(onchainAddress = invocation.getArgument(0))
        }
        val reservations = mock<PrivatePaykitReservationStore>()
        whenever(reservations.data).thenReturn(
            flowOf(
                PrivatePaykitReservationData(
                    reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1)),
                    publicAssignments = mapOf(
                        "profile" to PrivatePaykitStoredAssignmentData("nativeSegwit", 1, publicAddress),
                    ),
                ),
            ),
        )
        val nodeState = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Stopped))
        whenever(lightningRepo.lightningState).thenReturn(nodeState)
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 1)).doSuspendableAnswer {
            if (nodeState.value.nodeLifecycleState.isRunning()) {
                Result.success(AddressDerivationInfo(publicAddress, 1))
            } else {
                Result.failure(NodeNotRunningError("addressInfoForType", nodeState.value.nodeLifecycleState))
            }
        }
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH))
            .thenReturn(Result.success(AddressDerivationInfo(ADDRESS_NEW, 2)))
        whenever(coreService.isAddressUsed(address)).thenReturn(false)
        val reservationRepo = PrivatePaykitAddressReservationRepo(
            testDispatcher,
            reservations,
            settingsStore,
            coreService,
            lightningRepo,
            cacheStore,
            keychain,
        )
        sut = createSut(reservationRepo = reservationRepo)

        sut.loadFromCache()
        testScheduler.runCurrent()
        assertEquals(cachedBalance, sut.balanceState.value)
        assertEquals("", sut.walletState.value.onchainAddress)
        assertEquals("", sut.walletState.value.bip21)
        assertEquals(address, cache.value.onchainAddress)

        nodeState.value = LightningState(nodeLifecycleState = NodeLifecycleState.Running)
        sut.refreshBip21().getOrThrow()

        assertEquals(address, sut.walletState.value.onchainAddress)
        assertEquals("bitcoin:$address", sut.walletState.value.bip21)
        assertEquals(address, cache.value.onchainAddress)
        verify(lightningRepo, never()).newAddressInfoForType(any())
    }

    @Test
    fun `cached receive recovery validates availability and usage against current storage`() = test {
        val scenarios = listOf(
            "unavailable", "used", "lookup failure", "usage failure", "cleared", "replacement",
            "replacement during lookup", "promotion failure", "promotion cancellation",
        )
        for (scenario in scenarios) {
            val cache = MutableStateFlow(AppCacheData(onchainAddress = ADDRESS, bip21 = "bitcoin:$ADDRESS"))
            whenever(cacheStore.data).thenReturn(cache)
            whenever(cacheStore.update(any())).doSuspendableAnswer { invocation ->
                if (scenario == "promotion failure") throw AppError("cache unavailable")
                if (scenario == "promotion cancellation") throw CancellationException("cache update cancelled")
                cache.value = invocation.getArgument<(AppCacheData) -> AppCacheData>(0)(cache.value)
            }
            whenever(cacheStore.setOnchainAddress(any())).doSuspendableAnswer { invocation ->
                cache.value = cache.value.copy(onchainAddress = invocation.getArgument(0))
            }
            var checks = 0
            whenever(privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(any())).doSuspendableAnswer {
                if (checks++ == 0 || scenario == "lookup failure") throw AppError("address lookup unavailable")
                if (scenario == "replacement during lookup") {
                    cache.value = cache.value.copy(onchainAddress = "replacementAddress")
                }
                scenario == "unavailable"
            }
            whenever(coreService.isAddressUsed(any())).doSuspendableAnswer {
                if (scenario == "usage failure") throw AppError("address usage unavailable")
                scenario == "used"
            }
            var allocations = 0
            whenever(privatePaykitAddressReservationRepo.nextReusableReceiveAddress()).doSuspendableAnswer {
                allocations++
                Result.success(ADDRESS_NEW)
            }
            sut = createSut()
            sut.loadFromCache()
            testScheduler.runCurrent()
            assertEquals("", sut.walletState.value.onchainAddress, scenario)
            when (scenario) {
                "cleared" -> cache.value = AppCacheData()
                "replacement" -> cache.value = AppCacheData(onchainAddress = "replacementAddress")
            }

            val result = runCatching { sut.refreshReusableReceiveAddress().getOrThrow() }
            val failed = scenario.endsWith("failure") || scenario.endsWith("cancellation")
            val replacement = scenario.startsWith("replacement")
            val expectedAddress = when {
                failed -> ""
                replacement -> "replacementAddress"
                else -> ADDRESS_NEW
            }
            assertEquals(failed, result.isFailure, scenario)
            assertEquals(scenario.endsWith("cancellation"), result.exceptionOrNull() is CancellationException, scenario)
            assertEquals(expectedAddress, sut.walletState.value.onchainAddress, scenario)
            assertEquals(if (failed) "" else "bitcoin:$expectedAddress", sut.walletState.value.bip21, scenario)
            assertEquals(if (failed) ADDRESS else expectedAddress, cache.value.onchainAddress, scenario)
            assertEquals(if (failed || replacement) 0 else 1, allocations, scenario)
            assertFalse(sut.walletState.value.isReceiveInvoiceUpdating, scenario)
        }
    }

    @Test
    fun `settlement during cached receive recovery rotates before exposing the saved address`() = test {
        val cache = MutableStateFlow(AppCacheData(onchainAddress = ADDRESS, bip21 = "bitcoin:$ADDRESS"))
        whenever(cacheStore.data).thenReturn(cache)
        whenever(cacheStore.update(any())).doSuspendableAnswer { invocation ->
            cache.value = invocation.getArgument<(AppCacheData) -> AppCacheData>(0)(cache.value)
        }
        whenever(cacheStore.setOnchainAddress(any())).doSuspendableAnswer { invocation ->
            cache.value = cache.value.copy(onchainAddress = invocation.getArgument(0))
        }
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        val validationStarted = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        var checks = 0
        whenever(privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(ADDRESS)).doSuspendableAnswer {
            if (checks++ == 0) throw AppError("node not started")
            validationStarted.complete(Unit)
            finishValidation.await()
            false
        }
        whenever(coreService.isAddressUsed(ADDRESS)).thenReturn(false)
        sut = createSut()
        val states = mutableListOf<WalletState>()
        sut.walletState.onEach { states.add(it) }.launchIn(backgroundScope)
        sut.loadFromCache()
        testScheduler.runCurrent()
        val recovery = launch { sut.refreshReusableReceiveAddress().getOrThrow() }
        validationStarted.await()
        cache.value = cache.value.invalidateReceiveOnchainAddress()
        val settlement = launch {
            events.emit(
                NodeEventUpdate(
                    Event.OnchainTransactionReceived("txid", mock<TransactionDetails>()),
                    settledReceiveAddress = SettledReceiveAddress(ADDRESS),
                ),
            )
        }
        testScheduler.runCurrent()
        finishValidation.complete(Unit)
        recovery.join()
        settlement.join()
        testScheduler.runCurrent()

        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        assertEquals(ADDRESS_NEW, cache.value.onchainAddress)
        assertTrue(states.filter { it.onchainAddress == ADDRESS }.all { it.isReceiveInvoiceUpdating })
        assertFalse(
            states.any {
                getInvoiceForTab(
                    tab = ReceiveTab.SAVINGS,
                    bip21 = it.bip21,
                    bolt11 = it.bolt11,
                    isNodeRunning = true,
                    onchainAddress = it.onchainAddress,
                    isReceiveInvoiceUpdating = it.isReceiveInvoiceUpdating,
                ).contains(ADDRESS)
            },
            "A settled address must never be available in Savings",
        )
        assertTrue(states.none { it.onchainAddress == ADDRESS })
        verify(cacheStore, never()).setOnchainAddress(ADDRESS)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `event sync waits for a fresh activity snapshot before publication`() = test {
        whenever(transferRepo.syncTransferStates()).thenReturn(Result.success(Unit))
        val staleSnapshot = CompletableDeferred<Unit>()
        val freshSnapshot = CompletableDeferred<Unit>()
        whenever(activityRepo.syncActivities(any())).doSuspendableAnswer {
            if (it.getArgument<Boolean>(0)) freshSnapshot.await() else staleSnapshot.await()
            Result.success(Unit)
        }
        val existingSync = backgroundScope.launch { activityRepo.syncActivities() }
        testScheduler.runCurrent()
        var publications = 0
        sut.debounceSyncByEvent { publications++ }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(0, publications)

        staleSnapshot.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(existingSync.isCompleted)
        assertEquals(0, publications)

        freshSnapshot.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, publications)
        verify(activityRepo).syncActivities(requireFresh = true)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `event sync releases publication only after successful activity persistence`() = test {
        whenever(transferRepo.syncTransferStates()).thenReturn(Result.success(Unit))
        for (success in listOf(false, true)) {
            val persistenceStarted = CompletableDeferred<Unit>()
            val finishPersistence = CompletableDeferred<Unit>()
            var persisted = false
            var publications = 0
            whenever(activityRepo.syncActivities(requireFresh = true)).doSuspendableAnswer {
                persistenceStarted.complete(Unit)
                finishPersistence.await()
                if (success) {
                    persisted = true
                    Result.success(Unit)
                } else {
                    Result.failure(AppError("activity persistence failed"))
                }
            }

            sut.debounceSyncByEvent {
                assertTrue(persisted)
                publications++
            }
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            persistenceStarted.await()
            assertEquals(0, publications)
            finishPersistence.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(if (success) 1 else 0, publications)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `coalesced event sync retains only the latest completion and cancellation never publishes`() = test {
        whenever(transferRepo.syncTransferStates()).thenReturn(Result.success(Unit))
        val finishPersistence = CompletableDeferred<Unit>()
        whenever(activityRepo.syncActivities(requireFresh = true)).doSuspendableAnswer {
            finishPersistence.await()
            Result.success(Unit)
        }
        val completed = mutableListOf<String>()
        sut.debounceSyncByEvent { completed += "superseded" }
        sut.debounceSyncByEvent { completed += "current" }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertTrue(completed.isEmpty())
        finishPersistence.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("current"), completed)
        verify(activityRepo).syncActivities(requireFresh = true)

        sut.debounceSyncByEvent { completed += "cancelled" }
        sut.cancelSyncByEvent()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(listOf("current"), completed)
        verify(activityRepo).syncActivities(requireFresh = true)
    }

    @Test
    fun `buffered unrelated event cannot delay hiding a matched settled invoice`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>(extraBufferCapacity = 64)
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(coreService.isAddressUsed(any())).doSuspendableAnswer {
            releaseCheck.await()
            false
        }
        val maintenance = launch { sut.refreshReusableReceiveAddress() }
        events.emit(NodeEventUpdate(Event.PaymentSuccessful("payment", "hash", "preimage", 10uL)))
        events.emit(
            NodeEventUpdate(
                Event.PaymentReceived("received", "hash", 1000uL, emptyList()),
                settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
            ),
        )
        testScheduler.runCurrent()
        val pendingWhileMaintenanceSuspended = sut.walletState.value.isReceiveInvoiceUpdating
        releaseCheck.complete(Unit)
        maintenance.join()
        testScheduler.runCurrent()

        assertTrue(pendingWhileMaintenanceSuspended)
        assertEquals("", sut.walletState.value.bolt11)
    }

    @Test
    fun `failed channel refresh rejects expired and undecodable fallback invoices`() = test {
        for (decoderMode in 0..2) {
            val events = MutableSharedFlow<NodeEventUpdate>(extraBufferCapacity = 64)
            whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
            whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
            whenever(lightningRepo.getChannels()).thenReturn(channels)
            whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))
            when (decoderMode) {
                0 -> whenever(coreService.decode(INVOICE))
                    .thenReturn(Scanner.Lightning(lightningInvoice(INVOICE, true)))
                1 -> whenever(coreService.decode(INVOICE)).thenThrow(error)
                else -> doReturn(Scanner.OnChain(OnChainInvoice(ADDRESS, 0u, null, null, emptyMap())))
                    .whenever(coreService).decode(INVOICE)
            }
            sut = createSut()
            sut.setOnchainAddress(ADDRESS)
            sut.setBolt11(INVOICE)

            events.emit(NodeEventUpdate(channelReady))
            testScheduler.runCurrent()

            assertTrue(sut.walletState.value.hasReceiveInvoiceError)
        }
    }

    @Test
    fun `late expired validation cannot hide or flag a newer ready request`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>(extraBufferCapacity = 64)
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.failure(error), Result.success(INVOICE_REPLACEMENT))
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        var oldDecodeCalls = 0
        whenever(coreService.decode(INVOICE)).doSuspendableAnswer {
            if (oldDecodeCalls++ == 0) {
                decodeStarted.complete(Unit)
                releaseDecode.await()
            }
            Scanner.Lightning(lightningInvoice(INVOICE, true))
        }
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        events.emit(NodeEventUpdate(channelReady))
        decodeStarted.await()
        val pendingDuringOldValidation = sut.walletState.value.isReceiveInvoiceUpdating

        sut.updateBip21Invoice(SATS / 2uL, "new request").getOrThrow()
        val replacementBeforeDecode = sut.walletState.value
        releaseDecode.complete(Unit)
        testScheduler.runCurrent()

        assertTrue(pendingDuringOldValidation)
        assertEquals(INVOICE_REPLACEMENT, replacementBeforeDecode.bolt11)
        assertFalse(replacementBeforeDecode.isReceiveInvoiceUpdating)
        assertFalse(replacementBeforeDecode.hasReceiveInvoiceError)
        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertEquals(SATS / 2uL, sut.walletState.value.bip21AmountSats)
        assertEquals("new request", sut.walletState.value.bip21Description)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
        assertFalse(sut.walletState.value.hasReceiveInvoiceError)
    }

    private class DeferredReceiveDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runOldestFirst() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        fun runNewestFirst() {
            while (tasks.isNotEmpty()) tasks.removeLast().run()
        }
    }

    @Test
    fun `address usage failure keeps lightning receive and retry available`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenThrow(error)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        assertTrue(sut.refreshReceiveState().isSuccess)
        assertEquals(ADDRESS, sut.walletState.value.onchainAddress)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertTrue(sut.retryReceiveInvoice().isSuccess)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertFalse(sut.walletState.value.hasReceiveInvoiceError)
        verify(lightningRepo, times(2)).createInvoice(anyOrNull(), any(), any(), any())
    }

    @Test
    fun `address-only refresh does not mark the receive invoice as updating`() = test {
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(coreService.isAddressUsed(any())).doSuspendableAnswer {
            releaseCheck.await()
            false
        }
        val job = launch { sut.refreshReusableReceiveAddress() }
        val updatingWhileSuspended = sut.walletState.value.isReceiveInvoiceUpdating
        val invoiceWhileSuspended = sut.walletState.value.bolt11
        releaseCheck.complete(Unit)
        job.join()

        assertFalse(updatingWhileSuspended)
        assertEquals(INVOICE, invoiceWhileSuspended)
    }

    @Test
    fun `reserved address-only refresh does not mark the receive invoice as updating`() = test {
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        whenever { privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(ADDRESS) }.thenReturn(true)
        val releaseAddress = CompletableDeferred<Unit>()
        whenever { privatePaykitAddressReservationRepo.nextReusableReceiveAddress() }.doSuspendableAnswer {
            releaseAddress.await()
            Result.success(ADDRESS_NEW)
        }
        val job = launch { sut.refreshReusableReceiveAddressIfReserved() }
        val updatingWhileSuspended = sut.walletState.value.isReceiveInvoiceUpdating
        val invoiceWhileSuspended = sut.walletState.value.bolt11
        releaseAddress.complete(Unit)
        job.join()

        assertFalse(updatingWhileSuspended)
        assertEquals(INVOICE, invoiceWhileSuspended)
        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
    }

    @Test
    fun `bip21-only publication does not mark the receive invoice as updating`() = test {
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        val releaseCache = CompletableDeferred<Unit>()
        whenever(cacheStore.setBip21(any())).doSuspendableAnswer { releaseCache.await() }
        val job = launch { sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE") }
        val updatingWhileSuspended = sut.walletState.value.isReceiveInvoiceUpdating
        releaseCache.complete(Unit)
        job.join()

        assertFalse(updatingWhileSuspended)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `failed channel refresh retains the existing payable invoice without error`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        val previousBip21 = sut.walletState.value.bip21

        events.emit(NodeEventUpdate(channelReady))

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertEquals(previousBip21, sut.walletState.value.bip21)
        assertFalse(sut.walletState.value.hasReceiveInvoiceError)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `failed channel refresh without an invoice still reports error`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)

        events.emit(NodeEventUpdate(channelReady))

        assertTrue(sut.walletState.value.hasReceiveInvoiceError)
        assertEquals("", sut.walletState.value.bolt11)
    }

    @Test
    fun `address-only publication preserves another pending invoice update`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseInvoice = CompletableDeferred<Unit>()
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            releaseInvoice.await()
            Result.success(INVOICE)
        }
        val invoiceJob = launch { sut.updateBip21Invoice(SATS, "latest") }
        val addressJob = launch { sut.setBip21("bitcoin:$ADDRESS") }
        val updatingWhileQueued = sut.walletState.value.isReceiveInvoiceUpdating
        releaseInvoice.complete(Unit)
        invoiceJob.join()
        addressJob.join()

        assertTrue(updatingWhileQueued)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `address-only refresh preserves a failed edit error`() = test {
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))
        assertTrue(sut.updateBip21Invoice(SATS, "failed edit").isFailure)

        sut.refreshReusableReceiveAddress().getOrThrow()
        sut.setBip21(sut.walletState.value.bip21)

        assertTrue(sut.walletState.value.hasReceiveInvoiceError)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `failed channel refresh preserves a failed edit error over an older invoice`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        assertTrue(sut.updateBip21Invoice(SATS, "failed edit").isFailure)

        events.emit(NodeEventUpdate(channelReady))

        assertTrue(sut.walletState.value.hasReceiveInvoiceError)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `irrelevant node events queued behind address maintenance keep invoice available`() = test {
        val received = Event.PaymentReceived("payment", "hash", 1000uL, emptyList())
        val onchain = Event.OnchainTransactionReceived("txid", mock<TransactionDetails>())
        val updates = listOf(
            NodeEventUpdate(Event.PaymentSuccessful("payment", "hash", "preimage", 10uL)),
            NodeEventUpdate(Event.SyncCompleted(SyncType.ONCHAIN_WALLET, 100u)),
            NodeEventUpdate(received),
            NodeEventUpdate(received, settledReceiveInvoice = SettledReceiveInvoice(INVOICE_REPLACEMENT)),
            NodeEventUpdate(onchain),
            NodeEventUpdate(onchain, settledReceiveAddress = SettledReceiveAddress(ADDRESS_NEW)),
        )
        val pendingStates = mutableListOf<Boolean>()
        for (update in updates) {
            val events = MutableSharedFlow<NodeEventUpdate>()
            whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
            whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
            sut = createSut()
            sut.setOnchainAddress(ADDRESS)
            sut.setBolt11(INVOICE)
            val expectedBip21 = sut.walletState.value.bip21
            val releaseCheck = CompletableDeferred<Unit>()
            whenever(coreService.isAddressUsed(any())).doSuspendableAnswer {
                releaseCheck.await()
                false
            }
            val maintenance = launch { sut.refreshReusableReceiveAddress() }
            val event = launch { events.emit(update) }
            testScheduler.runCurrent()
            pendingStates.add(sut.walletState.value.isReceiveInvoiceUpdating)
            val invoiceWhileQueued = sut.walletState.value.bolt11
            val bip21WhileQueued = sut.walletState.value.bip21
            releaseCheck.complete(Unit)
            maintenance.join()
            event.join()

            assertEquals(INVOICE, invoiceWhileQueued)
            assertEquals(expectedBip21, bip21WhileQueued)
        }
        assertFalse(pendingStates.any { it }, updates.zip(pendingStates).joinToString())
    }

    @Test
    fun `matched lightning settlement queued behind address maintenance stays pending`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        val releaseCheck = CompletableDeferred<Unit>()
        whenever(coreService.isAddressUsed(any())).doSuspendableAnswer {
            releaseCheck.await()
            false
        }
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE_REPLACEMENT))
        val maintenance = launch { sut.refreshReusableReceiveAddress() }
        val settlement = launch {
            events.emit(
                NodeEventUpdate(
                    Event.PaymentReceived("payment", "hash", 1000uL, emptyList()),
                    settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
                ),
            )
        }
        testScheduler.runCurrent()
        val updatingWhileSettled = sut.walletState.value.isReceiveInvoiceUpdating
        val replacement = launch { sut.updateBip21Invoice(SATS, "latest") }
        releaseCheck.complete(Unit)
        maintenance.join()
        replacement.join()
        settlement.join()

        assertTrue(updatingWhileSettled)
        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains(INVOICE_REPLACEMENT))
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `onchain settlement address rotation keeps the spending invoice available`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut = createSut()
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        val releaseAddress = CompletableDeferred<Unit>()
        whenever { privatePaykitAddressReservationRepo.nextReusableReceiveAddress() }.doSuspendableAnswer {
            releaseAddress.await()
            Result.success(ADDRESS_NEW)
        }
        val event = launch {
            events.emit(
                NodeEventUpdate(
                    Event.OnchainTransactionReceived("txid", mock<TransactionDetails>()),
                    settledReceiveAddress = SettledReceiveAddress(ADDRESS),
                ),
            )
        }
        testScheduler.runCurrent()
        val updatingDuringRotation = sut.walletState.value.isReceiveInvoiceUpdating
        val invoiceDuringRotation = sut.walletState.value.bolt11
        releaseAddress.complete(Unit)
        event.join()

        assertFalse(updatingDuringRotation)
        assertEquals(INVOICE, invoiceDuringRotation)
        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
    }

    @Test
    fun `latest receive invocation wins despite reversed background scheduling`() = test {
        val dispatcher = DeferredReceiveDispatcher()
        sut = createSut(dispatcher)
        dispatcher.runOldestFirst()
        sut.setOnchainAddress(ADDRESS)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer { invocation ->
            val amount = invocation.getArgument<ULong?>(0)
            Result.success(if (amount == SATS) INVOICE else INVOICE_REPLACEMENT)
        }
        val older = launch { sut.updateBip21Invoice(SATS, "older") }
        val newer = launch { sut.updateBip21Invoice(SATS / 2uL, "latest") }
        assertTrue(sut.walletState.value.isReceiveInvoiceUpdating)
        dispatcher.runNewestFirst()
        older.join()
        newer.join()

        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertEquals(
            sut.buildBip21Url(ADDRESS, SATS / 2uL, "latest", INVOICE_REPLACEMENT),
            sut.walletState.value.bip21,
        )
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `retry must repeat the failed edited amount`() = test {
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE), Result.failure(error), Result.success(INVOICE_REPLACEMENT))
        sut.updateBip21Invoice(SATS, "old invoice").getOrThrow()
        val editedAmount = SATS / 2uL
        assertTrue(sut.updateBip21Invoice(editedAmount, "old invoice").isFailure)
        assertTrue(sut.walletState.value.hasReceiveInvoiceError)

        sut.retryReceiveInvoice().getOrThrow()

        assertEquals(editedAmount, sut.walletState.value.bip21AmountSats)
        assertEquals("old invoice", sut.walletState.value.bip21Description)
        verify(lightningRepo, times(2)).createInvoice(eq(editedAmount), eq("old invoice"), any(), any())
    }

    @Test
    fun `pending invoice must not erase a newer typed note`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseInvoice = CompletableDeferred<Unit>()
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            releaseInvoice.await()
            Result.success(INVOICE)
        }
        val older = launch { sut.updateBip21Invoice(SATS, "old request") }
        sut.setBip21Description("new text typed after reopening Edit")
        assertEquals("new text typed after reopening Edit", sut.walletState.value.bip21Description)
        releaseInvoice.complete(Unit)
        older.join()

        assertEquals("new text typed after reopening Edit", sut.walletState.value.bip21Description)
    }

    @Test
    fun `retry must recover a failed initial address prerequisite`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(privatePaykitAddressReservationRepo.nextReusableReceiveAddress())
            .thenReturn(Result.failure(error), Result.success(ADDRESS_NEW))
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE))
        assertTrue(sut.refreshReceiveState().isFailure)
        assertTrue(sut.walletState.value.hasReceiveInvoiceError)

        sut.retryReceiveInvoice().getOrThrow()

        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        assertTrue(sut.walletState.value.bip21.startsWith("bitcoin:$ADDRESS_NEW"))
    }

    @Test
    fun `walletExists should return true when mnemonic exists in keychain`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)

        val result = sut.walletExists()

        assertTrue(result)
    }

    @Test
    fun `walletExists should return false when mnemonic does not exist in keychain`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(false)

        val result = sut.walletExists()

        assertFalse(result)
    }

    @Test
    fun `walletExists relies on exists and does not touch load paths`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)

        val result = sut.walletExists()

        assertTrue(result)
        verify(keychain, never()).loadString(Keychain.Key.BIP39_MNEMONIC.name)
        verify(keychain, never()).load(Keychain.Key.BIP39_MNEMONIC.name)
    }

    @Test
    fun `setWalletExistsState should update walletState with current existence status`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)

        sut.setWalletExistsState()

        sut.walletState.test {
            val state = awaitItem()
            assertTrue(state.walletExists)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreWallet should save provided mnemonic and passphrase to keychain`() = test {
        val mnemonic = "restore mnemonic"
        val passphrase = "restore passphrase"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)

        val result = sut.restoreWallet(mnemonic, passphrase)

        assertTrue(result.isSuccess)
        verify(keychain).saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
        verify(keychain).saveString(Keychain.Key.BIP39_PASSPHRASE.name, passphrase)
    }

    @Test
    fun `restoreWallet should work without passphrase`() = test {
        val mnemonic = "restore mnemonic"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)

        val result = sut.restoreWallet(mnemonic, null)

        assertTrue(result.isSuccess)
        verify(keychain).saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
    }

    @Test
    fun `restoreWallet should monitor all address types for restore`() = test {
        val mnemonic = "restore mnemonic"
        whenever(keychain.saveString(any(), any())).thenReturn(Unit)
        var capturedSettings: SettingsData? = null
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            capturedSettings = transform(SettingsData())
        }

        val result = sut.restoreWallet(mnemonic, null)

        assertTrue(result.isSuccess)
        assertEquals("nativeSegwit", capturedSettings?.selectedAddressType)
        assertEquals(
            listOf("legacy", "nestedSegwit", "nativeSegwit", "taproot"),
            capturedSettings?.addressTypesToMonitor,
        )
    }

    @Test
    fun `refreshBip21 should generate new address when current is empty`() = test {
        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
    }

    @Test
    fun `refreshBip21 should generate new address when current has transactions`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(coreService.isAddressUsed(any())).thenReturn(true)

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
    }

    @Test
    fun `refreshBip21 should keep address when current has no transactions`() = test {
        val existingAddress = "existingAddress"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = existingAddress)))
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut = createSut()
        sut.loadFromCache()

        val result = sut.refreshBip21()

        assertTrue(result.isSuccess)
        verify(privatePaykitAddressReservationRepo, never()).nextReusableReceiveAddress()
    }

    @Test
    fun `syncBalances should update balance cache and state`() = test {
        val expectedState = BalanceState(
            totalOnchainSats = 100_000u,
            totalLightningSats = 50_000u,
            maxSendLightningSats = 1000u,
            maxSendOnchainSats = 0u,
            balanceInTransferToSavings = 0u,
            balanceInTransferToSpending = 0u,
        )
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(expectedState))

        sut.syncBalances()

        verify(cacheStore).cacheBalance(expectedState)
        sut.balanceState.test {
            val state = awaitItem()
            assertEquals(expectedState, state)
            assertEquals(expectedState.totalSats, state.totalSats)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshMaxSendLightning should derive the spending limit from the published channels`() = test {
        val syncedState = BalanceState(totalOnchainSats = 100_000u, maxSendLightningSats = 0u)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(syncedState))
        sut.syncBalances()
        val recovered = mock<ChannelDetails> {
            on { isChannelReady } doReturn true
            on { isUsable } doReturn true
            on { nextOutboundHtlcLimitMsat } doReturn 2_000_000u
        }
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(
                LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                    channels = listOf(recovered).toImmutableList(),
                )
            )
        )
        whenever(lightningRepo.getChannels()).thenThrow(IllegalStateException("read failed"))

        sut.refreshMaxSendLightning()

        assertEquals(2_000u, sut.balanceState.value.maxSendLightningSats)
        assertEquals(100_000u, sut.balanceState.value.totalOnchainSats)
        verify(lightningRepo, never()).getChannels()
        verify(lightningRepo, never()).getBalances()
    }

    @Test
    fun `refreshMaxSendLightning should keep the spending limit when the node is not running`() = test {
        val syncedState = BalanceState(maxSendLightningSats = 1_000u)
        whenever(deriveBalanceStateUseCase.invoke()).thenReturn(Result.success(syncedState))
        sut.syncBalances()
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Stopped))
        )

        sut.refreshMaxSendLightning()

        assertEquals(1_000u, sut.balanceState.value.maxSendLightningSats)
    }

    @Test
    fun `refreshBip21ForEvent should not refresh for other events`() = test {
        sut.refreshBip21ForEvent(
            Event.PaymentSuccessful(
                paymentId = "",
                paymentHash = "",
                paymentPreimage = "",
                feePaidMsat = 10uL,
            )
        )

        verify(onchainService, never()).deriveBitcoinAddress(any(), any(), any(), anyOrNull())
        verify(lightningRepo, never()).syncState()
    }

    @Test
    fun `older receive refresh cannot overwrite a newer invoice update`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseOlder = CompletableDeferred<Unit>()
        var invoiceCalls = 0
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            if (invoiceCalls++ == 0) {
                releaseOlder.await()
                Result.success(INVOICE)
            } else {
                Result.success(INVOICE_REPLACEMENT)
            }
        }

        val older = launch { sut.refreshBip21() }
        val newer = launch { sut.updateBip21Invoice(description = "newer") }
        releaseOlder.complete(Unit)
        older.join()
        newer.join()

        val state = sut.walletState.value
        assertEquals(INVOICE_REPLACEMENT, state.bolt11)
        assertTrue(state.bip21.contains("lightning=$INVOICE_REPLACEMENT"))
        assertEquals("newer", state.bip21Description)
    }

    @Test
    fun `cache failure does not terminate the receive event collector`() = test {
        val events = MutableSharedFlow<NodeEventUpdate>()
        whenever(lightningRepo.nodeEventUpdates).thenReturn(events)
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE))
        var cacheCalls = 0
        whenever(cacheStore.update(any())).doSuspendableAnswer {
            if (cacheCalls++ == 0) throw IOException("injected cache write failure")
            Unit
        }
        sut = createSut()
        events.emit(NodeEventUpdate(channelReady))
        assertTrue(sut.walletState.value.hasReceiveInvoiceError)

        events.emit(NodeEventUpdate(channelReady))

        verify(lightningRepo, times(2)).createInvoice(anyOrNull(), any(), any(), any())
        assertFalse(sut.walletState.value.hasReceiveInvoiceError)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `opening refresh delayed by its invoice cannot replace a newer invoice edit`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseFirstInvoice = CompletableDeferred<Unit>()
        var invoiceCalls = 0
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            invoiceCalls += 1
            if (invoiceCalls == 1) {
                releaseFirstInvoice.await()
                Result.success(INVOICE)
            } else {
                Result.success(INVOICE_REPLACEMENT)
            }
        }
        val earlier = launch { sut.refreshReceiveState() }
        val newer = launch { sut.updateBip21Invoice(SATS, "latest edit") }
        releaseFirstInvoice.complete(Unit)
        earlier.join()
        newer.join()

        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertEquals(SATS, sut.walletState.value.bip21AmountSats)
        assertEquals("latest edit", sut.walletState.value.bip21Description)
        assertTrue(sut.walletState.value.bip21.contains("lightning=$INVOICE_REPLACEMENT"))
    }

    @Test
    fun `invoice creation failure reports terminal state and retry recovers`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.failure(error), Result.success(INVOICE))

        assertTrue(sut.refreshReceiveState().isFailure)
        assertTrue(sut.walletState.value.hasReceiveInvoiceError)

        assertTrue(sut.updateBip21Invoice().isSuccess)
        assertFalse(sut.walletState.value.hasReceiveInvoiceError)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains("lightning=$INVOICE"))
    }

    @Test
    fun `invoice publication uses the requested amount and description across suspension`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseInvoice = CompletableDeferred<Unit>()
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            releaseInvoice.await()
            Result.success(INVOICE)
        }
        val refresh = launch { sut.updateBip21Invoice(SATS, "requested") }
        sut.setBip21AmountSats(SATS * 2uL)
        sut.setBip21Description("changed during creation")
        releaseInvoice.complete(Unit)
        refresh.join()

        val state = sut.walletState.value
        assertEquals(SATS * 2uL, state.bip21AmountSats)
        assertEquals("changed during creation", state.bip21Description)
        assertEquals(sut.buildBip21Url(ADDRESS, SATS, "requested", INVOICE), state.bip21)
        verify(lightningRepo).createInvoice(eq(SATS), eq("requested"), any(), any())
        assertFalse(state.isReceiveInvoiceUpdating)
    }

    @Test
    fun `failed invoice edit preserves the published pair and retains the request for retry`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        sut.setBolt11(INVOICE)
        val previous = sut.walletState.value
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.failure(error))

        assertTrue(sut.updateBip21Invoice(SATS, "unsaved edit").isFailure)

        val state = sut.walletState.value
        assertEquals(previous.bolt11, state.bolt11)
        assertEquals(previous.bip21, state.bip21)
        assertEquals(SATS, state.bip21AmountSats)
        assertEquals("unsaved edit", state.bip21Description)
        assertTrue(state.hasReceiveInvoiceError)
    }

    @Test
    fun `address refresh preserves committed invoice metadata while inputs are edited`() = test {
        sut.loadFromCache()
        sut.setBip21AmountSats(SATS)
        sut.setBip21Description("committed")
        sut.setBolt11(INVOICE)
        sut.setBip21AmountSats(SATS * 2uL)
        sut.setBip21Description("draft")
        whenever(coreService.isAddressUsed(ADDRESS)).thenReturn(true)
        whenever(privatePaykitAddressReservationRepo.nextReusableReceiveAddress())
            .thenReturn(Result.success(ADDRESS_NEW))

        sut.refreshReusableReceiveAddress().getOrThrow()

        assertEquals(sut.buildBip21Url(ADDRESS_NEW, SATS, "committed", INVOICE), sut.walletState.value.bip21)
        assertEquals("draft", sut.walletState.value.bip21Description)
    }

    @Test
    fun `queued invoice request cannot reset note typed after its invocation`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseFirstInvoice = CompletableDeferred<Unit>()
        var invoiceCalls = 0
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            invoiceCalls += 1
            if (invoiceCalls == 1) {
                releaseFirstInvoice.await()
                Result.success(INVOICE)
            } else {
                Result.success(INVOICE_REPLACEMENT)
            }
        }
        val opening = launch { sut.refreshReceiveState() }
        val queued = launch { sut.updateBip21Invoice(SATS, "requested") }
        sut.setBip21Description("typed later")
        assertTrue(sut.walletState.value.isReceiveInvoiceUpdating)
        releaseFirstInvoice.complete(Unit)
        opening.join()
        queued.join()

        assertEquals("typed later", sut.walletState.value.bip21Description)
        assertEquals(sut.buildBip21Url(ADDRESS, SATS, "requested", INVOICE_REPLACEMENT), sut.walletState.value.bip21)
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `cancelling a queued request keeps the active invoice update pending`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val releaseInvoice = CompletableDeferred<Unit>()
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            releaseInvoice.await()
            Result.success(INVOICE)
        }
        val active = launch { sut.updateBip21Invoice(SATS, "active") }
        val queued = launch { sut.updateBip21Invoice(SATS, "queued") }
        queued.cancel()
        queued.join()
        assertTrue(sut.walletState.value.isReceiveInvoiceUpdating)
        releaseInvoice.complete(Unit)
        active.join()
        assertFalse(sut.walletState.value.isReceiveInvoiceUpdating)
    }

    @Test
    fun `cancelled receive refresh releases the next invoice update`() = test {
        sut.loadFromCache()
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        val neverReleased = CompletableDeferred<Unit>()
        var invoiceCalls = 0
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).doSuspendableAnswer {
            if (invoiceCalls++ == 0) neverReleased.await()
            Result.success(INVOICE_REPLACEMENT)
        }
        val older = launch { sut.refreshBip21() }
        val newer = launch { sut.updateBip21Invoice() }

        older.cancel()
        older.join()
        newer.join()

        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains("lightning=$INVOICE_REPLACEMENT"))
    }

    @Test
    fun `invoice publication never exposes new bolt11 with onchain only bip21`() = test {
        sut.loadFromCache()
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))
        val states = mutableListOf<WalletState>()
        sut.walletState.onEach { states.add(it) }.launchIn(backgroundScope)

        sut.updateBip21Invoice()

        assertTrue(states.any { it.bolt11 == INVOICE })
        assertTrue(states.filter { it.bolt11 == INVOICE }.all { it.bip21.contains("lightning=$INVOICE") })
    }

    @Test
    fun `updateBip21Invoice should create bolt11 when node can receive`() = test {
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals(INVOICE, sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should not create bolt11 when node cannot receive`() = test {
        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals("", sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `updateBip21Invoice should create bolt11 when channels are ready but not usable`() = test {
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(channels = readyButNotUsableChannels)))
        whenever(lightningRepo.getChannels()).thenReturn(readyButNotUsableChannels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertEquals(INVOICE, sut.walletState.value.bolt11)
        }
    }

    @Test
    fun `inboundLiquiditySats should count ready channels`() = test {
        val mixedChannels = (channels + readyButNotUsableChannels).toImmutableList()
        whenever(lightningRepo.lightningState)
            .thenReturn(MutableStateFlow(LightningState(channels = mixedChannels)))
        whenever(lightningRepo.getChannels()).thenReturn(mixedChannels)

        assertEquals(2_000uL, sut.inboundLiquiditySats())
    }

    @Test
    fun `updateBip21Invoice should build correct BIP21 URL`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))
        sut = createSut()
        sut.loadFromCache()

        sut.updateBip21Invoice(amountSats = SATS, description = "test").let { result ->
            assertTrue(result.isSuccess)
            assertTrue(sut.walletState.value.bip21.contains(ADDRESS))
            assertTrue(sut.walletState.value.bip21.contains("amount=0.00001"))
            assertTrue(sut.walletState.value.bip21.contains("message=test"))
        }
    }

    @Test
    fun `setOnchainAddress should update storage and state`() = test {
        sut.setOnchainAddress(ADDRESS)

        assertEquals(ADDRESS, sut.walletState.value.onchainAddress)
        verify(cacheStore).setOnchainAddress(ADDRESS)
    }

    @Test
    fun `refreshReusableReceiveAddress creates address when cached address is blank`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = "")))
        sut = createSut()
        sut.loadFromCache()

        val result = sut.refreshReusableReceiveAddress()

        assertTrue(result.isSuccess)
        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
        verify(cacheStore).setOnchainAddress(ADDRESS_NEW)
    }

    @Test
    fun `refreshReusableReceiveAddressIfReserved replaces unavailable cached address`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS, bolt11 = INVOICE)))
        sut = createSut()
        sut.loadFromCache()
        whenever { privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(ADDRESS) }
            .thenReturn(true)

        val result = sut.refreshReusableReceiveAddressIfReserved()

        assertTrue(result.isSuccess)
        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
        verify(cacheStore).setOnchainAddress(ADDRESS_NEW)
    }

    @Test
    fun `refreshReusableReceiveAddressIfReserved clears unavailable cached address when replacement fails`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS, bolt11 = INVOICE)))
        whenever { privatePaykitAddressReservationRepo.nextReusableReceiveAddress() }
            .thenReturn(Result.failure(error))
        sut = createSut()
        sut.loadFromCache()
        whenever { privatePaykitAddressReservationRepo.isUnavailableForReusableReceive(ADDRESS) }
            .thenReturn(true)

        val result = sut.refreshReusableReceiveAddressIfReserved()

        assertTrue(result.isFailure)
        assertEquals("", sut.walletState.value.onchainAddress)
        assertEquals(INVOICE, sut.walletState.value.bolt11)
        verify(cacheStore).update(any())
    }

    @Test
    fun `setBolt11 should update storage and state`() = test {
        sut.setBolt11(INVOICE)

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        val update = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore).update(update.capture())
        val cached = update.firstValue(AppCacheData())
        assertEquals(INVOICE, cached.bolt11)
        assertEquals(sut.walletState.value.bip21, cached.bip21)
        assertTrue(cached.bip21.contains("lightning=$INVOICE"))
    }

    @Test
    fun `setBip21 should update storage and state`() = test {
        val testBip21 = "testBip21"

        sut.setBip21(testBip21)

        assertEquals(testBip21, sut.walletState.value.bip21)
        verify(cacheStore).setBip21(testBip21)
    }

    @Test
    fun `buildBip21Url should create correct URL`() = test {
        val testMessage = "test message"

        val result = sut.buildBip21Url(ADDRESS, SATS, testMessage, INVOICE)

        assertTrue(result.contains(ADDRESS))
        assertTrue(result.contains("amount=0.00001"))
        assertTrue(result.contains("message=test+message"))
        assertTrue(result.contains("lightning=testInvoice"))
    }

    @Test
    fun `addTagToSelected should add tag and update lastUsedTags`() = test {
        // Set address in wallet state so paymentId() returns it
        sut.setOnchainAddress(ADDRESS)

        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isSuccess)
        assertEquals(listOf(ACTIVITY_TAG), sut.walletState.value.selectedTags)
        verify(settingsStore).addLastUsedTag(ACTIVITY_TAG)
        verify(preActivityMetadataRepo).addPreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG))
    }

    @Test
    fun `removeTag should remove tag`() = test {
        // Set address in wallet state so paymentId() returns it
        sut.setOnchainAddress(ADDRESS)

        val addResult = sut.addTagToSelected(ACTIVITY_TAG)
        assertTrue(addResult.isSuccess)

        val removeResult = sut.removeTag(ACTIVITY_TAG)
        assertTrue(removeResult.isSuccess)

        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `addTagToSelected should fail when payment ID is not available`() = test {
        // Don't set address, so paymentId() returns null
        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `removeTag should fail when payment ID is not available`() = test {
        // Don't set address, so paymentId() returns null
        val result = sut.removeTag(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `addTagToSelected should fail when metadata repo fails`() = test {
        sut.setOnchainAddress(ADDRESS)
        whenever(preActivityMetadataRepo.addPreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG)))
            .thenReturn(Result.failure(error))

        val result = sut.addTagToSelected(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `removeTag should fail when metadata repo fails`() = test {
        sut.setOnchainAddress(ADDRESS)
        whenever(preActivityMetadataRepo.removePreActivityMetadataTags(ADDRESS, listOf(ACTIVITY_TAG)))
            .thenReturn(Result.failure(error))

        val result = sut.removeTag(ACTIVITY_TAG)

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `clearBip21State should clear all bip21 related state`() = test {
        sut.setOnchainAddress(ADDRESS)
        val addResult = sut.addTagToSelected(ACTIVITY_TAG)
        assertTrue(addResult.isSuccess)
        sut.updateBip21Invoice(amountSats = 1000uL, description = "test")

        sut.clearBip21State()

        assertEquals("", sut.walletState.value.bip21)
        assertEquals(null, sut.walletState.value.bip21AmountSats)
        assertEquals("", sut.walletState.value.bip21Description)
        assertTrue(sut.walletState.value.selectedTags.isEmpty())
    }

    @Test
    fun `setBip21AmountSats should update state`() = test {
        sut.setBip21AmountSats(SATS)

        assertEquals(SATS, sut.walletState.value.bip21AmountSats)
    }

    @Test
    fun `updateOnchainBip21Amount should update amount and bip21 without lightning invoice`() = test {
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        sut.setBip21Description("test")
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        val result = sut.updateOnchainBip21Amount(2000uL)

        assertTrue(result.isSuccess)
        assertEquals(2000uL, sut.walletState.value.bip21AmountSats)
        assertEquals("", sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains(ADDRESS))
        assertTrue(sut.walletState.value.bip21.contains("amount=0.00002"))
        assertTrue(sut.walletState.value.bip21.contains("message=test"))
        assertFalse(sut.walletState.value.bip21.contains("lightning="))
        verify(lightningRepo, never()).createInvoice(anyOrNull(), any(), any(), any())
    }

    @Test
    fun `setBip21Description should update state`() = test {
        val testDescription = "test description"

        sut.setBip21Description(testDescription)

        assertEquals(testDescription, sut.walletState.value.bip21Description)
    }

    @Test
    fun `refreshBip21ForEvent ChannelReady should update bolt11 and preserve amount`() = test {
        val testDescription = "test"
        sut.setBip21AmountSats(SATS)
        sut.setBip21Description(testDescription)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.refreshBip21ForEvent(channelReady)

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertEquals(SATS, sut.walletState.value.bip21AmountSats)
        assertEquals(testDescription, sut.walletState.value.bip21Description)
        verify(lightningRepo).syncState()
    }

    @Test
    fun `refreshBip21ForEvent ChannelReady should not create invoice when cannot receive`() = test {
        sut.setBip21AmountSats(SATS)
        whenever(lightningRepo.canReceive()).thenReturn(false)

        sut.refreshBip21ForEvent(channelReady)

        verify(lightningRepo, never()).createInvoice(anyOrNull(), any(), any(), any())
        verify(lightningRepo).syncState()
    }

    @Test
    fun `refreshBip21ForEvent ChannelClosed should clear bolt11 when live channels are gone`() = test {
        sut.setBolt11(INVOICE)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())

        sut.refreshBip21ForEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null,
            )
        )

        assertEquals("", sut.walletState.value.bolt11)
        verify(lightningRepo).syncState()
    }

    @Test
    fun `refreshBip21ForEvent ChannelReady should create invoice from live channels`() = test {
        sut.setBip21AmountSats(SATS)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any())).thenReturn(Result.success(INVOICE))

        sut.refreshBip21ForEvent(channelReady)

        verify(lightningRepo).createInvoice(anyOrNull(), any(), any(), any())
        verify(lightningRepo).syncState()
        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `refreshBip21ForEvent ChannelClosed should clear bolt11 when cannot receive`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS)))
        sut = createSut()
        sut.loadFromCache()
        sut.setBolt11(INVOICE)
        whenever(lightningRepo.canReceive()).thenReturn(false)

        sut.refreshBip21ForEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null
            )
        )

        assertEquals("", sut.walletState.value.bolt11)
    }

    @Test
    fun `refreshBip21ForEvent ChannelClosed should not clear bolt11 when can still receive`() = test {
        sut.setBolt11(INVOICE)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        whenever(lightningRepo.getChannels()).thenReturn(channels)

        sut.refreshBip21ForEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null
            )
        )

        assertEquals(INVOICE, sut.walletState.value.bolt11)
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should refresh address if used`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS, bolt11 = INVOICE)))
        whenever(coreService.isAddressUsed(any())).thenReturn(true)
        sut = createSut()
        sut.loadFromCache()

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
        )

        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should not refresh address if not used`() = test {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(onchainAddress = ADDRESS, bolt11 = INVOICE)))
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        sut = createSut()
        sut.loadFromCache()

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
        )

        verify(privatePaykitAddressReservationRepo, never()).nextReusableReceiveAddress()
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should invalidate the settled invoice`() = test {
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE_REPLACEMENT))
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE")

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
        )

        assertEquals("", sut.walletState.value.bolt11)
        assertFalse(sut.walletState.value.bip21.contains(INVOICE))
        assertTrue(sut.walletState.value.bip21.contains(ADDRESS))
        verify(cacheStore, never()).invalidateReceiveLightningInvoice(any())
        verify(lightningRepo, never()).createInvoice(anyOrNull(), any(), any(), any())
        verify(preActivityMetadataRepo, never()).addPreActivityMetadata(any())
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should preserve an unrelated receive invoice`() = test {
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE")

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "unrelatedPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
        )

        assertEquals(INVOICE, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains(INVOICE))
        verify(cacheStore, never()).invalidateReceiveLightningInvoice(any())
    }

    @Test
    fun `refreshBip21ForEvent PaymentReceived should preserve a replacement invoice`() = test {
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE_REPLACEMENT)
        sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE_REPLACEMENT")

        sut.refreshBip21ForEvent(
            event = Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
        )

        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains(INVOICE_REPLACEMENT))
        verify(privatePaykitAddressReservationRepo, never()).nextReusableReceiveAddress()
    }

    @Test
    fun `refreshBip21ForEvent OnchainTransactionReceived rotates the settled address`() = test {
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE")

        sut.refreshBip21ForEvent(
            event = Event.OnchainTransactionReceived(
                txid = "txid",
                details = mock<TransactionDetails>(),
            ),
            settledReceiveAddress = SettledReceiveAddress(ADDRESS),
        )

        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        assertTrue(sut.walletState.value.bip21.contains(ADDRESS_NEW))
        assertTrue(sut.walletState.value.bip21.contains(INVOICE))
        verify(privatePaykitAddressReservationRepo).nextReusableReceiveAddress()
        verify(coreService, never()).isAddressUsed(any())
    }

    @Test
    fun `refreshBip21ForEvent OnchainTransactionReceived preserves a replacement address`() = test {
        sut.setOnchainAddress(ADDRESS_NEW)
        sut.setBolt11(INVOICE)
        sut.setBip21("bitcoin:$ADDRESS_NEW?lightning=$INVOICE")

        sut.refreshBip21ForEvent(
            event = Event.OnchainTransactionReceived(
                txid = "txid",
                details = mock<TransactionDetails>(),
            ),
            settledReceiveAddress = SettledReceiveAddress(ADDRESS),
        )

        assertEquals(ADDRESS_NEW, sut.walletState.value.onchainAddress)
        assertTrue(sut.walletState.value.bip21.contains(ADDRESS_NEW))
        verify(privatePaykitAddressReservationRepo, never()).nextReusableReceiveAddress()
        verify(coreService, never()).isAddressUsed(any())
    }

    @Test
    fun `refreshBip21 should create a fresh invoice after PaymentReceived invalidates the old one`() = test {
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(channels = channels)))
        whenever(lightningRepo.getChannels()).thenReturn(channels)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any(), any()))
            .thenReturn(Result.success(INVOICE_REPLACEMENT))
        sut.setOnchainAddress(ADDRESS)
        sut.setBolt11(INVOICE)
        sut.setBip21("bitcoin:$ADDRESS?lightning=$INVOICE")

        sut.refreshBip21ForEvent(
            Event.PaymentReceived(
                paymentId = "testPaymentId",
                paymentHash = "testPaymentHash",
                amountMsat = 1000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice(INVOICE),
        )
        assertEquals("", sut.walletState.value.bolt11)

        sut.refreshBip21()

        assertEquals(INVOICE_REPLACEMENT, sut.walletState.value.bolt11)
        assertTrue(sut.walletState.value.bip21.contains(INVOICE_REPLACEMENT))
    }

    @Test
    fun `completed wallet reset publishes current credential absence with clear recovery`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut.setWalletExistsState()
        testScheduler.runCurrent()
        val attempt = "00000000-0000-0000-0000-000000000001"
        profileRemovalStore.withAccess(attempt, ProfileRemovalScope.WALLET_WIPE) {
            profileRemovalStore.begin(attempt)
            testScheduler.runCurrent()
            assertEquals(WalletResetRecovery.Pending(attempt), sut.walletState.value.walletResetRecovery)
            assertTrue(sut.walletState.value.walletExists)

            whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(false)
            profileRemovalStore.complete(attempt)
            testScheduler.runCurrent()

            assertEquals(WalletResetRecovery.Clear, sut.walletState.value.walletResetRecovery)
            assertFalse(sut.walletState.value.walletExists)
        }
    }

    @Test
    fun `unreadable wallet presence keeps completed reset unavailable until refresh`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut.setWalletExistsState()
        testScheduler.runCurrent()
        val attempt = "00000000-0000-0000-0000-000000000002"
        profileRemovalStore.withAccess(attempt, ProfileRemovalScope.WALLET_WIPE) {
            profileRemovalStore.begin(attempt)
            testScheduler.runCurrent()
            whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenAnswer { throw error }
            profileRemovalStore.complete(attempt)
            testScheduler.runCurrent()

            assertEquals(WalletResetRecovery.Unavailable, sut.walletState.value.walletResetRecovery)
            assertTrue(sut.walletState.value.walletExists)

            sut.resetState()
            assertEquals(WalletResetRecovery.Unavailable, sut.walletState.value.walletResetRecovery)
            assertTrue(sut.walletState.value.walletExists)

            doReturn(false).whenever(keychain).exists(Keychain.Key.BIP39_MNEMONIC.name)
            sut.refreshWalletResetRecovery()
            testScheduler.runCurrent()
            assertEquals(WalletResetRecovery.Clear, sut.walletState.value.walletResetRecovery)
            assertFalse(sut.walletState.value.walletExists)
        }
    }

    @Test
    fun `wipeWallet should call use case`() = test {
        whenever(wipeWalletUseCase.invoke(any(), any(), any(), isNull())).thenReturn(Result.success(Unit))

        val result = sut.wipeWallet()

        assertTrue(result.isSuccess)
        verify(wipeWalletUseCase).invoke(any(), any(), any(), isNull())
    }

    @Test
    fun `wipeWallet re-reads wallet existence when the wipe is incomplete`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        whenever(wipeWalletUseCase.invoke(any(), any(), any(), isNull())).thenReturn(Result.failure(WipeIncomplete()))

        val result = sut.wipeWallet()

        assertTrue(result.isFailure)
        assertTrue(sut.walletState.value.walletExists)
    }

    @Test
    fun `canceled wipe refreshes acknowledged credential erasure without claiming completion`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        sut.setWalletExistsState()
        assertTrue(sut.walletState.value.walletExists)
        whenever(wipeWalletUseCase.invoke(any(), any(), any(), isNull())).thenAnswer {
            whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(false)
            throw CancellationException("Canceled after credential erasure")
        }
        assertFailsWith<CancellationException> { sut.wipeWallet() }
        assertFalse(sut.walletState.value.walletExists)
    }

    @Test
    fun `wipeWallet should return failure when use case fails`() = test {
        whenever(wipeWalletUseCase.invoke(any(), any(), any(), isNull())).thenReturn(Result.failure(error))

        val result = sut.wipeWallet()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getAddresses should call ldk address infos with P2WPKH by default`() = test {
        val result = sut.getAddresses()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertTrue(result.getOrNull()?.firstOrNull()?.path?.startsWith("m/84'") == true)
        verify(lightningRepo).addressInfosForType(AddressType.P2WPKH, isChange = false, startIndex = 0, count = 20)
    }

    @Test
    fun `getAddresses should call ldk address infos with P2TR when addressType is Taproot`() = test {
        val result = sut.getAddresses(addressType = AddressType.P2TR)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertTrue(result.getOrNull()?.firstOrNull()?.path?.startsWith("m/86'") == true)
        verify(lightningRepo).addressInfosForType(AddressType.P2TR, isChange = false, startIndex = 0, count = 20)
    }

    @Test
    fun `getAddresses should derive addresses from mnemonic when ldk address infos are unavailable`() = test {
        whenever { lightningRepo.addressInfosForType(any(), any(), any(), any()) }
            .thenReturn(Result.failure(ServiceError.NodeNotStarted()))
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(null)
        whenever(
            onchainService.deriveBitcoinAddresses(
                mnemonicPhrase = "test mnemonic",
                derivationPathStr = "m/84'/1'/0'",
                network = Network.REGTEST,
                bip39Passphrase = null,
                isChange = false,
                startIndex = 3u,
                count = 2u,
            ),
        ).thenReturn(
            GetAddressesResponse(
                listOf(
                    GetAddressResponse(
                        address = "bc1qfallback0000000000000000000000000000000",
                        path = "m/84'/0'/0'/0/3",
                        publicKey = "public-key",
                    ),
                    GetAddressResponse(
                        address = "bc1qfallback1111111111111111111111111111111",
                        path = "m/84'/0'/0'/0/4",
                        publicKey = "public-key",
                    ),
                ),
            ),
        )

        val result = sut.getAddresses(startIndex = 3, count = 2)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString().orEmpty())
        val addresses = result.getOrThrow()
        assertEquals(2, addresses.size)
        assertEquals("bc1qfallback0000000000000000000000000000000", addresses[0].address)
        assertEquals(3, addresses[0].index)
        assertEquals("m/84'/1'/0'/0/3", addresses[0].path)
        assertEquals("bc1qfallback1111111111111111111111111111111", addresses[1].address)
        assertEquals(4, addresses[1].index)
        assertEquals("m/84'/1'/0'/0/4", addresses[1].path)
        verify(onchainService).deriveBitcoinAddresses(
            mnemonicPhrase = "test mnemonic",
            derivationPathStr = "m/84'/1'/0'",
            network = Network.REGTEST,
            bip39Passphrase = null,
            isChange = false,
            startIndex = 3u,
            count = 2u,
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `connection recovery admits the newly published restored wallet`() = test {
        whenever(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(true)
        val observed = async(UnconfinedTestDispatcher(testScheduler)) {
            sut.walletState.first { it.walletExists }
            runCatching { connectionRecovery.captureRuntimeOwner() }
        }
        sut.restoreWallet("restored-mnemonic", null).getOrThrow()
        assertTrue(observed.await().isSuccess)
    }
}
