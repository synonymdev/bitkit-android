package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.RecoveryVerdict
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.TransferType
import to.bitkit.repositories.SparkExitRecord.Phase
import to.bitkit.services.CoreService
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
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
    ) = SparkExitRepo(
        work = work,
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        sparkRepo = sparkRepo,
        lightningRepo = mock<LightningRepo>(),
        transferRepo = transferRepo,
        activityRepo = mock<ActivityRepo>(),
        coreService = mock<CoreService>(),
        keychain = mock<Keychain>(),
        httpClient = httpClient,
        clock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(1_800_000_000)
        },
        sparkExitTip = SparkExitTip(),
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
}
