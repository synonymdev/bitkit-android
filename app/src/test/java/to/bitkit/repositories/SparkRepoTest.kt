package to.bitkit.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.synonym.bitkitcore.PaymentType as ActivityPaymentType

class SparkRepoTest : BaseUnitTest() {
    private val sparkService = mock<SparkService>()
    private val activityRepo = mock<ActivityRepo>()
    private val sparkEvents = MutableSharedFlow<SdkEvent>()

    private fun createSut() = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { activityRepo },
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { sparkService.getInfo() }.thenReturn(GetInfoResponse("pubkey", 0uL, emptyMap(), 0uL))
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(emptyList())
        whenever { sparkService.listPayments() }.thenReturn(emptyList())
        whenever(sparkService.events).thenReturn(sparkEvents)
        whenever { activityRepo.getActivity(any(), any()) }.thenReturn(Result.success(null))
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.success(Unit))
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
    fun `records a send's fee once a sync reports it`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 0)))
        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 2)))
        sparkEvents.emit(SdkEvent.Synced)

        val activities = argumentCaptor<Activity>()
        verify(activityRepo, times(2)).upsertActivity(activities.capture())
        assertEquals(listOf(0uL, 2uL), activities.allValues.map { (it as Activity.Lightning).v1.fee })
    }

    @Test
    fun `lists a withdrawal with its fee and marks its arrival in Savings as a transfer`() = test {
        val arrival = OnchainActivity.create(
            id = "arrival",
            txType = ActivityPaymentType.RECEIVED,
            txId = "withdrawal",
            value = 4_917uL,
            fee = 0uL,
            address = "bc1q",
            timestamp = 1uL,
        )
        whenever { activityRepo.getOnchainActivityByTxId(eq("withdrawal"), any()) }.thenReturn(arrival)
        whenever { activityRepo.updateActivity(any(), any(), any()) }.thenReturn(Result.success(Unit))
        val withdrawal = sentPayment(fees = 1_470).copy(
            method = PaymentMethod.WITHDRAW,
            details = PaymentDetails.Withdraw("withdrawal"),
        )
        whenever { sparkService.listPayments() }.thenReturn(listOf(withdrawal))
        whenever(sparkService.isConnected).thenReturn(true)

        val sut = createSut()
        sut.start(0).getOrThrow()

        val updated = argumentCaptor<Activity>()
        verify(activityRepo).updateActivity(eq("arrival"), updated.capture(), any())
        assertTrue((updated.firstValue as Activity.Onchain).v1.isTransfer)
        assertEquals(listOf(SparkWithdrawal("withdrawal", 128uL, 1_470uL)), sut.state.value.withdrawals)
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

    private fun sentPayment(fees: Int) = Payment(
        id = "send",
        paymentType = PaymentType.SEND,
        status = PaymentStatus.COMPLETED,
        amount = BigInteger.valueOf(128),
        fees = BigInteger.valueOf(fees.toLong()),
        timestamp = 1uL,
        method = PaymentMethod.LIGHTNING,
        details = PaymentDetails.Lightning(
            description = null,
            invoice = "lnbc1280n1send",
            destinationPubkey = "pubkey",
            htlcDetails = SparkHtlcDetails("hash", "preimage", 1uL, SparkHtlcStatus.PREIMAGE_SHARED),
            lnurlPayInfo = null,
            lnurlWithdrawInfo = null,
            lnurlReceiveMetadata = null,
            conversionInfo = null,
        ),
        conversionDetails = null,
    )
}
