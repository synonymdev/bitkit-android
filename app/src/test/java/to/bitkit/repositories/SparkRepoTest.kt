package to.bitkit.repositories

import breez_sdk_spark.BitcoinAddressDetails
import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.ClaimDeferredReason
import breez_sdk_spark.ClaimDepositOutcome
import breez_sdk_spark.ClaimDepositQuote
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.FetchClaimDepositQuoteResponse
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendOnchainFeeQuote
import breez_sdk_spark.SendOnchainSpeedFeeQuote
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.ServiceRouteLeg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.services.SparkError
import to.bitkit.services.SparkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.ServiceError
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import com.synonym.bitkitcore.PaymentType as ActivityPaymentType

@OptIn(ExperimentalCoroutinesApi::class)
class SparkRepoTest : BaseUnitTest() {
    private companion object {
        const val ADDRESS = "bcrt1qsavings"
    }

    private val sparkService = mock<SparkService>()
    private val activityRepo = mock<ActivityRepo>()
    private val sparkEvents = MutableSharedFlow<SdkEvent>()
    private val cacheData = MutableStateFlow(AppCacheData())
    private val cacheStore = mock<CacheStore> { on { data } doReturn cacheData }
    private val contactResolver = mock<PrivatePaykitContactResolver>()
    private val pendingPaymentRepo = PendingPaymentRepo()
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock {
        override fun now() = this@SparkRepoTest.now
    }

    private fun createSut() = SparkRepo(
        bgDispatcher = testDispatcher,
        sparkService = sparkService,
        activityRepo = { activityRepo },
        cacheStore = cacheStore,
        contactResolver = { contactResolver },
        pendingPaymentRepo = pendingPaymentRepo,
        clock = clock,
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { sparkService.getInfo() }.thenReturn(info(0uL))
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
    fun `marks a withdrawal's arrival as a transfer by the txid its send returned`() = test {
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
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        val sent = sentPayment(fees = 1_470).copy(
            method = PaymentMethod.WITHDRAW,
            details = PaymentDetails.Withdraw("withdrawal", 0u, ADDRESS),
        )
        whenever { sparkService.send(any()) }.thenReturn(sent)
        val sut = createSut()

        assertFalse(sut.markWithdrawalAsTransfer("withdrawal"))
        sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).getOrThrow()

        assertTrue(sut.markWithdrawalAsTransfer("withdrawal"))
        assertFalse(sut.markWithdrawalAsTransfer("other"))
        val updated = argumentCaptor<Activity>()
        verify(activityRepo).updateActivity(eq("arrival"), updated.capture(), any())
        assertTrue((updated.firstValue as Activity.Onchain).v1.isTransfer)
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
            details = PaymentDetails.Withdraw("withdrawal", 0u, ADDRESS),
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
    fun `quotes a withdrawal to a Paykit contact as a route leg`() = test {
        val sut = readySpark(balanceSats = 50_000uL)

        val leg = sut.withdrawalRouteLeg(contactEndpoint, 20_000uL).getOrThrow()

        assertEquals(
            ServiceRouteLeg(
                service = "spark",
                endpoint = contactEndpoint,
                deliveredSat = 20_000uL,
                debitSat = 20_300uL,
                atomicity = "trusted",
                validUntilUnix = 1_900_000_000uL,
            ),
            leg,
        )
    }

    @Test
    fun `offers no route leg the balance cannot cover with its fee`() = test {
        val sut = readySpark(balanceSats = 20_299uL)

        assertEquals(null, sut.withdrawalRouteLeg(contactEndpoint, 20_000uL).getOrThrow())
    }

    private val contactEndpoint = PaykitEndpoint(methodId = "btc-regtest-p2wpkh", value = "bcrt1qcontact")

    private suspend fun readySpark(balanceSats: ULong): SparkRepo {
        whenever { sparkService.getInfo() }.thenReturn(info(balanceSats))
        val speed = SendOnchainSpeedFeeQuote(userFeeSat = 300uL, l1BroadcastFeeSat = 200uL)
        val prepared = mock<PrepareSendPaymentResponse>()
        whenever(prepared.paymentMethod).thenReturn(
            SendPaymentMethod.BitcoinAddress(
                address = mock<BitcoinAddressDetails>(),
                feeQuote = SendOnchainFeeQuote("quote", 1_900_000_000uL, speed, speed, speed, isEstimate = false),
            ),
        )
        whenever { sparkService.prepareSend(eq("bcrt1qcontact"), eq(20_000uL), anyOrNull()) }.thenReturn(prepared)
        whenever(sparkService.isConnected).thenReturn(true)
        return createSut().also { it.start(0).getOrThrow() }
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
    fun `a later deposit reads as claimed once spark claims it`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        sut.followDepositClaim("txid", SparkClaimSpeed.Later, ceilingSats = 500uL)

        whenever { sparkService.listPayments() }.thenReturn(listOf(depositPayment()))
        sparkEvents.emit(SdkEvent.ClaimedDeposits(emptyList()))

        assertEquals(SparkDepositClaim.Claimed, sut.depositClaims.value["txid"])
    }

    @Test
    fun `a payment event settles even when the balance read fails`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val sut = createSut()
        sut.start(0).getOrThrow()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()
        whenever { sparkService.getInfo() }.thenAnswer { throw SdkException.Generic("offline") }

        sparkEvents.emit(SdkEvent.PaymentSucceeded(sentPayment(fees = 1)))
        runCurrent()

        assertEquals("hash", assertIs<Event.PaymentSuccessful>(events.single()).paymentHash)
    }

