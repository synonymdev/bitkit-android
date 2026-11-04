package to.bitkit.repositories

import breez_sdk_spark.ClaimDeferredReason
import breez_sdk_spark.ClaimDepositOutcome
import breez_sdk_spark.ClaimDepositQuote
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.FetchClaimDepositQuoteResponse
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.synonym.bitkitcore.PaymentType as ActivityPaymentType

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `instant claims early at once when the offer is within the ceiling`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(ClaimDepositOutcome.Submitted)
        whenever { sparkService.listPayments() }.thenReturn(emptyList(), listOf(depositPayment()))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        verify(sparkService).claimDeposit("txid", 0u, 300uL)
        assertEquals(SparkDepositClaim.Claimed, sut.depositClaims.value["txid"])
    }

    @Test
    fun `instant asks the user when the early fee is over the ceiling`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 900uL))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        val claim = assertIs<SparkDepositClaim.Repriced>(sut.depositClaims.value["txid"])
        assertEquals(900uL, claim.offer.earlyFeeSats)
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `paying a repriced claim claims at the new fee`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 900uL))
        whenever { sparkService.claimDeposit("txid", 0u, 900uL) }.thenReturn(ClaimDepositOutcome.Submitted)
        val sut = createSut()
        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        sut.payRepricedClaim("txid")
        advanceUntilIdle()

        verify(sparkService).claimDeposit("txid", 0u, 900uL)
        assertEquals(SparkDepositClaim.Adding, sut.depositClaims.value["txid"])
    }

    @Test
    fun `instant shows the new price when the claim comes back repriced`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(
                ClaimDeferredReason.MaxFeeExceeded(requiredFeeSats = 450uL, maxFeeSats = 300uL)
            )
        )
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        val claim = assertIs<SparkDepositClaim.Repriced>(sut.depositClaims.value["txid"])
        assertEquals(450uL, claim.offer.earlyFeeSats)
    }

    @Test
    fun `instant is unavailable when Spark offers only the standard claim`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = null))
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Unavailable, sut.depositClaims.value["txid"])
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `instant is unavailable when the provider declines the early claim`() = test {
        listDeposit()
        whenever { sparkService.fetchClaimQuote("txid", 0u) }.thenReturn(quote(earlyFee = 300uL))
        whenever { sparkService.claimDeposit("txid", 0u, 300uL) }.thenReturn(
            ClaimDepositOutcome.Deferred(ClaimDeferredReason.ProviderDeclined("no"))
        )
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Unavailable, sut.depositClaims.value["txid"])
    }

    @Test
    fun `later leaves the deposit to the claim at maturity`() = test {
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Later, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.Later, sut.depositClaims.value["txid"])
        verify(sparkService, never()).listUnclaimedDeposits()
        verify(sparkService, never()).claimDeposit(any(), any(), any())
    }

    @Test
    fun `instant says Spark has not seen the deposit when it is never listed`() = test {
        val sut = createSut()

        sut.followDepositClaim("txid", SparkClaimSpeed.Instant, ceilingSats = 500uL)
        advanceUntilIdle()

        assertEquals(SparkDepositClaim.NotSeen, sut.depositClaims.value["txid"])
    }

    @Test
    fun `claim step follows the offer and the ceiling`() {
        val choice = SparkClaimOffer.Choice("txid", 0u, earlyFeeSats = 300uL, 0u, 198uL, 3u)

        assertEquals(SparkClaimStep.KeepLooking, SparkClaimStep.of(null, 500uL))
        assertEquals(SparkClaimStep.ClaimEarly(choice), SparkClaimStep.of(choice, 300uL))
        assertEquals(SparkClaimStep.Ask(choice), SparkClaimStep.of(choice, 299uL))
        assertEquals(
            SparkClaimStep.Unavailable,
            SparkClaimStep.of(SparkClaimOffer.Standard("txid", 0u, 198uL, 3u), 500uL)
        )
        assertEquals(SparkClaimStep.Adding, SparkClaimStep.of(SparkClaimOffer.Automatic("txid", 0u, 250uL, 0u), 500uL))
    }

    private fun listDeposit() {
        whenever { sparkService.listUnclaimedDeposits() }.thenReturn(
            listOf(DepositInfo("txid", 0u, 50_000uL, false, null, null, null, null, null, null))
        )
    }

    private fun quote(earlyFee: ULong?) = FetchClaimDepositQuoteResponse(
        amountSats = 50_000uL,
        confirmations = 0u,
        instant = earlyFee?.let { ClaimDepositQuote(0u, 50_000uL - it, it, 200uL, false) },
        mature = ClaimDepositQuote(3u, 49_802uL, 198uL, null, true),
    )

    private fun depositPayment() = sentPayment(fees = 300).copy(
        id = "deposit",
        paymentType = PaymentType.RECEIVE,
        method = PaymentMethod.DEPOSIT,
        details = PaymentDetails.Deposit(txId = "txid", vout = 0u),
    )

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
