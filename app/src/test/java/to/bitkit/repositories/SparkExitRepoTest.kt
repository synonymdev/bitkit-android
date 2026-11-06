package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.RecoveryRedoReason
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
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
import to.bitkit.models.TransferType
import to.bitkit.repositories.SparkExitRecord.Phase
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SparkExitRepoTest : BaseUnitTest() {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val transferRepo = mock<TransferRepo>()
    private val sparkRepo = mock<SparkRepo> {
        on { walletWiped } doReturn MutableSharedFlow()
        on { walletIndex } doReturn 0
        on { state } doReturn MutableStateFlow(SparkState())
    }
    private val work = mock<SparkExitWork>()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
    }

    private fun createSut(
        sparkService: SparkService = mock<SparkService>(),
        httpClient: HttpClient = mock<HttpClient>(),
        keychain: Keychain = mock<Keychain>(),
        coreService: CoreService = mock<CoreService>(),
    ) = SparkExitRepo(
        work = work,
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        sparkRepo = sparkRepo,
        lightningRepo = mock<LightningRepo>(),
        transferRepo = transferRepo,
        activityRepo = mock<ActivityRepo>(),
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
    private fun regtestMempool(posts: MutableList<String>, refusal: String? = null) = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            when {
                request.method == HttpMethod.Post && url.endsWith("/api/tx") -> {
                    posts += String(request.body.toByteArray())
                    if (refusal != null) return@MockEngine respond(refusal, HttpStatusCode.BadRequest)
                    respond(String(request.body.toByteArray()).removePrefix("02"))
                }
                url.endsWith("/blocks/tip/height") -> respond("1000")
                url.endsWith("/status") -> respond("""{"confirmed":false}""")
                else -> throw IOException("unreachable '$url'")
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
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Post && url.endsWith("/api/tx") -> respond("n1")
                    url.endsWith("/address/${SparkExitFixtures.FUNDING_ADDRESS}/utxo") -> respond(utxo)
                    url.endsWith("/blocks/tip/height") -> respond("1000")
                    url.endsWith("/status") -> respond("""{"confirmed":false}""")
                    else -> throw IOException("unreachable '$url'")
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
}
