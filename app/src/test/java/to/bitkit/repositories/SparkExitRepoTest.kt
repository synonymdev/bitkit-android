package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoverFundsLeaf
import breez_sdk_spark.RecoveryMethod
import breez_sdk_spark.RecoveryRedoReason
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
import breez_sdk_spark.SkippedLeaf
import breez_sdk_spark.SkippedLeafReason
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.GetAddressResponse
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.atMost
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.models.FeeRate
import to.bitkit.models.TransferType
import to.bitkit.repositories.SparkExitRecord.Phase
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SparkExitRepoTest : BaseUnitTest() {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val transferRepo = mock<TransferRepo>()
    private val wiped = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val sparkState = MutableStateFlow(SparkState())
    private val sparkRepo = mock<SparkRepo> {
        on { walletWiped } doReturn wiped
        on { walletIndex } doReturn 0
        on { state } doReturn sparkState
    }
    private val work = mock<SparkExitWork>()
    private val cacheStore = mock<CacheStore> { on { data } doReturn flowOf(AppCacheData()) }

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { work.activeWalletIndex() }.thenReturn(0)
    }

    private fun createSut(
        sparkService: SparkService = mock<SparkService>(),
        httpClient: HttpClient = mock<HttpClient>(),
        keychain: Keychain = mock<Keychain>(),
        coreService: CoreService = mock<CoreService>(),
        lightningRepo: LightningRepo = mock<LightningRepo>(),
        activityRepo: ActivityRepo = mock<ActivityRepo>(),
    ) = SparkExitRepo(
        work = work,
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        sparkRepo = sparkRepo,
        lightningRepo = lightningRepo,
        transferRepo = transferRepo,
        activityRepo = activityRepo,
        coreService = coreService,
        keychain = keychain,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(1_800_000_000)
        },
        sparkExitTip = SparkExitTip(),
        cacheStore = cacheStore,
    )

    private suspend fun createTransfer() = transferRepo.createTransfer(
        any(),
        any(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
        anyOrNull(),
    )

    private fun transfer(id: String, type: TransferType = TransferType.SPARK_EXIT) =
        TransferEntity(id = id, type = type, amountSats = 111_000L, createdAt = 0L)

    @Test
    fun `load settles exit transfers no running exit owns and resumes the running one`() = test {
        SparkExitStore(File(Env.sparkExitRecordPath(0))).save(SparkExitFixtures.record())
        whenever(transferRepo.activeTransfers).thenReturn(
            flowOf(listOf(transfer("transfer-1"), transfer("orphan"), transfer("close", TransferType.FORCE_CLOSE))),
        )
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.markSettled(any())).thenReturn(Result.success(Unit))

        val record = createSut().load(0).getOrThrow()

        assertEquals("transfer-1", record?.transferId)
        verify(transferRepo).markSettled("orphan")
        verify(transferRepo, never()).markSettled("transfer-1")
        verify(transferRepo, never()).markSettled("close")
        verify(work).schedulePeriodic()
    }

    @Test
    fun `load settles the transfer of an unreadable record it sets aside`() = test {
        val file = File(Env.sparkExitRecordPath(0)).apply {
            parentFile?.mkdirs()
            writeText("{")
        }
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.markSettled(any())).thenReturn(Result.success(Unit))

        val record = createSut().load(0).getOrThrow()

        assertEquals(null, record)
        verify(transferRepo).markSettled("transfer-1")
        verify(work, never()).schedulePeriodic()
        assertEquals(false, file.exists())
    }

    @Test
    fun `load keeps a running exit whose record has an unknown version, to rebuild it from chain`() = test {
        val file = File(Env.sparkExitRecordPath(0))
        SparkExitStore(file).save(SparkExitFixtures.record(Phase.Running))
        val original = file.readText().replace("\"version\":1", "\"version\":2")
        file.writeText(original)
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))

        val record = assertNotNull(createSut().load(0).getOrThrow())

        assertTrue(record.isActive)
        assertTrue(record.isRebuiltFromChain)
        assertTrue(record.isRebuilding)
        assertEquals("transfer-1", record.transferId)
        verify(transferRepo, never()).markSettled(any())
        verify(work).schedulePeriodic()
        assertEquals(record, SparkExitStore(file).load())
        assertEquals(original, File(file.parentFile, "spark-exit.unreadable-1800000000000.json").readText())
    }

    @Test
    fun `a confirmed sweep settles the transfer, but not while failed cooperative leaves are retried`() = test {
        val sweep = SparkExitFixtures.tx(
            "s",
            kind = RecoveryTxKind.SWEEP,
            status = ExitTransactionStatus.Confirmed(50u),
        )
        val running = SparkExitFixtures.record(Phase.Running).adopt(SparkExitFixtures.recovery(listOf(sweep)), 0L)
        val activity = OnchainActivity.create(
            walletId = "wallet0",
            id = "sweep",
            txType = PaymentType.RECEIVED,
            txId = "s",
            value = 90_000uL,
            fee = 0uL,
            address = "bcrt1qdestination",
            timestamp = 0uL,
            feeRate = 1uL,
        ).copy(isTransfer = true)
        val activityRepo = mock<ActivityRepo>()
        whenever(activityRepo.getOnchainActivityByTxId(any(), any())).thenReturn(activity)
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.markSettled(any())).thenReturn(Result.success(Unit))
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))

        // Leaves the operators could not co-sign are still on their way: the transfer keeps counting them.
        // Before and after the retry's own build, which no longer lists the landed sweep.
        val retry = running.copy(failedLeafRetrySince = 900u)
        listOf(retry, retry.adopt(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2"))), 0L)).forEach {
            store.save(it)
            createSut(activityRepo = activityRepo).load().getOrThrow()
        }
        verify(transferRepo, never()).markSettled(any())

        store.save(running)
        createSut(activityRepo = activityRepo).load().getOrThrow()
        verify(transferRepo).markSettled("transfer-1")
    }

    @Test
    fun `a sweep seen before failed leaves are retried keeps the transfer`() = test {
        val sweep = SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP)
        val failed = CooperativeRecoveryFailure(
            "coop-1",
            "o",
            0u,
            CooperativeRecoveryError.OperatorsUnavailable("down"),
        )
        val running = SparkExitFixtures.record(Phase.Running)
            .adopt(SparkExitFixtures.recovery(listOf(sweep), failed = listOf(failed)), 0L)
        val activity = OnchainActivity.create(
            walletId = "wallet0",
            id = "sweep",
            txType = PaymentType.RECEIVED,
            txId = "s",
            value = 90_000uL,
            fee = 0uL,
            address = "bcrt1qdestination",
            timestamp = 0uL,
            feeRate = 1uL,
        ).copy(isTransfer = true)
        val activityRepo = mock<ActivityRepo>()
        whenever(activityRepo.getOnchainActivityByTxId(any(), any())).thenReturn(activity)
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.markSettled(any())).thenReturn(Result.success(Unit))
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))

        assertNull(running.failedLeafRetrySince)
        store.save(running)
        createSut(activityRepo = activityRepo).load().getOrThrow()
        verify(transferRepo, never()).markSettled(any())
    }

    @Test
    fun `leaves lost to an older state outlive the exit record that found them`() = test {
        val recordFile = File(Env.sparkExitRecordPath(0))
        val lost = SparkExitRecord.LostLeaf("leaf-1", 65_536uL)
        SparkExitStore(recordFile).save(
            SparkExitFixtures.record(Phase.Failed(SparkExitDriver.LOST_REASON)).copy(lostLeaves = listOf(lost)),
        )
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(true))
        createSut().load(0).getOrThrow()

        // A new exit replaces the record.
        SparkExitStore(recordFile).save(SparkExitFixtures.record().copy(leafIds = listOf("leaf-2")))
        createSut().load(0).getOrThrow()

        assertEquals(setOf("leaf-1"), SparkExitLostLeafStore(File(Env.sparkExitLostLeavesPath(0))).load())
    }

    /** Spark's regtest mempool, where nothing is confirmed yet and every single transaction is accepted. */
    private fun regtestMempool(
        posts: MutableList<String>,
        refusal: String? = null,
        refusalStatus: HttpStatusCode = HttpStatusCode.BadRequest,
    ) = HttpClient(
        MockEngine.create {
            dispatcher = testDispatcher
            addHandler { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Post && url.endsWith("/api/tx") -> {
                        posts += String(request.body.toByteArray())
                        if (refusal != null) return@addHandler respond(refusal, refusalStatus)
                        respond(String(request.body.toByteArray()).removePrefix("02"))
                    }
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    url.endsWith("/status") -> respond("""{"confirmed":false}""")
                    else -> throw IOException("unreachable '$url'")
                }
            }
        },
    ) { defaultRequest { contentType(ContentType.Application.Json) } }

    /** Spark in a process the worker woke: not connected until the run starts it, which never touches the node. */
    private suspend fun coldSpark(sdk: FakeSparkExitRecovering, connects: Boolean = true): SparkService {
        var isConnected = false
        whenever(sparkRepo.start(any())).thenAnswer {
            isConnected = connects
            Result.success(Unit)
        }
        return mock<SparkService> {
            on { networkName } doReturn "regtest"
            on { this.isConnected } doAnswer { isConnected }
            on { exitRecovering() } doAnswer { sdk.takeIf { isConnected } }
        }
    }

    @Test
    fun `a background run from a cold start drives the exit and a restart sends nothing twice`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val posts = mutableListOf<String>()

        // First run: the keyless pass publishes from the stored recovery, then Spark connects and checks it.
        val sdk = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(recovery, RecoveryVerdict.Valid)
        }
        createSut(coldSpark(sdk), regtestMempool(posts)).runInBackground().getOrThrow()

        assertEquals(listOf("02n1"), posts)
        assertEquals(1, sdk.checkedRecoveries.size)
        assertEquals(listOf("n1"), store.load()?.broadcastLog?.filter { it.isAccepted }?.map { it.txid })
        verify(work, atLeastOnce()).schedulePeriodic()
        verify(work, atLeastOnce()).armDeadline(any(), any())

        // The app was killed: a new process runs again within the rebroadcast interval.
        val restarted = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(recovery, RecoveryVerdict.Valid)
        }
        createSut(coldSpark(restarted), regtestMempool(posts)).runInBackground().getOrThrow()

        assertEquals(listOf("02n1"), posts)
        assertEquals(1, restarted.checkedRecoveries.size)
    }

    @Test
    fun `a background run gives up on a hanging connect at the timeout and leaves it running`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val spark = coldSpark(FakeSparkExitRecovering(), connects = false)
        val gate = CompletableDeferred<Unit>()
        var isCancelled = false
        whenever(sparkRepo.start(any())).doSuspendableAnswer {
            runCatching { gate.await() }.onFailure { isCancelled = true }.getOrThrow()
            Result.success(Unit)
        }

        createSut(spark, regtestMempool(mutableListOf())).runInBackground().getOrThrow()

        assertFalse(gate.isCompleted)
        assertFalse(isCancelled)
        gate.complete(Unit)
    }

    @Test
    fun `a tick that fails after saving a step keeps the saved record`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val running = runningRecord()
        store.save(running)
        val sdk = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(
                running.recovery!!,
                RecoveryVerdict.Redo(RecoveryRedoReason.ON_CHAIN_STATE_DIVERGED),
            )
            // The rebuild is saved, then its prepare fails.
            prepareErrors += SdkException.SparkException("connection reset")
        }
        val sut = quotedSut(sdk)
        sut.load().getOrThrow()

        assertTrue(sut.tick().isFailure)

        assertTrue(assertNotNull(sut.state.value.record).isRebuilding)
        assertEquals(store.load(), sut.state.value.record)
    }

    @Test
    fun `a background run whose steps no host takes for now never pushes the deadline back`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val posts = mutableListOf<String>()

        repeat(2) {
            val spark = coldSpark(FakeSparkExitRecovering(), connects = false)
            val mempool = regtestMempool(posts, "busy", HttpStatusCode.ServiceUnavailable)
            createSut(spark, mempool).runInBackground().getOrThrow()
        }

        assertTrue(posts.isNotEmpty())
        assertNotNull(store.load()?.unpublishedStep)
        verify(work, never()).armDeadline(any(), any())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a wipe waits for the tick in flight and deletes what it saved`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0))).add(
            SparkExitEarlierAddress(
                address = "bcrt1qearlier",
                path = "m/84'/1'/0'/0/3",
                kind = SparkExitEarlierAddress.Kind.KEPT_CHANGE,
                sats = 294uL,
                atMillis = 0L,
            ),
        )
        val gate = CompletableDeferred<Unit>()
        val chain = HttpClient(
            MockEngine.create {
                dispatcher = testDispatcher
                addHandler { request ->
                    val url = request.url.toString()
                    when {
                        url.endsWith("/blocks/tip/height") -> {
                            gate.await()
                            respond("1000")
                        }
                        url.endsWith("/address/$funding/utxo") -> respond("[]")
                        url.endsWith("/fee-estimates") -> respond("""{"1":9,"3":6,"144":1}""")
                        else -> throw IOException("unreachable '$url'")
                    }
                }
            },
        ) { defaultRequest { contentType(ContentType.Application.Json) } }
        val sdk = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(runningRecord().recovery!!, RecoveryVerdict.Valid)
        }
        val sut = quotedSut(sdk, chain)
        sut.load().getOrThrow()

        val tick = async { sut.tick() }
        runCurrent()
        wiped.emit(0)
        gate.complete(Unit)
        tick.await()
        runCurrent()

        assertNull(store.load())
        assertNull(sut.state.value.record)
        verify(work, atLeastOnce()).cancelAll()
        assertFalse(File(Env.sparkExitEarlierAddressesPath(0)).exists(), "the wiped wallet's addresses stayed")
    }

    @Test
    fun `a background run whose steps keep being refused lets the deadline alarm fire`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val posts = mutableListOf<String>()
        val refusal = """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met"}"""

        repeat(SparkExitDriver.REJECTIONS_TO_SURFACE) {
            // The operators are down: Spark does not connect, and the keyless pass publishes alone.
            val spark = coldSpark(FakeSparkExitRecovering(), connects = false)
            createSut(spark, regtestMempool(posts, refusal)).runInBackground().getOrThrow()
        }

        assertEquals(3, posts.size)
        assertEquals(3, store.load()?.broadcastLog?.single()?.rejections)
        // The first two refusals still count as runs that kept up; the third surfaces and stops pushing the alarm.
        verify(work, times(2)).armDeadline(any(), any())
    }

    @Test
    fun `an exit that stops moving after three stall rebuilds notifies once, at once`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val posts = mutableListOf<String>()
        val refusal = """sendrawtransaction RPC error: {"code":-26,"message":"insufficient fee"}"""

        repeat(SparkExitDriver.STALL_REBUILDS_TO_SURFACE + 1) { run ->
            // A rival keeps winning the step: it was refused twice already in this generation, and rebuilds are due.
            val stored = requireNotNull(store.load())
            val refused = SparkExitRecord.BroadcastAttempt(stored.generation, "n1", 2, 2, 0L, "rejected", false)
            store.save(stored.copy(broadcastLog = listOf(refused), lastBuildAt = null))
            val sdk = FakeSparkExitRecovering().apply {
                preparedResponse = SparkExitFixtures.prepared(funding = null)
                checks += CheckRecoverFundsResponse(recovery, RecoveryVerdict.Valid)
                builds += recovery
            }
            createSut(coldSpark(sdk), regtestMempool(posts, refusal)).runInBackground().getOrThrow()

            val record = requireNotNull(store.load())
            val expected = if (run + 1 >= SparkExitDriver.STALL_REBUILDS_TO_SURFACE) 1 else 0
            assertEquals(minOf(run + 1, SparkExitDriver.STALL_REBUILDS_TO_SURFACE), record.stallRebuilds)
            verify(work, times(expected)).notify(eq(SparkExitAlert.NotMoving), anyOrNull(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `a background run whose step keeps going unpaid lets the deadline alarm fire`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        store.save(SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val posts = mutableListOf<String>()
        val refusal = """sendrawtransaction RPC error: {"code":-26,"message":"min relay fee not met, 0 < 17"}"""

        repeat(SparkExitDriver.FEE_CHAIN_FAILURES_TO_REBUILD) {
            // Only the SDK can rebuild the fee chain, and Spark does not connect.
            val spark = coldSpark(FakeSparkExitRecovering(), connects = false)
            createSut(spark, regtestMempool(posts, refusal)).runInBackground().getOrThrow()
        }

        val attempt = store.load()?.broadcastLog?.single()
        assertEquals(0, attempt?.rejections)
        assertEquals(3, attempt?.feeChainFailures)
        verify(work, times(2)).armDeadline(any(), any())
    }

    @Test
    fun `a tick without the sdk connects spark again for a running exit`() = test {
        SparkExitStore(File(Env.sparkExitRecordPath(0))).save(SparkExitFixtures.record())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val disconnected = mock<SparkService> { on { networkName } doReturn "regtest" }
        val sut = createSut(disconnected, fundingChain())
        sut.load(0).getOrThrow()

        sut.tick().getOrThrow()

        verify(sparkRepo).reconnectForExit()
    }

    @Test
    fun `a tick that leaves the exit short of fees asks for at least dust at once`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val funded = "f0".repeat(32)
        store.save(
            SparkExitFixtures.record(Phase.Running).adopt(recovery, 0L).copy(
                funding = listOf(
                    SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", funded, 0u, 9_035uL)
                ),
            ),
        )
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val utxo = """[{"txid":"$funded","vout":0,"value":9035,"status":{"confirmed":true,"block_height":900}}]"""
        val mempool = HttpClient(
            MockEngine.create {
                dispatcher = testDispatcher
                addHandler { request ->
                    val url = request.url.toString()
                    when {
                        request.method == HttpMethod.Post && url.endsWith("/api/tx") -> respond("n1")
                        url.endsWith("/address/${SparkExitFixtures.FUNDING_ADDRESS}/utxo") -> respond(utxo)
                        url.endsWith("/blocks/tip/height") -> respond("1000")
                        url.endsWith("/status") -> respond("""{"confirmed":false}""")
                        else -> throw IOException("unreachable '$url'")
                    }
                }
            },
        ) { defaultRequest { contentType(ContentType.Application.Json) } }
        // The qa-03 run: the rebuild needs 68 sats more than the address holds.
        val sdk = FakeSparkExitRecovering().apply {
            val redo = RecoveryVerdict.Redo(RecoveryRedoReason.ON_CHAIN_STATE_DIVERGED)
            checks += CheckRecoverFundsResponse(recovery, redo)
            preparedResponse = SparkExitFixtures.prepared(funding = null)
            recoverError = SdkException.InsufficientCpfpFunds(9_103uL, "")
        }
        val sut = createSut(coldSpark(sdk), mempool)

        sut.runInBackground().getOrThrow()

        assertEquals(Phase.NeedsFunding(9_239uL), sut.state.value.record?.phase)
        val ask = assertIs<SparkExitFundingState.Ask>(sut.state.value.fundingState)
        assertEquals(SparkExitMath.FUNDING_DUST_SATS, ask.sats)
    }

    @Test
    fun `a background run of an ended exit with leftover to sweep keeps going without starting spark`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(
            SparkExitFixtures.record(Phase.Done).copy(leftover = listOf(SparkExitRecord.Leftover("f0", 0u, 5_035uL))),
        )
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(true))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val spark = coldSpark(FakeSparkExitRecovering())

        createSut(spark, regtestMempool(mutableListOf())).runInBackground().getOrThrow()

        verify(sparkRepo, never()).start(any())
        verify(work, atLeastOnce()).schedulePeriodic()
        verify(work, never()).cancelAll()
    }

    @Test
    fun `a background run that finds no exit to drive stops the background work`() = test {
        // An unreadable record schedules the work before the run finds nothing to salvage.
        File(Env.sparkExitRecordPath(0)).apply { parentFile?.mkdirs() }.writeText("not json")
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))

        assertNull(createSut().runInBackground().getOrThrow())

        verify(work).cancelAll()
    }

    @Test
    fun `a restarted exit signs with the funding key of its own record`() = test {
        val address = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(
            SparkExitFixtures.record(Phase.Done).copy(
                destination = "bcrt1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqc8gma6",
                funding = listOf(SparkExitRecord.Funding(2u, address, "02ab")),
                leftover = listOf(SparkExitRecord.Leftover("f0".repeat(32), 0u, 5_035uL)),
            ),
        )
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(true))
        val utxo = """[{"txid":"${"f0".repeat(32)}","vout":0,"value":5035,"status":{"confirmed":true}}]"""
        val mempool = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.endsWith("/address/$address/utxo") -> respond(utxo)
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    else -> throw IOException("unreachable '$url'")
                }
            },
        )
        val keychain = mock<Keychain> { on { loadString(any()) } doReturn "mnemonic" }
        val onchain = mock<OnchainService>()
        whenever(onchain.deriveBitcoinAddress(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(IllegalStateException("stub"))
        val coreService = mock<CoreService> { on { this.onchain } doReturn onchain }
        val spark = mock<SparkService> { on { networkName } doReturn "regtest" }

        createSut(spark, mempool, keychain, coreService).runInBackground().getOrThrow()

        verify(
            onchain
        ).deriveBitcoinAddress(any(), eq(SparkExitFundingKey.path(2, Env.network)), anyOrNull(), anyOrNull())
    }

    private val lightning = mock<LightningRepo>()

    private val funding = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    /** A compressed regtest WIF of a 32-byte secret of 0x01s. */
    private val wif = "cMceqPhHedrhbcR9eXgzmfWy7kRqLyAxMYwFT6ABDWsiwUp9Nsq9"

    private val keyedOnchain = mock<OnchainService>()

    private suspend fun keyed(): Pair<Keychain, CoreService> {
        val keychain = mock<Keychain> { on { loadString(any()) } doReturn "mnemonic" }
        val onchain = keyedOnchain
        whenever(onchain.deriveBitcoinAddress(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(GetAddressResponse(funding, "m/84'/1'/7797'/0/0", "02ab"))
        whenever(onchain.derivePrivateKey(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(wif)
        return keychain to mock<CoreService> { on { this.onchain } doReturn onchain }
    }

    private suspend fun connectedSpark(sdk: FakeSparkExitRecovering) = mock<SparkService> {
        on { networkName } doReturn "regtest"
        on { isConnected } doReturn true
        on { exitRecovering() } doReturn sdk
    }.also {
        whenever(it.fastestFeeRate()).thenReturn(9uL)
        whenever(it.halfHourFeeRate()).thenReturn(6uL)
    }

    // On the test's own dispatcher, so no tick a start or payment launches runs on after the test.
    private fun fundingChain(utxos: String? = "[]") = HttpClient(
        MockEngine.create {
            dispatcher = testDispatcher
            addHandler { request ->
                val url = request.url.toString()
                when {
                    url.endsWith("/address/$funding/utxo") && utxos != null -> respond(utxos)
                    url.endsWith("/address/$funding") -> respond(addressStats(txCount = 0))
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    url.endsWith("/fee-estimates") -> respond("""{"1":9,"3":6,"144":1}""")
                    else -> throw IOException("unreachable '$url'")
                }
            }
        },
    ) { defaultRequest { contentType(ContentType.Application.Json) } }

    private fun addressStats(txCount: Int) =
        """{"chain_stats":{"tx_count":$txCount},"mempool_stats":{"tx_count":0}}"""

    private fun scannedAddress(index: Int) = "bcrt1qfunding$index"

    /**
     * A chain where the funding addresses at [used] have a transaction, each index its own address, and the address
     * reads [reads] lists; [isDown] answers every address read with a server error.
     */
    private fun scanChain(
        used: Set<Int>,
        reads: MutableList<Int>,
        isDown: Boolean = false,
        coins: Map<Int, Int> = emptyMap(),
    ) = HttpClient(
        MockEngine.create {
            dispatcher = testDispatcher
            addHandler { request ->
                val url = request.url.toString()
                val index = url.substringAfter("/address/bcrt1qfunding", "").toIntOrNull()
                val coinIndex = url.substringAfter("/address/bcrt1qfunding", "").removeSuffix("/utxo").toIntOrNull()
                when {
                    index != null && isDown -> respond("", HttpStatusCode.InternalServerError)
                    index != null -> {
                        reads += index
                        respond(addressStats(txCount = if (index in used) 1 else 0))
                    }
                    url.endsWith("/utxo") && coinIndex != null -> respond(
                        coins[coinIndex]?.let {
                            """[{"txid":"${"ab".repeat(32)}","vout":0,"value":$it,"status":{"confirmed":true}}]"""
                        } ?: "[]"
                    )
                    url.endsWith("/utxo") -> respond("[]")
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    url.endsWith("/fee-estimates") -> respond("""{"1":9,"3":6,"144":1}""")
                    else -> throw IOException("unreachable '$url'")
                }
            }
        },
    ) { defaultRequest { contentType(ContentType.Application.Json) } }

    /** A quoted exit whose funding key at each index derives [scannedAddress] of that index. */
    private suspend fun scannedSut(http: HttpClient): SparkExitRepo {
        val sut = quotedSut(FakeSparkExitRecovering(), http)
        val onchain = keyedOnchain
        whenever(onchain.deriveBitcoinAddress(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            val path = it.getArgument<String>(1)
            GetAddressResponse(scannedAddress(path.substringAfterLast('/').toInt()), path, "02ab")
        }
        return sut
    }

    private suspend fun keptFundingIndexes(): Map<String, Int> {
        val updates = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore, atLeastOnce()).update(updates.capture())
        return updates.allValues.fold(AppCacheData()) { data, update -> update(data) }.sparkExitNextFundingIndexes
    }

    @Test
    fun `a restored wallet with no kept funding index scans past the funding address its last exit used`() = test {
        // Funding indexes 0 to 2 were used before the restore; index 2 still holds what a lost exit received.
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = reads))

        sut.loadQuote().getOrThrow()

        assertEquals(3u, sut.state.value.pending?.fundingIndex)
        assertEquals(scannedAddress(3), sut.state.value.fundingAddress)
        assertTrue(2 in reads, "the funding at index 2 goes unseen")
        assertEquals((0..2 + SparkExitMath.FUNDING_SCAN_GAP_LIMIT).toList(), reads)
        assertEquals(listOf(3), keptFundingIndexes().values.toList(), "the next quote scans again")
    }

    private val now = 1_800_000_000_000L

    private fun keptCoins(index: Int, sats: ULong) = SparkExitEarlierAddress(
        scannedAddress(index),
        SparkExitFundingKey.path(index, Env.network),
        SparkExitEarlierAddress.Kind.KEPT_CHANGE,
        sats,
        now,
    )

    private suspend fun keptCache(initial: AppCacheData): AppCacheData {
        val updates = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore, atLeastOnce()).update(updates.capture())
        return updates.allValues.fold(initial) { data, update -> update(data) }
    }

    @Test
    fun `a restored wallet with no kept funding index watches the funding addresses that still hold coins`() = test {
        // Index 2 still holds what a lost exit received; 0 and 1 were spent by earlier exits.
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = mutableListOf(), coins = mapOf(2 to 5_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(keptCoins(2, 5_000uL)), sut.state.value.earlierAddresses, "the lost funding goes unwatched")
        val stored = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0))).load()
        assertEquals(listOf(keptCoins(2, 5_000uL)), stored)
        assertEquals(listOf(3), keptCache(AppCacheData()).sparkExitNextFundingIndexes.values.toList())
    }

    @Test
    fun `a funding address with history but no coins is not watched`() = test {
        val sut = scannedSut(scanChain(used = setOf(0, 1), reads = mutableListOf(), coins = mapOf(1 to 700)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(keptCoins(1, 700uL)), sut.state.value.earlierAddresses)
    }

    @Test
    fun `a restored funding index brings back the funding addresses that still hold coins`() = test {
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = reads, coins = mapOf(2 to 5_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(keptCoins(2, 5_000uL)), sut.state.value.earlierAddresses, "the lost funding goes unwatched")
        assertEquals((3 until 23).toList(), reads, "a restored index is scanned forward from, once")
        val cache = keptCache(restored)
        assertTrue(cache.sparkExitFundingRecoveryPending.isEmpty(), "every start reads the funding addresses again")
        assertEquals(mapOf(key to 3), cache.sparkExitNextFundingIndexes)
    }

    @Test
    fun `a restored funding index that predates the last exit's funding finds its coins`() = test {
        // The backup carrying index 4 never landed: the exit that funded address 3 is lost with it.
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2, 3), reads = reads, coins = mapOf(3 to 7_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(keptCoins(3, 7_000uL)), sut.state.value.earlierAddresses, "address 3's coins stay hidden")
        assertEquals(mapOf(key to 4), keptCache(restored).sparkExitNextFundingIndexes, "the next exit reuses address 3")
    }

    @Test
    fun `a failed listing of a restored funding address keeps the restore pending`() = test {
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        // A non-empty folder where the list goes makes its write fail.
        File(Env.sparkExitEarlierAddressesPath(0)).apply { mkdirs() }.resolve("blocker").writeText("x")
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = reads, coins = mapOf(2 to 5_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        val updates = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore, atMost(Int.MAX_VALUE)).update(updates.capture())
        val cache = updates.allValues.fold(restored) { data, update -> update(data) }
        assertEquals(
            setOf(key),
            cache.sparkExitFundingRecoveryPending,
            "the coin-holding address is never looked for again",
        )
    }

    @Test
    fun `a quote while a restore is pending scans past the restored funding index first`() = test {
        // The backup carrying index 4 never landed: address 3 still holds the lost exit's coins.
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2, 3), reads = mutableListOf(), coins = mapOf(3 to 7_000)))

        sut.loadQuote().getOrThrow()

        assertEquals(4u, sut.state.value.pending?.fundingIndex, "the next exit funds from the lost exit's address")
        assertEquals(listOf(keptCoins(3, 7_000uL)), sut.state.value.earlierAddresses)
        assertTrue(keptCache(restored).sparkExitFundingRecoveryPending.isEmpty())
    }

    @Test
    fun `a quote while a restore is pending fails when the scan fails`() = test {
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        val sut = scannedSut(scanChain(used = emptySet(), reads = mutableListOf(), isDown = true))

        assertTrue(sut.loadQuote().isFailure, "a quote could fund from the address a lost exit used")

        assertNull(sut.state.value.pending)
        verify(cacheStore, never()).update(any())
    }

    @Test
    fun `a restored funding index at a forgotten exit's address lists its late payment as kept change`() = test {
        // The exit at address 3 was forgotten before funding, so the index went back to 3; a payment came late.
        val key = "${Env.network.name.lowercase()}/wallet0"
        val restored = AppCacheData(
            sparkExitNextFundingIndexes = mapOf(key to 3),
            sparkExitFundingRecoveryPending = setOf(key),
        )
        whenever(cacheStore.data).thenReturn(flowOf(restored))
        val earlier = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0)))
        earlier.add(keptCoins(3, 0uL).copy(kind = SparkExitEarlierAddress.Kind.CANCELLED_FUNDING, sats = null))
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2, 3), reads = mutableListOf(), coins = mapOf(3 to 4_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(keptCoins(3, 4_000uL)), earlier.load(), "watched only until its cancel expires")
        assertEquals(mapOf(key to 4), keptCache(restored).sparkExitNextFundingIndexes)
    }

    @Test
    fun `a kept funding index with no restore pending reads no funding address`() = test {
        val key = "${Env.network.name.lowercase()}/wallet0"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(sparkExitNextFundingIndexes = mapOf(key to 3))))
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = reads, coins = mapOf(2 to 5_000)))
        sut.load(0).getOrThrow()

        sut.refreshEarlierAddresses()

        assertTrue(reads.isEmpty())
        assertTrue(sut.state.value.earlierAddresses.isEmpty())
    }

    @Test
    fun `a funding index restored from the backup is used without a scan`() = test {
        val key = "${Env.network.name.lowercase()}/wallet0"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(sparkExitNextFundingIndexes = mapOf(key to 3))))
        val reads = mutableListOf<Int>()
        val sut = scannedSut(scanChain(used = setOf(0, 1, 2), reads = reads))

        sut.loadQuote().getOrThrow()

        assertEquals(3u, sut.state.value.pending?.fundingIndex)
        assertTrue(reads.isEmpty(), "a backed-up index needs no scan")
    }

    @Test
    fun `a funding scan the chain fails fails the quote and keeps no index`() = test {
        val sut = scannedSut(scanChain(used = emptySet(), reads = mutableListOf(), isDown = true))

        assertTrue(sut.loadQuote().isFailure, "an exit could fund from an address an earlier exit used")

        assertNull(sut.state.value.pending)
        verify(cacheStore, never()).update(any())
    }

    private suspend fun quotedSut(sdk: FakeSparkExitRecovering, http: HttpClient = fundingChain()): SparkExitRepo {
        whenever(sparkRepo.needsSettling()).thenReturn(false)
        whenever(sparkRepo.settleForExit()).thenReturn(Result.success(Unit))
        whenever(lightning.newAddress()).thenReturn(Result.success("bcrt1qdestination"))
        whenever(
            transferRepo.createTransfer(
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        )
            .thenReturn(Result.success("transfer-1"), Result.success("transfer-2"))
        whenever(transferRepo.markSettled(any())).thenReturn(Result.success(Unit))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(transferRepo.isSettled(any())).thenReturn(Result.success(false))
        whenever(transferRepo.updateClaimableAtHeight(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val (keychain, core) = keyed()
        // Spark reads the node's chain, so savings can pay the exit's funding.
        return createSut(connectedSpark(sdk), http, keychain, core, lightning).apply { sharesNodeChain = { true } }
    }

    @Test
    fun `a quote prepares every leaf per node at the fastest rate to a fresh savings address`() = test {
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk)

        val quote = sut.loadQuote().getOrThrow()

        assertEquals(listOf("leaf-1"), quote.leafIds)
        val request = sdk.prepareRequests.single()
        assertEquals(CpfpFundingStrategy.PER_NODE, request.fundingStrategy)
        assertEquals(9uL, request.feeRateSatPerVbyte)
        assertEquals("bcrt1qdestination", request.destination)
        assertEquals(funding, sut.state.value.fundingAddress)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `starting holds spark with the transfer, then reconnects it, and a second start is refused`() = test {
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk)
        sut.loadQuote().getOrThrow()

        sut.start(fundFromSavings = false).getOrThrow()

        val order = inOrder(sparkRepo, transferRepo)
        order.verify(sparkRepo).holdForExit()
        order.verify(
            transferRepo
        ).createTransfer(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        order.verify(sparkRepo).awaitSpendsIdle()
        order.verify(sparkRepo).awaitOptimizationIdle()
        order.verify(sparkRepo).setExiting(true, true)
        val record = assertNotNull(sut.state.value.record)
        assertEquals(SparkExitRecord.PER_NODE, record.fundingStrategy)
        assertEquals("transfer-1", record.transferId)
        assertIs<Phase.AwaitingFunding>(record.phase)
        assertTrue(sut.start(fundFromSavings = false).isFailure)
        // Nothing paid in yet: the one reminder is scheduled for 4 hours (10 minutes on regtest) after the start.
        runCurrent()
        verify(work, atLeastOnce()).scheduleFundingReminder(eq(record.createdAt), any())
        sut.onAppBackgrounded()
    }

    @Test
    fun `a start refused while a spark send is still active saves nothing and can be retried`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        var isSending = true
        whenever(sparkRepo.awaitSpendsIdle()).doSuspendableAnswer {
            if (isSending) throw SparkExitError("A Spark payment is still sending")
        }

        val error = sut.start(fundFromSavings = false).exceptionOrNull()

        assertIs<SparkExitError>(error)
        assertNull(sut.state.value.record)
        assertNull(createSut().load(0).getOrThrow(), "no exit starts while a send can still spend its leaves")
        verify(transferRepo).markSettled("transfer-1")
        verify(sparkRepo).releaseExitHold()
        verify(sparkRepo, never()).awaitOptimizationIdle()
        verify(sparkRepo, never()).setExiting(any(), any())

        isSending = false
        sut.start(fundFromSavings = false).getOrThrow()

        assertEquals("transfer-2", sut.state.value.record?.transferId)
        sut.onAppBackgrounded()
    }

    @Test
    fun `a start saves what stays on spark without the lost leaves spark still counts`() = test {
        SparkExitLostLeafStore(File(Env.sparkExitLostLeavesPath(0))).add(listOf("leaf-lost"))
        val dust = listOf(SkippedLeaf("dust", 1_696uL, SkippedLeafReason.FeeExceedsValue))
        val sdk = FakeSparkExitRecovering()
        sdk.preparedResponses += SparkExitFixtures.prepared(skipped = dust).let {
            it.copy(leaves = it.leaves + RecoverFundsLeaf("leaf-lost", 32_768uL, RecoveryMethod.UNILATERAL))
        }
        sdk.preparedResponse = SparkExitFixtures.prepared(skipped = dust)
        sparkState.value = SparkState(balanceSats = 134_464uL)
        val sut = quotedSut(sdk)
        val quote = sut.loadQuote().getOrThrow()
        assertEquals(100_000uL, quote.leafTotalSats)
        assertEquals(listOf(32_768uL), quote.lostLeafSats)

        sut.start(fundFromSavings = false).getOrThrow()
        // Stops the foreground ticks first, so a failed assertion ends the test.
        sut.onAppBackgrounded()

        assertEquals(1_696uL, sut.state.value.record?.outsideSats)
    }

    @Test
    fun `a start keeps each quoted leaf's value for a leaf its first build leaves behind`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()

        sut.start(fundFromSavings = false).getOrThrow()
        sut.onAppBackgrounded()

        val record = assertNotNull(sut.state.value.record)
        assertNull(record.recovery)
        assertEquals(100_000uL, record.leafValueSats("leaf-1"))
        assertEquals(100_000uL, SparkExitJson.decode(SparkExitJson.encode(record)).leafValueSats("leaf-1"))
        assertEquals(100_000uL, record.leaveBehind("leaf-1", "refused").leftBehindLeafSats)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a start killed before its transfer is written leaves no exit to resume`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        val gate = CompletableDeferred<Result<String>>()
        whenever(createTransfer()).doSuspendableAnswer { gate.await() }

        val start = async { sut.start(fundFromSavings = false) }
        runCurrent()
        start.cancel()

        assertNull(createSut().load(0).getOrThrow(), "no running exit lacks the transfer counting it")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a start killed after its transfer is written leaves that transfer settled and no exit`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        // The row is in the database once the insert runs; the app dies before the start goes on.
        val gate = CompletableDeferred<Result<String>>()
        whenever(createTransfer()).doSuspendableAnswer { gate.await() }

        val start = async { sut.start(fundFromSavings = false) }
        runCurrent()
        start.cancel()
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(listOf(transfer("transfer-1"))))

        assertNull(createSut().load(0).getOrThrow(), "no running exit lacks the transfer counting it")
        verify(transferRepo, atLeastOnce()).markSettled("transfer-1")
    }

    @Test
    fun `a start whose transfer write fails saves no exit and releases spark`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        whenever(createTransfer()).thenReturn(Result.failure(SparkExitError("database locked")))

        assertTrue(sut.start(fundFromSavings = false).isFailure)

        assertNull(SparkExitStore(File(Env.sparkExitRecordPath(0))).load())
        assertNull(sut.state.value.record)
        verify(sparkRepo).releaseExitHold()
        verify(sparkRepo, never()).setExiting(true, true)
    }

    @Test
    fun `a cancel that forgets the exit gives its funding address to the next exit`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        sut.start(fundFromSavings = false).getOrThrow()
        sut.onAppBackgrounded()

        assertEquals(SparkExitCancel.Cancelled, sut.cancel().getOrThrow())

        val updates = argumentCaptor<(AppCacheData) -> AppCacheData>()
        verify(cacheStore, atLeastOnce()).update(updates.capture())
        val cache = updates.allValues.fold(AppCacheData()) { data, update -> update(data) }
        val indexes = cache.sparkExitNextFundingIndexes
        assertEquals(listOf(0), indexes.values.toList(), "a payment landing late at the address goes unwatched")
    }

    @Test
    fun `a cancel that forgets the exit keeps its funding address and path listed`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        sut.start(fundFromSavings = false).getOrThrow()
        sut.onAppBackgrounded()

        sut.cancel().getOrThrow()

        val listed = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0))).load().single()
        assertEquals(funding, listed.address)
        assertEquals("m/84'/1'/${SparkExitFundingKey.ACCOUNT}'/0/0", listed.path)
        assertEquals(SparkExitEarlierAddress.Kind.CANCELLED_FUNDING, listed.kind)
        assertEquals(listOf(listed), sut.state.value.earlierAddresses)
    }

    @Test
    fun `an exit that funds a cancelled exit's address again takes it off the list`() = test {
        val store = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0)))
        val kind = SparkExitEarlierAddress.Kind.CANCELLED_FUNDING
        store.add(SparkExitEarlierAddress(funding, "m/84'/1'/0'/0/0", kind, atMillis = 0L))
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()
        sut.loadQuote().getOrThrow()

        sut.start(fundFromSavings = false).getOrThrow()
        sut.onAppBackgrounded()

        assertTrue(store.load().isEmpty(), "the running exit's funding shows as an earlier exit's")
        assertTrue(sut.state.value.earlierAddresses.isEmpty())
    }

    @Test
    fun `kept change at a cancelled exit's reused address is read past the cancel's four weeks`() = test {
        val store = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0)))
        val path = "m/84'/1'/7797'/0/0"
        val cancelledAt = 1_800_000_000_000L
        val keptAt = cancelledAt + 20.days.inWholeMilliseconds
        val cancelled = SparkExitEarlierAddress.Kind.CANCELLED_FUNDING
        store.add(SparkExitEarlierAddress(funding, path, cancelled, atMillis = cancelledAt))

        store.add(SparkExitEarlierAddress(funding, path, SparkExitEarlierAddress.Kind.KEPT_CHANGE, 600uL, keptAt))

        val listed = store.load().single()
        assertEquals(SparkExitEarlierAddress.Kind.KEPT_CHANGE, listed.kind, "the kept change stays a cancel's entry")
        assertEquals(keptAt, listed.atMillis)
        assertEquals(600uL, listed.sats)
        assertTrue(listed.isWatched(cancelledAt + 60.days.inWholeMilliseconds), "the kept change is no longer read")
    }

    @Test
    fun `a late payment to a cancelled exit's address shows with its balance for four weeks`() = test {
        val now = 1_800_000_000_000L
        val kind = SparkExitEarlierAddress.Kind.CANCELLED_FUNDING
        val store = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0)))
        val recent = now - 1.days.inWholeMilliseconds
        val old = now - 29.days.inWholeMilliseconds
        store.add(SparkExitEarlierAddress(funding, "m/84'/1'/7797'/0/0", kind, atMillis = recent))
        store.add(SparkExitEarlierAddress("bcrt1qold", "m/84'/1'/7797'/0/1", kind, atMillis = old))
        val paid = """[{"txid":"${"e1".repeat(32)}","vout":0,"value":8000,"status":{"confirmed":true}}]"""
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxos = paid))
        sut.load().getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(8_000uL, null), sut.state.value.earlierAddresses.map { it.sats })
        assertEquals(8_000uL, store.load().first().sats)
    }

    @Test
    fun `kept change spent by hand shows its current balance`() = test {
        val store = SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0)))
        val kind = SparkExitEarlierAddress.Kind.KEPT_CHANGE
        store.add(SparkExitEarlierAddress(funding, "m/84'/1'/7797'/0/0", kind, sats = 600uL, atMillis = 0L))
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxos = "[]"))
        sut.load().getOrThrow()

        sut.refreshEarlierAddresses()

        assertEquals(listOf(0uL), sut.state.value.earlierAddresses.map { it.sats }, "the spent change still shows")
    }

    @Test
    fun `an exit that kept its fee change lists the address, so the next exit can start`() = test {
        val kept = SparkExitFixtures.record(Phase.Done).copy(
            leftover = listOf(SparkExitRecord.Leftover("ab".repeat(32), 0u, 294uL)),
            leftoverSweep = SparkExitRecord.LeftoverSweep(
                txid = null,
                txHex = null,
                valueSats = 294uL,
                isDustKept = true,
            ),
            isResultSeen = true,
        )
        SparkExitStore(File(Env.sparkExitRecordPath(0))).save(kept)
        val sut = quotedSut(FakeSparkExitRecovering())

        sut.load().getOrThrow()

        val listed = sut.state.value.earlierAddresses.single()
        assertEquals(SparkExitFixtures.FUNDING_ADDRESS, listed.address)
        assertEquals(SparkExitEarlierAddress.Kind.KEPT_CHANGE, listed.kind)
        assertEquals(294uL, listed.sats)
        assertEquals(listOf(listed), SparkExitEarlierAddressStore(File(Env.sparkExitEarlierAddressesPath(0))).load())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancel during the start's optimization wait ends the exit before anything is paid`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Accepted("paid")) }
        val gate = CompletableDeferred<Unit>()
        whenever(sparkRepo.awaitOptimizationIdle()).doSuspendableAnswer { gate.await() }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()

        val start = async { sut.start(fundFromSavings = true) }
        runCurrent()
        assertTrue(sut.canCancel)
        assertEquals(SparkExitCancel.Cancelled, sut.cancel().getOrThrow())
        gate.complete(Unit)

        assertIs<SparkExitStartCancelled>(start.await().exceptionOrNull())
        assertNull(SparkExitStore(File(Env.sparkExitRecordPath(0))).load())
        assertNull(sut.state.value.record)
        verify(transferRepo).markSettled("transfer-1")
        verify(lightning, never()).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
        verify(sparkRepo, never()).setExiting(true, true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `no tick drives the exit while its start waits for the optimization`() = test {
        val gate = CompletableDeferred<Unit>()
        whenever(sparkRepo.awaitOptimizationIdle()).doSuspendableAnswer { gate.await() }
        val utxo = """[{"txid":"${"f0".repeat(32)}","vout":0,"value":30000,"status":{"confirmed":true}}]"""
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk, fundingChain(utxo))
        sut.loadQuote().getOrThrow()
        val quotes = sdk.prepareRequests.size

        val start = async { sut.start(fundFromSavings = false) }
        runCurrent()
        sut.tick()

        assertEquals(quotes, sdk.prepareRequests.size, "nothing is built while optimization may still swap leaves")
        assertNull(sut.state.value.record?.recovery)
        gate.complete(Unit)
        start.await().getOrThrow()
        sut.onAppBackgrounded()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a start whose screen goes away after its record is saved still takes spark off lightning`() = test {
        val gate = CompletableDeferred<Unit>()
        whenever(sparkRepo.awaitOptimizationIdle()).doSuspendableAnswer { gate.await() }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()

        val start = async { sut.start(fundFromSavings = false) }
        runCurrent()
        start.cancel()
        gate.complete(Unit)
        runCurrent()

        verify(sparkRepo).setExiting(true, true)
        verify(work, atLeastOnce()).schedulePeriodic()
        assertTrue(sut.state.value.isExiting)
        sut.onAppBackgrounded()
    }

    @Test
    fun `a leaf swap between the quote and the start quotes the exit again`() = test {
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk)
        sut.loadQuote().getOrThrow()
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))

        sut.start(fundFromSavings = false).getOrThrow()

        val record = assertNotNull(sut.state.value.record)
        assertEquals(listOf("leaf-2"), record.leafIds)
        assertEquals("transfer-2", record.transferId)
        verify(transferRepo).markSettled("transfer-1")
        sut.onAppBackgrounded()
    }

    @Test
    fun `a requote whose transfer fails goes on with the confirmed quote and still reconnects`() = test {
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk)
        sut.loadQuote().getOrThrow()
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))
        whenever(
            transferRepo.createTransfer(
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(Result.success("transfer-1"), Result.failure(SparkExitError("database locked")))

        sut.start(fundFromSavings = false).getOrThrow()

        val record = assertNotNull(sut.state.value.record)
        assertEquals(listOf("leaf-1"), record.leafIds)
        assertEquals("transfer-1", record.transferId)
        verify(transferRepo, never()).markSettled("transfer-1")
        verify(sparkRepo).setExiting(true, true)
        sut.onAppBackgrounded()
    }

    @Test
    fun `pay from savings fails when the fee rates cannot be read`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        whenever(lightning.getFeeRates()).thenReturn(Result.failure(SparkExitError("no estimates")))
        // What the fee helper falls back to once it cannot price.
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(1_000uL))
        whenever(lightning.getBalancesAsync()).thenReturn(Result.success(balances(100_000uL)))

        assertTrue(sut.savingsFundingFee().isFailure)
    }

    @Test
    fun `a new quote forgets what an earlier funding address showed`() = test {
        val paid = """[{"txid":"${"e1".repeat(32)}","vout":0,"value":8000,"status":{"confirmed":false}}]"""
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxos = paid))
        sut.loadQuote().getOrThrow()
        assertEquals(8_000uL, sut.refreshFundingAddress().getOrThrow())

        sut.loadQuote().getOrThrow()

        assertEquals(0uL, sut.state.value.fundingSeenSats)
        assertEquals(0, sut.state.value.fundingSeenCount)
    }

    @Test
    fun `pay from savings sends only what another wallet's payment left missing`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Accepted("paid")) }
        val paid = """[{"txid":"${"e1".repeat(32)}","vout":0,"value":8000,"status":{"confirmed":false}}]"""
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxos = paid))
        sut.loadQuote().getOrThrow()
        assertEquals(8_000uL, sut.refreshFundingAddress().getOrThrow())
        whenever(lightning.getFeeRates()).thenReturn(Result.success(FeeRates(fast = 7u, mid = 4u, slow = 1u)))
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(300uL))
        whenever(lightning.getBalancesAsync()).thenReturn(Result.success(balances(100_000uL)))
        sut.savingsFundingFee().getOrThrow()

        sut.start(fundFromSavings = true).getOrThrow()
        sut.onAppBackgrounded()

        // The quote's 20,000 less the 8,000 that arrived, plus the 612 input fee its second payment adds at 9 sat/vB.
        verify(lightning).calculateTotalFee(eq(12_612uL), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(lightning).sendOnChain(
            eq(funding), eq(12_612uL), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
    }

    @Test
    fun `pay from savings sends at the rate its fee was priced at`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Accepted("paid")) }
        val rates = FeeRates(fast = 7u, mid = 4u, slow = 1u)
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        whenever(lightning.getFeeRates()).thenReturn(Result.success(rates))
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(300uL))
        whenever(lightning.getBalancesAsync()).thenReturn(Result.success(balances(100_000uL)))
        sut.savingsFundingFee().getOrThrow()
        // A placeholder fee would confirm a total savings can't pay: any pricing error fails instead.
        verify(lightning).calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), eq(rates), eq(false))

        sut.start(fundFromSavings = true).getOrThrow()
        sut.onAppBackgrounded()

        verify(lightning).sendOnChain(
            eq(funding), any(), anyOrNull(), anyOrNull(), eq(rates), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            // Savings never pays more fee than the one confirmed.
            approvedMiningFeeSats = eq(300uL),
        )
    }

    @Test
    fun `pay from savings approves at least the fee a start was refused for`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Accepted("paid")) }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()
        whenever(lightning.getFeeRates()).thenReturn(Result.success(FeeRates(fast = 7u, mid = 4u, slow = 1u)))
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(420uL))
        whenever(lightning.getBalancesAsync()).thenReturn(Result.success(balances(100_000uL)))

        assertEquals(600uL, sut.savingsFundingFee(floorSats = 600uL).getOrThrow().feeSats)
        sut.start(fundFromSavings = true).getOrThrow()
        sut.onAppBackgrounded()

        verify(lightning).sendOnChain(
            eq(funding), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = eq(600uL),
        )
    }

    @Test
    fun `savings never pays more than the confirmed funding when the start quotes again`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Accepted("paid")) }
        val sdk = FakeSparkExitRecovering()
        val sut = quotedSut(sdk)
        val shown = sut.loadQuote().getOrThrow().fundingSats
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = 35_000uL, leaves = listOf("leaf-1", "leaf-2"))

        sut.start(fundFromSavings = true).getOrThrow()
        // Stops the foreground loop even when the check fails, or runTest drives it forever.
        sut.onAppBackgrounded()

        assertEquals(20_000uL, shown)
        verify(lightning).sendOnChain(
            eq(funding), eq(shown), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
    }

    private fun awaitingRecord(paidTxid: String? = null) = SparkExitFixtures.record().copy(
        funding = listOf(SparkExitRecord.Funding(0u, funding, "02ab")),
        fundingPaymentTxid = paidTxid,
    )

    @Test
    fun `cancel forgets an exit nothing reached, and never one savings paid`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord(paidTxid = "paid"))
        val paid = quotedSut(FakeSparkExitRecovering())
        paid.load().getOrThrow()
        assertFalse(paid.canCancel)
        assertTrue(paid.cancel().isFailure)
        assertNotNull(store.load())

        store.save(awaitingRecord())
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()
        assertTrue(sut.canCancel)
        assertEquals(SparkExitCancel.Cancelled, sut.cancel().getOrThrow())
        assertNull(store.load())
        // Nothing was paid: Spark keeps the balance it showed before the exit.
        verify(sparkRepo).releaseExitHold()
        verify(sparkRepo).setExiting(false, true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a savings payment with an unknown result counts as paid and keeps the exit`() = test {
        stubFunding { Result.success(OnchainSendOutcome.Unknown("maybe")) }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.loadQuote().getOrThrow()

        sut.start(fundFromSavings = true).getOrThrow()
        runCurrent()

        val record = assertNotNull(sut.state.value.record)
        assertEquals("maybe", record.fundingPaymentTxid)
        assertTrue(record.isAwaitingOwnPayment)
        assertFalse(sut.canCancel)
        assertTrue(sut.cancel().isFailure)
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        assertEquals("maybe", store.load()?.fundingPaymentTxid)
        verify(lightning, never()).completeAcceptedOrdinaryFollowup(any())
        sut.onAppBackgrounded()
    }

    @Test
    fun `cancel keeps an exit savings may still pay, while its send to the address is unresolved`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        val attempt = OnchainSendAttempt(
            walletId = "wallet",
            attemptId = "attempt",
            requestId = null,
            orderId = null,
            address = funding,
            amountSats = 9_000uL,
            isMaxAmount = false,
            feeRateSatsPerVByte = 9uL,
            isTransfer = false,
            channelId = null,
            tags = emptyList(),
            evidence = OnchainSendEvidence.Unknown,
            txid = "maybe",
        )
        whenever(lightning.currentOnchainSendAttempt()).thenReturn(attempt)
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()

        assertTrue(sut.cancel().isFailure)
        assertFalse(sut.canCancel)
        assertNotNull(store.load())

        // Once savings let the send go, the exit nothing reached can go too.
        whenever(lightning.currentOnchainSendAttempt()).thenReturn(null)
        assertEquals(SparkExitCancel.Cancelled, sut.cancel().getOrThrow())
        assertNull(store.load())
    }

    private fun refusedSavingsPayment() = OnchainSendAttempt(
        walletId = "wallet",
        attemptId = "attempt",
        requestId = null,
        orderId = null,
        address = funding,
        amountSats = 9_000uL,
        isMaxAmount = false,
        feeRateSatsPerVByte = 9uL,
        isTransfer = false,
        channelId = null,
        tags = emptyList(),
        evidence = OnchainSendEvidence.Rejected,
        txid = "refused",
        refusalReason = "sendrawtransaction RPC error: {\"code\":-1}",
    )

    @Test
    fun `a refused savings payment is shown, and a payment still unanswered is not`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        whenever(lightning.currentOnchainSendAttempt()).thenReturn(refusedSavingsPayment())
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()

        sut.tick().getOrThrow()
        assertTrue(sut.state.value.isSavingsPaymentRefused)
        assertFalse(sut.canCancel, "the refused payment may still be sent, so the exit stays")

        whenever(lightning.currentOnchainSendAttempt())
            .thenReturn(refusedSavingsPayment().copy(evidence = OnchainSendEvidence.Unknown))
        sut.tick().getOrThrow()
        assertFalse(sut.state.value.isSavingsPaymentRefused)
        sut.onAppBackgrounded()
    }

    @Test
    fun `paying again from savings re-signs the refused payment over its own inputs`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        val refused = refusedSavingsPayment()
        whenever(lightning.currentOnchainSendAttempt()).thenReturn(refused)
        whenever(
            lightning.retryOriginalOnchainSend(any(), any(), any(), anyOrNull(), any(), any())
        ).thenReturn(Result.success(OnchainSendOutcome.Accepted("again")))
        whenever(lightning.completeAcceptedOrdinaryFollowup(any())).thenReturn(Unit)
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()
        sut.tick().getOrThrow()

        sut.retrySavingsPayment().getOrThrow()

        // The same attempt at the rate the user approved: it spends the refused payment's inputs, so at most one of
        // the two can ever confirm. No new payment picks other coins.
        verify(lightning).retryOriginalOnchainSend(eq("attempt"), eq("wallet"), eq(9uL), anyOrNull(), any(), any())
        verify(lightning, never()).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
        assertEquals("again", assertNotNull(store.load()).fundingPaymentTxid)
        assertFalse(sut.state.value.isSavingsPaymentRefused)
        sut.onAppBackgrounded()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancel while savings pays again waits for the payment and then keeps the exit`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        val refused = refusedSavingsPayment()
        whenever(lightning.currentOnchainSendAttempt()).thenReturn(refused)
        val gate = CompletableDeferred<Unit>()
        whenever(
            lightning.retryOriginalOnchainSend(any(), any(), any(), anyOrNull(), any(), any())
        ).doSuspendableAnswer {
            // Savings accepted it: the attempt is no longer unresolved, but the exit has not recorded it yet.
            whenever(lightning.currentOnchainSendAttempt())
                .thenReturn(refused.copy(evidence = OnchainSendEvidence.Accepted, txid = "again"))
            gate.await()
            Result.success(OnchainSendOutcome.Accepted("again"))
        }
        whenever(lightning.completeAcceptedOrdinaryFollowup(any())).thenReturn(Unit)
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()
        sut.tick().getOrThrow()

        val retry = async { sut.retrySavingsPayment() }
        runCurrent()
        val cancel = async { sut.cancel() }
        runCurrent()
        gate.complete(Unit)
        retry.await().getOrThrow()

        assertTrue(cancel.await().isFailure, "savings paid this exit: it stays")
        assertEquals("again", assertNotNull(store.load()).fundingPaymentTxid)
        sut.onAppBackgrounded()
    }

    @Test
    fun `cancel keeps funding that reached the address and sends it back to savings`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        val utxo = """[{"txid":"${"f0".repeat(32)}","vout":0,"value":5000,"status":{"confirmed":false}}]"""
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxo))
        sut.load().getOrThrow()

        assertEquals(SparkExitCancel.Sweeping, sut.cancel().getOrThrow())

        val record = assertNotNull(store.load())
        assertTrue(record.isCancelled)
        assertEquals(Phase.Failed(CANCELLED_REASON), record.phase)
        assertEquals(5_000uL, record.leftover.single().valueSats)
        assertTrue(record.needsDriving)
        verify(sparkRepo).releaseExitHold()
    }

    @Test
    fun `cancel refuses while the funding address cannot be read`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(awaitingRecord())
        val sut = quotedSut(FakeSparkExitRecovering(), fundingChain(utxos = null))
        sut.load().getOrThrow()

        assertIs<SparkExitCancelUnreadable>(sut.cancel().exceptionOrNull())
        assertNotNull(store.load())
    }

    private fun balances(spendable: ULong) = BalanceDetails(
        totalOnchainBalanceSats = spendable,
        spendableOnchainBalanceSats = spendable,
        totalAnchorChannelsReserveSats = 0u,
        totalLightningBalanceSats = 0u,
        lightningBalances = emptyList(),
        pendingBalancesFromChannelClosures = emptyList(),
    )

    private suspend fun stubFunding(answer: suspend () -> Result<OnchainSendOutcome>) {
        whenever {
            lightning.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.doSuspendableAnswer { answer() }
        whenever(lightning.completeAcceptedOrdinaryFollowup(any())).thenReturn(Unit)
    }

    private fun runningRecord() = SparkExitFixtures.record(Phase.Running)
        .copy(funding = listOf(SparkExitRecord.Funding(0u, funding, "02ab", "f0", 0u, 20_000uL)))
        .adopt(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))), 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a higher fee pays once, even when tapped twice, and never for an ended exit`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        val gate = CompletableDeferred<Unit>()
        stubFunding {
            gate.await()
            Result.success(OnchainSendOutcome.Accepted("bump"))
        }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.load().getOrThrow()
        val option = SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 6_000uL, 6_400uL)

        val first = async { sut.payHigherFee(option) }
        advanceUntilIdle()
        assertTrue(sut.payHigherFee(option).isFailure)
        gate.complete(Unit)
        first.await().getOrThrow()
        // The tick the payment starts runs before the record is changed under it.
        advanceUntilIdle()

        val record = assertNotNull(store.load())
        assertEquals("bump", record.fundingPaymentTxid)
        assertEquals(9uL, record.feeRateSatPerVbyte)
        assertTrue(record.isDrivingWhileFunding)

        store.save(runningRecord().copy(phase = Phase.Done))
        val ended = quotedSut(FakeSparkExitRecovering())
        ended.load().getOrThrow()
        assertTrue(ended.payHigherFee(option).isFailure)
    }

    @Test
    fun `a higher fee is never paid from savings on a chain spark does not read`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        var isPaid = false
        stubFunding {
            isPaid = true
            Result.success(OnchainSendOutcome.Accepted("bump"))
        }
        val sut = quotedSut(FakeSparkExitRecovering())
        sut.sharesNodeChain = { false }
        sut.load().getOrThrow()

        assertTrue(sut.payHigherFee(SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 6_000uL, 6_400uL)).isFailure)

        assertFalse(isPaid, "savings paid on a chain spark does not read")
        assertEquals(2uL, assertNotNull(store.load()).feeRateSatPerVbyte)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a higher fee paid while the exit ends is watched until its outputs reach the address`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        val gate = CompletableDeferred<Unit>()
        stubFunding {
            gate.await()
            Result.success(OnchainSendOutcome.Accepted("bump"))
        }
        var utxos = "[]"
        val http = HttpClient(
            MockEngine.create {
                dispatcher = testDispatcher
                addHandler { request ->
                    val url = request.url.toString()
                    when {
                        url.endsWith("/address/$funding/utxo") -> respond(utxos)
                        url.endsWith("/blocks/tip/height") -> respond("1000")
                        url.endsWith("/fee-estimates") -> respond("""{"1":9,"3":6,"144":1}""")
                        url.endsWith("/tx/bump/hex") -> respond("02bump")
                        else -> throw IOException("unreachable '$url'")
                    }
                }
            },
        ) { defaultRequest { contentType(ContentType.Application.Json) } }
        val sdk = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(runningRecord().recovery!!, RecoveryVerdict.Done)
        }
        val sut = quotedSut(sdk, http)
        sut.load().getOrThrow()

        val payment = async { sut.payHigherFee(SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 6_000uL, 6_400uL)) }
        advanceUntilIdle()
        // A tick ends the exit while savings sends, before any of the payment reached the address.
        sut.tick().getOrThrow()
        assertEquals(Phase.Done, sut.state.value.record?.phase)
        gate.complete(Unit)
        payment.await().getOrThrow()
        advanceUntilIdle()

        val watching = assertNotNull(store.load())
        assertTrue(watching.needsDriving)
        assertEquals("bump", watching.fundingPaymentTxid)
        assertTrue(watching.leftover.isEmpty())

        utxos = """[{"txid":"bump","vout":1,"value":6000,"status":{"confirmed":true}}]"""
        sut.tick().getOrThrow()

        val record = assertNotNull(store.load())
        assertEquals(listOf(SparkExitRecord.Leftover("bump", 1u, 6_000uL)), record.leftover)
        assertTrue(record.needsDriving)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a higher fee paid while the exit starts rebuilding stays watched`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        val gate = CompletableDeferred<Unit>()
        stubFunding {
            gate.await()
            Result.success(OnchainSendOutcome.Unknown("bump"))
        }
        val sdk = FakeSparkExitRecovering().apply {
            checks += CheckRecoverFundsResponse(
                runningRecord().recovery!!,
                RecoveryVerdict.Redo(RecoveryRedoReason.ON_CHAIN_STATE_DIVERGED),
            )
            prepareErrors += SdkException.SparkException("connection reset")
        }
        val sut = quotedSut(sdk)
        sut.load().getOrThrow()

        val payment = async { sut.payHigherFee(SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 6_000uL, 6_400uL)) }
        advanceUntilIdle()
        // A tick starts a rebuild while savings sends.
        sut.tick()
        assertTrue(assertNotNull(sut.state.value.record).isRebuilding)
        gate.complete(Unit)
        payment.await().getOrThrow()
        advanceUntilIdle()

        val record = assertNotNull(store.load())
        assertEquals("bump", record.fundingPaymentTxid)
        // Should the exit end before the payment is adopted, the ended exit keeps watching it.
        assertTrue(record.copy(phase = Phase.Done).isEndedPaymentPending)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a higher fee pays at the rates its total was priced at`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        stubFunding { Result.success(OnchainSendOutcome.Accepted("bump")) }
        val rates = FeeRates(fast = 12u, mid = 8u, slow = 2u)
        val sdk = FakeSparkExitRecovering().apply { preparedResponse = SparkExitFixtures.prepared(funding = null) }
        val sut = quotedSut(sdk)
        sut.load().getOrThrow()
        whenever(lightning.getFeeRates()).thenReturn(Result.success(rates))

        assertTrue(sut.feeBumpOptions().getOrThrow().all { it.feeRates == rates })

        sut.payHigherFee(SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 6_000uL, 6_400uL, rates)).getOrThrow()
        advanceUntilIdle()
        verify(lightning).sendOnChain(
            eq(funding), eq(6_000uL), anyOrNull(), anyOrNull(), eq(rates), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            // The total shown less the extra funding.
            approvedMiningFeeSats = eq(400uL),
        )
    }

    @Test
    fun `a fee bump whose savings payment can't be priced is not offered at a placeholder fee`() = test {
        val sut = quotedSut(FakeSparkExitRecovering())
        val rates = FeeRates(fast = 12u, mid = 8u, slow = 2u)
        // The fee helper's placeholder once it cannot price, and the failure it reports without it.
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq(true)))
            .thenReturn(Result.success(1_000uL))
        whenever(lightning.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq(false)))
            .thenReturn(Result.failure(SparkExitError("no estimates")))

        val priced = runCatching { sut.feeBumpOption(FeeRate.FAST, 9uL, 6_000uL, funding, rates) }

        assertTrue(priced.isFailure)
    }

    @Test
    fun `fee bump rates are priced without broadcasting, leaving out a rate the sdk refuses a leaf at`() = test {
        val store = SparkExitStore(File(Env.sparkExitRecordPath(0)))
        store.save(runningRecord())
        val sdk = FakeSparkExitRecovering().apply {
            preparedResponse = SparkExitFixtures.prepared(funding = null)
            prepareErrors += SdkException.SparkException("Leaf leaf-1 cannot be exited: its refund cannot pay its fee")
        }
        val sut = quotedSut(sdk)
        sut.load().getOrThrow()

        val options = sut.feeBumpOptions().getOrThrow()

        assertEquals(listOf(FeeRate.NORMAL to 6uL), options.map { it.speed to it.rateSatPerVbyte })
        assertEquals(0uL, options.single().totalSats)

        // No rate left that every leaf can pay its exit at: that is the error.
        val refusal = "Leaf leaf-1 cannot be exited: its refund cannot pay its fee"
        repeat(2) { sdk.prepareErrors += SdkException.SparkException(refusal) }
        assertIs<SparkExitFeeBumpLeavesBehind>(sut.feeBumpOptions().exceptionOrNull())
        assertTrue(sdk.recoverRequests.isEmpty())
    }
}
