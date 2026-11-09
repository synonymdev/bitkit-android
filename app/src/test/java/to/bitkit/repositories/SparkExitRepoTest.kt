package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoveryRedoReason
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
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
import org.mockito.kotlin.atLeastOnce
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
import kotlin.time.Instant

class SparkExitRepoTest : BaseUnitTest() {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val transferRepo = mock<TransferRepo>()
    private val wiped = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val sparkRepo = mock<SparkRepo> {
        on { walletWiped } doReturn wiped
        on { walletIndex } doReturn 0
        on { state } doReturn MutableStateFlow(SparkState())
    }
    private val work = mock<SparkExitWork>()

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
        cacheStore = mock<CacheStore> { on { data } doReturn flowOf(AppCacheData()) },
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

    private suspend fun keyed(): Pair<Keychain, CoreService> {
        val keychain = mock<Keychain> { on { loadString(any()) } doReturn "mnemonic" }
        val onchain = mock<OnchainService>()
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
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    url.endsWith("/fee-estimates") -> respond("""{"1":9,"3":6,"144":1}""")
                    else -> throw IOException("unreachable '$url'")
                }
            }
        },
    ) { defaultRequest { contentType(ContentType.Application.Json) } }

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
        return createSut(connectedSpark(sdk), http, keychain, core, lightning)
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
        verify(sparkRepo).setExiting(false, false)
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