    @Test
    fun `a restore whose exit data spark rejects fails and keeps it staged`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.importExitState(any()) }.thenAnswer { throw SdkException.Generic("bad state") }
        val sut = createSut()

        val result = sut.restoreExitBackup(SparkExitBackupV1(createdAt = 1, network = "regtest", exitState = "s"), 0)

        assertTrue(result.isFailure)
        assertTrue(File(Env.sparkExitRestorePath(0)).exists())
    }

    @Test
    fun `a sent withdrawal succeeds even when the refresh after it fails`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 500))
        whenever { sparkService.listPayments() }.thenAnswer { throw SdkException.Generic("offline") }

        assertTrue(createSut().withdrawAll(ADDRESS, confirmedFeeSats = 500uL).isSuccess)
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

    @Test
    fun `a receive recorded without its event gets its paykit contact`() = test {
        whenever { contactResolver.contactPublicKeyForPrivateInvoicePaymentHash("hash") }.thenReturn("pubky-contact")
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertEquals("pubky-contact", (activity.firstValue as Activity.Lightning).v1.contact)
    }

    @Test
    fun `a receive whose contact the user detached stays without one`() = test {
        cacheData.value = AppCacheData(detachedActivityContacts = setOf(scopedActivityId(WalletScope.default, "hash")))
        whenever { contactResolver.contactPublicKeyForPrivateInvoicePaymentHash("hash") }.thenReturn("pubky-contact")
        whenever { sparkService.listPayments() }.thenReturn(listOf(receivedPayment()))
        whenever(sparkService.isConnected).thenReturn(true)

        createSut().start(0).getOrThrow()

        val activity = argumentCaptor<Activity>()
        verify(activityRepo).upsertActivity(activity.capture())
        assertNull((activity.firstValue as Activity.Lightning).v1.contact)
        verify(contactResolver, never()).contactPublicKeyForPrivateInvoicePaymentHash(any())
    }

    @Test
    fun `lightning send lookup reads spark's payments and waits until spark has synced them`() = test {
        val sut = createSut()
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))

        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("offline") }
        val failed = sentPayment(fees = 0).copy(id = "failed", status = PaymentStatus.FAILED)
        sut.start(0).getOrThrow()
        whenever { sparkService.listPayments() }.thenReturn(listOf(failed, sentPayment(fees = 1)))
        // A restored wallet lists its payments only after its first sync.
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))

        sparkEvents.emit(SdkEvent.Synced)
        assertEquals(SparkLightningSend.Found(PaymentStatus.COMPLETED, "preimage"), sut.lightningSend("HASH"))
        assertEquals(SparkLightningSend.NotFound, sut.lightningSend("other"))

        whenever { sparkService.listPayments() }.thenAnswer { throw SdkException.Generic("offline") }
        assertEquals(SparkLightningSend.Unavailable, sut.lightningSend("hash"))
    }

    @Test
    fun `a send spark refuses before it leaves is a typed refusal`() = test {
        val sut = createSut()
        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        whenever(sparkService.isConnected).thenReturn(true)
        val noRoute = SdkException.Generic("no route")
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer { throw noRoute }
        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verify(sparkService, never()).send(any())
    }

    @Test
    fun `an expired invoice spark refuses is a typed refusal`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val expired = SdkException.InvalidInput("Invoice has expired")
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenAnswer { throw expired }
        val sut = createSut()

        assertIs<SparkError.InvoiceExpired>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw expired }
        assertIs<SparkError.InvoiceExpired>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())
    }

    @Test
    fun `a spark send over the fee limit is refused before it leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 2uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()

        assertIs<SparkError.SendRefused>(sut.payInvoice("lnbc1", 100uL, maxTotalFeeMsat = 1_999uL).exceptionOrNull())
        verify(sparkService, never()).send(any())

        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 2))
        assertEquals("hash", sut.payInvoice("lnbc1", 100uL, maxTotalFeeMsat = 2_000uL).getOrThrow())
    }

    @Test
    fun `a spark withdrawal pays the quote its fee estimate priced while it holds`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        val held = withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(held)
        whenever { sparkService.send(any()) }.thenReturn(sentPayment(fees = 0))
        val sut = createSut()

        assertEquals(500uL, sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow())
        sut.withdrawAll(ADDRESS, confirmedFeeSats = 500uL).getOrThrow()

        verify(sparkService, times(1)).prepareSend(any(), anyOrNull(), anyOrNull())
        verify(sparkService).send(held)
    }

    @Test
    fun `a spark withdrawal asks again once its quote expired or the address changed`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        val expiring = withdrawal(expiresAt = now.epochSeconds.toULong() + 10uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(expiring)
        val sut = createSut()

        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        verify(sparkService, times(2)).prepareSend(any(), anyOrNull(), anyOrNull())

        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        sut.estimateWithdrawAllFeeSats(ADDRESS).getOrThrow()
        sut.estimateWithdrawAllFeeSats("bcrt1qother").getOrThrow()
        verify(sparkService, times(4)).prepareSend(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a spark send past its deadline never leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()

        val result = sut.payInvoice("lnbc1", 100uL, paymentDeadlineAt = now - 1.minutes)

        assertIs<ServiceError.PaymentDeadlineExpired>(result.exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a failure inside the spark send is pending, not failed`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        whenever { sparkService.send(any()) }.thenAnswer { throw SdkException.Generic("timeout") }

        val sut = createSut()
        val events = mutableListOf<Event>()
        backgroundScope.launch { sut.nodeEvents.collect { events += it } }
        runCurrent()

        val error = sut.payInvoice("lnbc1", 100uL).exceptionOrNull()
        advanceUntilIdle()

        assertEquals("hash", assertIs<PaymentPendingException>(error).paymentHash)
        // Spark synced and holds no such send, so nothing left and the pending send ends as failed.
        assertEquals("hash", assertIs<Event.PaymentFailed>(events.single()).paymentHash)
    }

    @Test
    fun `a max send keeps the routing fee spark quotes for its whole balance`() = test {
        whenever { sparkService.getInfo() }.thenReturn(info(100_000uL))
        val details = mock<Bolt11InvoiceDetails> { on { paymentHash } doReturn "hash" }
        val wholeBalance = PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, 400uL),
            amount = BigInteger.valueOf(99_600),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_INCLUDED,
        )
        whenever { sparkService.prepareSend("lnbc1", 100_000uL, FeePolicy.FEES_INCLUDED) }.thenReturn(wholeBalance)

        val reserve = createSut().maxSendReserveSats("lnbc1").getOrThrow()

        // The service fee alone leaves 99_750 sendable; routing on top caps the send at 99_600.
        assertEquals(99_600uL, SparkFees.sendableSats(100_000uL) - reserve)
    }

    @Test
    fun `an invoice is not sent again while its send is in flight`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sending = CompletableDeferred<Payment>()
        whenever { sparkService.send(any()) }.doSuspendableAnswer { sending.await() }
        val sut = createSut()

        val first = async { sut.payInvoice("lnbc1", 100uL) }
        runCurrent()
        val second = sut.payInvoice("lnbc1", 100uL)
        sending.complete(sentPayment(fees = 0))

        assertIs<PaymentPendingException>(second.exceptionOrNull())
        assertEquals("hash", first.await().getOrThrow())
        verify(sparkService, times(1)).send(any())
    }

    @Test
    fun `an invoice spark already paid or is paying is not sent again`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        val prepared = prepared(feeSats = 0uL)
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }.thenReturn(prepared)
        val sut = createSut()
        sut.start(0).getOrThrow()

        whenever { sparkService.listPayments() }.thenReturn(listOf(sentPayment(fees = 0)))
        assertEquals("hash", sut.payInvoice("lnbc1", 100uL).getOrThrow())

        whenever { sparkService.listPayments() }
            .thenReturn(listOf(sentPayment(fees = 0).copy(status = PaymentStatus.PENDING)))
        assertIs<PaymentPendingException>(sut.payInvoice("lnbc1", 100uL).exceptionOrNull())

        verify(sparkService, never()).send(any())
    }

    @Test
    fun `a withdrawal whose fee rose above the confirmed one or leaves dust never leaves`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(10_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL))
        val sut = createSut()

        assertIs<SparkError.WithdrawalFeeChanged>(sut.withdrawAll(ADDRESS, confirmedFeeSats = 499uL).exceptionOrNull())

        whenever { sparkService.getInfo() }.thenReturn(info(1_000uL))
        whenever { sparkService.prepareSend(any(), anyOrNull(), anyOrNull()) }
            .thenReturn(withdrawal(expiresAt = now.epochSeconds.toULong() + 600uL, amountSats = 1_000))
        assertIs<SparkError.WithdrawalTooSmall>(sut.estimateWithdrawAllFeeSats(ADDRESS).exceptionOrNull())
        verify(sparkService, never()).send(any())
    }

    @Test
    fun `exit backup waits for spark's first sync`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.exportExitState() }.thenReturn("state")
        whenever { sparkService.syncWallet() }.thenAnswer { throw SdkException.Generic("offline") }
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertNull(sut.exitBackup().getOrThrow())

        sparkEvents.emit(SdkEvent.Synced)
        assertEquals("state", sut.exitBackup().getOrThrow()?.exitState)
    }

    @Test
    fun `wipe fails when spark storage is not deleted and clears what spark held`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(5_000uL))
        val sut = createSut()
        sut.start(0).getOrThrow()
        sut.onDepositBroadcast("txid", 50_000uL)
        whenever { sparkService.wipeStorage(0) }.thenAnswer { throw SdkException.Generic("busy") }

        assertIs<SdkException.Generic>(sut.wipe(0).exceptionOrNull())

        assertEquals(SparkState(isSupported = true), sut.state.value)
        assertTrue(sut.depositClaims.value.isEmpty())
    }

    @Test
    fun `can send leaves room for spark's service fee`() = test {
        whenever(sparkService.isConnected).thenReturn(true)
        whenever { sparkService.getInfo() }.thenReturn(info(100_000uL))
        val sut = createSut()
        sut.start(0).getOrThrow()

        assertEquals(99_750uL, sut.state.value.sendableSats)
        assertTrue(sut.canSend(99_750uL))
        assertFalse(sut.canSend(100_000uL))
    }

    @Test
    fun `sendable amount fits the fee beside it`() {
        for (balance in listOf(0uL, 1uL, 400uL, 401uL, 9_572uL, 100_000uL, 21_000_000uL)) {
            val sendable = SparkFees.sendableSats(balance)
            assertTrue(sendable + SparkFees.serviceFeeSats(sendable) <= balance, "$balance")
            assertTrue(sendable + 1uL + SparkFees.serviceFeeSats(sendable + 1uL) > balance, "$balance leaves room")
        }
    }

    private fun info(balanceSats: ULong) = GetInfoResponse(
        identityPubkey = "pubkey",
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = null,
    )

    private fun prepared(feeSats: ULong): PrepareSendPaymentResponse {
        val details = mock<Bolt11InvoiceDetails> { on { paymentHash } doReturn "hash" }
        return PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.Bolt11Invoice(details, null, feeSats),
            amount = BigInteger.valueOf(100),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_EXCLUDED,
        )
    }

    private fun withdrawal(expiresAt: ULong, amountSats: Long = 10_000): PrepareSendPaymentResponse {
        val speed = SendOnchainSpeedFeeQuote(userFeeSat = 300uL, l1BroadcastFeeSat = 200uL)
        val quote = SendOnchainFeeQuote("quote", expiresAt, speed, speed, speed, isEstimate = false)
        return PrepareSendPaymentResponse(
            paymentMethod = SendPaymentMethod.BitcoinAddress(mock<BitcoinAddressDetails>(), quote),
            amount = BigInteger.valueOf(amountSats),
            tokenIdentifier = null,
            conversionEstimate = null,
            feePolicy = FeePolicy.FEES_INCLUDED,
        )
    }

    private fun receivedPayment() = sentPayment(fees = 0).copy(id = "receive", paymentType = PaymentType.RECEIVE)

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
