package to.bitkit.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ImportUnilateralExitStateResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SparkRepoTest : BaseUnitTest() {
    private val sparkService = mock<SparkService>()

    private fun createSut() = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { mock() },
        settingsStore = mock(),
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { sparkService.getInfo() }.thenReturn(GetInfoResponse("pubkey", 0uL, emptyMap(), 0uL))
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(emptyList())
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        whenever(sparkService.events).thenReturn(MutableSharedFlow())
        whenever(sparkService.isSupported).thenReturn(true)
        whenever(sparkService.networkName).thenReturn("regtest")
    }

    @Test
    fun `imports a restored exit backup once Spark connects`() = test {
        whenever { sparkService.importExitState(any()) }.thenReturn(ImportUnilateralExitStateResponse(2u, 0u, 0u, 0u))
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "regtest", exitState = "state"), 0)
            .getOrThrow()
        verify(sparkService, never()).importExitState(any())

        whenever(sparkService.isConnected).thenReturn(true)
        sut.start(0).getOrThrow()

        verify(sparkService).importExitState("state")
        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `skips an exit backup from another network`() = test {
        val sut = createSut()

        sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "mainnet", exitState = "state"), 0)
            .getOrThrow()

        assertFalse(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `lists a broadcast deposit as pending until Spark lists it`() = test {
        val sut = createSut()

        sut.onDepositBroadcast("txid", 2_500uL)

        val deposit = sut.state.value.deposits.single()
        assertEquals("txid", deposit.txid)
        assertEquals(false, deposit.isClaimed)
        assertEquals(2_500uL, sut.state.value.pendingDepositSats)
    }
}
