package to.bitkit.repositories

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.TransferType
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
    }
    private val work = mock<SparkExitWork>()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
    }

    private fun createSut() = SparkExitRepo(
        work = work,
        bgDispatcher = testDispatcher,
        sparkService = mock<SparkService>(),
        sparkRepo = sparkRepo,
        lightningRepo = mock<LightningRepo>(),
        transferRepo = transferRepo,
        activityRepo = mock<ActivityRepo>(),
        coreService = mock<CoreService>(),
        keychain = mock<Keychain>(),
        httpClient = mock<HttpClient>(),
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
}
