package to.bitkit.wear.repositories

import breez_sdk_spark.FeePolicy
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendPaymentMethod
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.OnChainInvoice
import com.synonym.bitkitcore.Scanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.FakeOwnInvoiceStore
import to.bitkit.wear.data.FakeUncertainSendStore
import to.bitkit.wear.data.UncertainSend
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.PreparedSend
import to.bitkit.wear.models.ReceiveError
import to.bitkit.wear.models.ReceiveInvoice
import to.bitkit.wear.models.SendError
import to.bitkit.wear.models.SendRequest
import to.bitkit.wear.services.InputDecoder
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.SparkFixtures
import to.bitkit.wear.services.SparkFixtures.IDENTITY
import to.bitkit.wear.services.SparkFixtures.OTHER_IDENTITY
import to.bitkit.wear.services.SparkFixtures.PAYMENT_HASH
import to.bitkit.wear.services.SparkFixtures.PROVIDER_HINT
import to.bitkit.wear.services.SparkFixtures.SPARK_ADDRESS
import to.bitkit.wear.services.SparkFixtures.SPARK_ADDRESS_HINT
import to.bitkit.wear.services.SparkFixtures.invoiceInput
import to.bitkit.wear.services.SparkFixtures.payment
import to.bitkit.wear.services.SparkFixtures.sparkAddressInput
import to.bitkit.wear.services.SparkSync
import to.bitkit.wear.services.WalletSelfSendGuard
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsRepoTest : BaseUnitTest() {
    private companion object {
        const val BOLT11 = "lnbcrt1invoice"
        const val LNURL_BOLT11 = "lnbcrt1fromlnurl"
        const val BALANCE = 10_000uL
        const val FEE = 4uL
        val RESERVATION = QuickPayReservation("2026-11-28", cents = 50)
    }

    private val sparkService = mock<WatchSparkService>()
    private val walletRepo = mock<WalletRepo>()
    private val events = MutableSharedFlow<SdkEvent>()
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, BALANCE, hasSynced = true))
    private val ownInvoices = FakeOwnInvoiceStore()
    private val uncertainSends = FakeUncertainSendStore()
    private val decoder = FakeInputDecoder()
    private val quickPayRepo = mock<QuickPayRepo>()

    private lateinit var sut: PaymentsRepo

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(events)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever { sparkService.identityPubkey() }.thenReturn(IDENTITY)
        whenever { sparkService.getInfo() }.thenReturn(SparkFixtures.info(BALANCE))
        whenever { sparkService.listPayments(anyOrNull()) }.thenReturn(emptyList())
        whenever { sparkService.syncWallet() }.thenReturn(SparkSync(error = null, syncedAtSecs = 1uL))
        whenever(sparkService.sendKey(any(), any())).thenAnswer { "key-${it.arguments[1]}" }
        whenever { sparkService.parse(BOLT11) }.thenReturn(invoiceInput(BOLT11))
        whenever { sparkService.prepareSend(eq(BOLT11), anyOrNull()) }.thenReturn(prepared())
        sut = PaymentsRepo(
            testDispatcher,
            sparkService,
            walletRepo,
            decoder,
            WalletSelfSendGuard(sparkService, ownInvoices),
            ownInvoices,
            uncertainSends,
            quickPayRepo,
        )
    }

    /** A new repository over the same stores, as after the app was closed and opened again. */
    private fun restart() {
        sut = PaymentsRepo(
            testDispatcher,
            sparkService,
            walletRepo,
            decoder,
            WalletSelfSendGuard(sparkService, ownInvoices),
            ownInvoices,
            uncertainSends,
            quickPayRepo,
        )
    }

    // region receive

    @Test
    fun `an invoice is amountless, remembered as the wallet's own and returned with its hash and expiry`() = test {
        whenever(sparkService.receiveBolt11(null, "", PaymentsRepo.INVOICE_EXPIRY_SECS)).thenReturn(BOLT11)

        val invoice = sut.createInvoice().getOrThrow()

        val expiresAt = SparkFixtures.TIMESTAMP + SparkFixtures.EXPIRY
        assertEquals(ReceiveInvoice(BOLT11, PAYMENT_HASH, expiresAt), invoice)
        assertEquals(mapOf(PAYMENT_HASH to expiresAt), ownInvoices.invoices)
        assertFalse(invoice.isExpired(expiresAt - 1uL))
        assertTrue(invoice.isExpired(expiresAt))
    }

    @Test
    fun `no invoice is made unless the wallet is ready`() = test {
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }

            assertIs<ReceiveError.NotReady>(sut.createInvoice().exceptionOrNull(), "$status")
        }
        verify(sparkService, never()).receiveBolt11(anyOrNull(), any(), any())
    }

    @Test
    fun `an invoice that cannot be remembered is not handed out`() = test {
        whenever(sparkService.receiveBolt11(null, "", PaymentsRepo.INVOICE_EXPIRY_SECS)).thenReturn(BOLT11)
        ownInvoices.failure = IllegalStateException("disk")

        assertIs<IllegalStateException>(sut.createInvoice().exceptionOrNull())
    }

    @Test
    fun `an invoice made while the wallet is wiped is forgotten and not handed out`() = test {
        whenever(sparkService.receiveBolt11(null, "", PaymentsRepo.INVOICE_EXPIRY_SECS)).doSuspendableAnswer {
            walletState.update { it.copy(status = WalletStatus.Idle) }
            BOLT11
        }

        assertIs<ReceiveError.NotReady>(sut.createInvoice().exceptionOrNull())
        assertTrue(ownInvoices.invoices.isEmpty())
    }

    @Test
    fun `waiting for an invoice ends with its payment and skips other payments`() = test {
        val paid = async { sut.awaitPaid(invoice()) }

        events.emit(SdkEvent.PaymentSucceeded(receipt("other", paymentHash = "ff".repeat(32))))
        events.emit(SdkEvent.PaymentSucceeded(payment("sent", PaymentStatus.COMPLETED)))
        events.emit(SdkEvent.PaymentPending(receipt("pending", status = PaymentStatus.PENDING)))
        assertFalse(paid.isCompleted)
        events.emit(SdkEvent.PaymentSucceeded(receipt("paid", amountSats = 2_100)))

        val received = paid.await()
        assertEquals("paid", received.id)
        assertEquals(2_100uL, received.amountSats)
        assertFalse(received.isSent)
        assertEquals(PaymentState.Completed, received.state)
    }

    @Test
    fun `waiting for an invoice finds a payment that arrived before the wait`() = test {
        whenever(sparkService.listPayments(anyOrNull())).thenReturn(listOf(receipt("paid")))

        assertEquals("paid", sut.awaitPaid(invoice()).id)
    }

    @Test
    fun `waiting for an invoice reads spark's list again after a sync`() = test {
        val paid = async { sut.awaitPaid(invoice()) }
        whenever(sparkService.listPayments(anyOrNull())).thenReturn(listOf(receipt("paid")))

        events.emit(SdkEvent.Synced)

        assertEquals("paid", paid.await().id)
    }

    // endregion

    // region parse

    @Test
    fun `an invoice is read with its amount, and without one when it is amountless`() = test {
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL, description = "Coffee")
        decoder.inputs["amountless"] = lightning(BOLT11, amountSats = 0uL, description = "")

        assertEquals(SendRequest.Invoice(BOLT11, 500uL, "Coffee"), sut.parse(" lightning:$BOLT11 ").getOrThrow())
        val amountless = sut.parse("amountless").getOrThrow()
        assertEquals(SendRequest.Invoice(BOLT11, null, null), amountless)
        assertNull(amountless.fixedAmountSats)
    }

    @Test
    fun `an expired invoice and an unreadable input are refused when read`() = test {
        decoder.inputs["expired"] = lightning(BOLT11, isExpired = true)

        assertIs<SendError.Expired>(sut.parse("expired").exceptionOrNull())
        assertIs<SendError.Invalid>(sut.parse("nonsense").exceptionOrNull())
    }

    @Test
    fun `an lnurl is read with its range in whole sats, named by its host`() = test {
        decoder.inputs["lnurl1abc"] = Scanner.LnurlPay(lnurl(minMsat = 1_001uL, maxMsat = 5_999uL))

        val request = assertIs<SendRequest.Lnurl>(sut.parse("lnurl1abc").getOrThrow())

        assertEquals(2uL, request.minSats)
        assertEquals(5uL, request.maxSats)
        assertEquals("pay.example", request.payee)
        assertNull(request.fixedAmountSats)
    }

    @Test
    fun `a lightning address is named by its address, and a fixed lnurl amount is fixed`() = test {
        val fixed = Scanner.LnurlPay(lnurl(minMsat = 21_000uL, maxMsat = 21_000uL))
        decoder.inputs["satoshi@pay.example"] = fixed

        val request = sut.parse("lightning:satoshi@pay.example").getOrThrow()

        assertEquals("satoshi@pay.example", request.payee)
        assertEquals(21uL, request.fixedAmountSats)
        assertEquals("satoshi@pay.example", sut.parse("LIGHTNING:SATOSHI@PAY.EXAMPLE").getOrThrow().payee)
    }

    @Test
    fun `an lnurl link loses its scheme, and an uppercase one is read lowercase`() = test {
        decoder.inputs["lnurl1abc"] = Scanner.LnurlPay(lnurl())

        for (link in listOf("lnurl:lnurl1abc", "lnurl:LNURL1ABC", "LIGHTNING:LNURL1ABC", "lnurlp:lnurl1abc")) {
            assertIs<SendRequest.Lnurl>(sut.parse(link).getOrThrow(), link)
        }
        assertEquals(List(4) { "lnurl1abc" }, decoder.decoded)
    }

    @Test
    fun `an lnurlp link is read as its https lnurl-pay url, and an onion one as http`() = test {
        decoder.inputs["https://pay.example/lnurlp/satoshi?tag=1"] = Scanner.LnurlPay(lnurl())
        decoder.inputs["http://payabc.onion/lnurlp/satoshi"] = Scanner.LnurlPay(lnurl())

        val request = assertIs<SendRequest.Lnurl>(sut.parse("lnurlp://pay.example/lnurlp/satoshi?tag=1").getOrThrow())
        assertEquals("pay.example", request.payee)
        assertIs<SendRequest.Lnurl>(sut.parse("LNURLP://payabc.onion/lnurlp/satoshi").getOrThrow())
    }

    @Test
    fun `an uppercase bitcoin uri is paid by its LIGHTNING invoice`() = test {
        val upper = "BITCOIN:BCRT1Q?LIGHTNING=${BOLT11.uppercase()}"
        decoder.inputs["bitcoin:bcrt1q?lightning=$BOLT11"] = onchain(mapOf("lightning" to BOLT11))
        decoder.inputs["bitcoin:bcrt1q?LIGHTNING=${BOLT11.uppercase()}"] =
            onchain(mapOf("LIGHTNING" to BOLT11.uppercase()))
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL)

        assertEquals(SendRequest.Invoice(BOLT11, 500uL, null), sut.parse(upper).getOrThrow())
        assertEquals(
            SendRequest.Invoice(BOLT11, 500uL, null),
            sut.parse("bitcoin:bcrt1q?LIGHTNING=${BOLT11.uppercase()}").getOrThrow(),
        )
    }

    @Test
    fun `an lnurl that takes less than one sat is refused`() = test {
        decoder.inputs["lnurl1abc"] = Scanner.LnurlPay(lnurl(minMsat = 1uL, maxMsat = 999uL))

        assertIs<SendError.Invalid>(sut.parse("lnurl1abc").exceptionOrNull())
    }

    @Test
    fun `a bitcoin uri is paid by its lightning invoice, and refused without one`() = test {
        decoder.inputs["bitcoin:bcrt1q?lightning=$BOLT11"] = onchain(mapOf("lightning" to BOLT11))
        decoder.inputs["bitcoin:bcrt1q"] = onchain(emptyMap())
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL)

        assertEquals(
            SendRequest.Invoice(BOLT11, 500uL, null),
            sut.parse("bitcoin:bcrt1q?lightning=$BOLT11").getOrThrow(),
        )
        assertIs<SendError.Unsupported>(sut.parse("bitcoin:bcrt1q").exceptionOrNull())
    }

    @Test
    fun `a spark address is read by the sdk and never sent to the decoder`() = test {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(OTHER_IDENTITY))

        assertEquals(SendRequest.SparkAddress(SPARK_ADDRESS), sut.parse("spark:$SPARK_ADDRESS").getOrThrow())
        assertEquals(emptyList(), decoder.decoded)
    }

    @Test
    fun `a spark address needs the wallet ready, while an invoice is read without it`() = test {
        walletState.update { it.copy(status = WalletStatus.Connecting) }
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL)

        assertIs<SendError.NotReady>(sut.parse(SPARK_ADDRESS).exceptionOrNull())
        assertTrue(sut.parse(BOLT11).isSuccess)
        verify(sparkService, never()).parse(any())
    }

    @Test
    fun `only a spark address has the shape of one`() {
        assertEquals(SPARK_ADDRESS, PaymentsRepo.sparkAddressOrNull(SPARK_ADDRESS))
        assertEquals(SPARK_ADDRESS, PaymentsRepo.sparkAddressOrNull("SPARK:$SPARK_ADDRESS"))
        assertEquals("sp1pgss", PaymentsRepo.sparkAddressOrNull("sp1pgss"))
        for (other in listOf(BOLT11, "lnurl1abc", "satoshi@pay.example", "bcrt1qabc", "spark1", "sparkle1abc")) {
            assertNull(PaymentsRepo.sparkAddressOrNull(other), other)
        }
    }

    // endregion

    // region self-send guard

    @Test
    fun `this wallet's own spark address is refused when read`() = test {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(IDENTITY))

        assertIs<SendError.OwnInvoice>(sut.parse(SPARK_ADDRESS).exceptionOrNull())
    }

    @Test
    fun `an invoice this watch created is refused when read and never priced`() = test {
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL)
        ownInvoices.add(PAYMENT_HASH, ULong.MAX_VALUE)

        assertIs<SendError.OwnInvoice>(sut.parse(BOLT11).exceptionOrNull())
        assertIs<SendError.OwnInvoice>(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `an invoice read before the wallet was ready is still refused before it is priced`() = test {
        decoder.inputs[BOLT11] = lightning(BOLT11, amountSats = 500uL)
        ownInvoices.add(PAYMENT_HASH, ULong.MAX_VALUE)
        walletState.update { it.copy(status = WalletStatus.Connecting) }
        val request = sut.parse(BOLT11).getOrThrow()
        walletState.update { it.copy(status = WalletStatus.Ready) }

        assertIs<SendError.OwnInvoice>(sut.prepare(request).exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `an invoice whose spark address hint names this wallet is refused before it is priced`() = test {
        val hints = listOf(PROVIDER_HINT, SPARK_ADDRESS_HINT)
        whenever(sparkService.parse(BOLT11)).thenReturn(invoiceInput(BOLT11, hints = hints))

        assertIs<SendError.OwnInvoice>(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `this wallet's own spark address is refused before it is priced`() = test {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(IDENTITY))

        val result = sut.prepare(SendRequest.SparkAddress(SPARK_ADDRESS), amountSats = 500uL)

        assertIs<SendError.OwnInvoice>(result.exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `the invoice an lnurl resolves to is refused when this watch created it`() = test {
        decoder.lnurlInvoices[500_000uL] = LNURL_BOLT11
        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 500uL)
        whenever(sparkService.parse(LNURL_BOLT11)).thenReturn(invoiceInput(LNURL_BOLT11))
        ownInvoices.add(PAYMENT_HASH, ULong.MAX_VALUE)

        val result = sut.prepare(SendRequest.Lnurl(lnurl(), 1uL, 1_000uL, "pay.example"), amountSats = 500uL)

        assertIs<SendError.OwnInvoice>(result.exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `an input the guard cannot check is refused`() = test {
        whenever(sparkService.identityPubkey()).doSuspendableAnswer { throw SparkError.NotConnected() }

        assertIs<SendError.NotReady>(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `another wallet's invoice and spark address pass the guard`() = test {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(OTHER_IDENTITY))
        whenever(sparkService.prepareSend(SPARK_ADDRESS, 500uL)).thenReturn(preparedTransfer())
        ownInvoices.add("ff".repeat(32), ULong.MAX_VALUE)

        assertTrue(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).isSuccess)
        assertTrue(sut.prepare(SendRequest.SparkAddress(SPARK_ADDRESS), amountSats = 500uL).isSuccess)
    }

    // endregion

    // region prepare

    @Test
    fun `nothing is priced unless the wallet is ready`() = test {
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }

            val result = sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null))

            assertIs<SendError.NotReady>(result.exceptionOrNull(), "$status")
        }
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `an invoice with an amount is priced at it, with the lightning fee`() = test {
        val prepared = sut.prepare(SendRequest.Invoice(BOLT11, 500uL, "Coffee"), amountSats = 9uL).getOrThrow()

        verify(sparkService).prepareSend(BOLT11, null)
        assertEquals(500uL, prepared.amountSats)
        assertEquals(FEE, prepared.feeSats)
        assertEquals(504uL, prepared.totalSats)
        assertEquals("Coffee", prepared.payee)
    }

    @Test
    fun `a spark transfer fee applies only where spark is preferred`() = test {
        whenever(sparkService.prepareSend(eq(BOLT11), anyOrNull())).thenReturn(prepared(sparkTransferFeeSats = 0uL))

        assertEquals(FEE, sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).getOrThrow().feeSats)

        whenever(sparkService.prefersSpark).thenReturn(true)
        assertEquals(0uL, sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).getOrThrow().feeSats)
    }

    @Test
    fun `an amountless invoice needs an amount above zero and is priced at it`() = test {
        val request = SendRequest.Invoice(BOLT11, null, null)

        assertIs<SendError.AmountRequired>(sut.prepare(request).exceptionOrNull())
        assertIs<SendError.AmountOutOfRange>(sut.prepare(request, amountSats = 0uL).exceptionOrNull())
        assertEquals(700uL, sut.prepare(request, amountSats = 700uL).getOrThrow().amountSats)
        verify(sparkService).prepareSend(BOLT11, 700uL)
    }

    @Test
    fun `an amount and fee above the balance are refused with the shortfall`() = test {
        val error = sut.prepare(SendRequest.Invoice(BOLT11, BALANCE - FEE + 1uL, null)).exceptionOrNull()

        assertEquals(1uL, assertIs<SendError.InsufficientBalance>(error).shortfallSats)
        assertTrue(sut.prepare(SendRequest.Invoice(BOLT11, BALANCE - FEE, null)).isSuccess)
    }

    @Test
    fun `spark's refusals at pricing are told apart`() = test {
        val request = SendRequest.Invoice(BOLT11, 500uL, null)
        var refusal: Throwable = SdkException.InvalidInput("Invoice has expired")
        whenever(sparkService.prepareSend(eq(BOLT11), anyOrNull())).doSuspendableAnswer { throw refusal }

        assertIs<SendError.Expired>(sut.prepare(request).exceptionOrNull())

        refusal = SdkException.InsufficientFunds(null)
        assertIs<SendError.InsufficientBalance>(sut.prepare(request).exceptionOrNull())

        refusal = SdkException.Generic("boom")
        assertIs<SendError.Failed>(sut.prepare(request).exceptionOrNull())
    }

    @Test
    fun `an lnurl amount outside its range is refused before the service is asked`() = test {
        val request = SendRequest.Lnurl(lnurl(), minSats = 10uL, maxSats = 20uL, payee = null)

        for (amount in listOf(9uL, 21uL)) {
            val error = assertIs<SendError.AmountOutOfRange>(sut.prepare(request, amount).exceptionOrNull())
            assertEquals(10uL to 20uL, error.minSats to error.maxSats)
        }
        assertIs<SendError.AmountRequired>(sut.prepare(request).exceptionOrNull())
        assertEquals(emptyList(), decoder.requestedMsats)
    }

    @Test
    fun `an lnurl is priced by the invoice its service gives for the amount`() = test {
        decoder.lnurlInvoices[15_000uL] = LNURL_BOLT11
        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 15uL)
        whenever(sparkService.parse(LNURL_BOLT11)).thenReturn(invoiceInput(LNURL_BOLT11, "cc".repeat(32)))
        whenever(sparkService.prepareSend(LNURL_BOLT11, null)).thenReturn(prepared(amountMsat = 15_000uL))

        val request = SendRequest.Lnurl(lnurl(), minSats = 10uL, maxSats = 20uL, payee = "satoshi@pay.example")
        val prepared = sut.prepare(request, amountSats = 15uL).getOrThrow()

        assertEquals(15uL, prepared.amountSats)
        assertEquals("satoshi@pay.example", prepared.payee)
    }

    @Test
    fun `an lnurl invoice that asks for millisats above the amount is refused`() = test {
        decoder.lnurlInvoices[15_000uL] = LNURL_BOLT11
        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 15uL)
        whenever(sparkService.parse(LNURL_BOLT11)).thenReturn(invoiceInput(LNURL_BOLT11, "cc".repeat(32)))
        whenever(sparkService.prepareSend(LNURL_BOLT11, null)).thenReturn(prepared(amountMsat = 15_999uL))
        val request = SendRequest.Lnurl(lnurl(), minSats = 10uL, maxSats = 20uL, payee = null)

        assertIs<SendError.Invalid>(sut.prepare(request, amountSats = 15uL).exceptionOrNull())
    }

    @Test
    fun `a wallet that disconnects while a send is priced is not ready, not failed`() = test {
        whenever(sparkService.getInfo()).doSuspendableAnswer { throw SparkError.NotConnected() }

        assertIs<SendError.NotReady>(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).exceptionOrNull())
    }

    @Test
    fun `an lnurl invoice for another amount, or an expired one, is refused`() = test {
        val request = SendRequest.Lnurl(lnurl(), minSats = 10uL, maxSats = 20uL, payee = null)
        decoder.lnurlInvoices[15_000uL] = LNURL_BOLT11

        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 16uL)
        assertIs<SendError.Invalid>(sut.prepare(request, amountSats = 15uL).exceptionOrNull())

        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 15uL, isExpired = true)
        assertIs<SendError.Expired>(sut.prepare(request, amountSats = 15uL).exceptionOrNull())

        assertIs<SendError.Failed>(sut.prepare(request, amountSats = 12uL).exceptionOrNull())
        verify(sparkService, never()).prepareSend(any(), anyOrNull())
    }

    @Test
    fun `quickpay skips the hold for an invoice with an amount, at its amount without the fee`() = test {
        whenever(quickPayRepo.reserve(500uL)).thenReturn(RESERVATION)

        assertTrue(sut.skipConfirmation(preparedSend()) { true })

        verify(quickPayRepo, times(1)).reserve(any())
        verify(quickPayRepo, never()).release(any())
    }

    @Test
    fun `quickpay fetches a missing rate before it reads the limits`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)

        sut.skipConfirmation(preparedSend()) { true }

        val order = inOrder(quickPayRepo)
        order.verify(quickPayRepo).awaitRate()
        order.verify(quickPayRepo).reserve(any())
    }

    @Test
    fun `quickpay holds when the wallet stopped being ready while it waited for the rate`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        whenever(quickPayRepo.awaitRate()).doSuspendableAnswer {
            walletState.update { it.copy(status = WalletStatus.Paused) }
        }

        assertFalse(sut.skipConfirmation(preparedSend()) { true })
    }

    @Test
    fun `quickpay holds when the limits say no`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(null)

        assertFalse(sut.skipConfirmation(preparedSend()) { true })
    }

    @Test
    fun `quickpay holds and counts nothing when the wearer left while the limits were read`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        val prepared = preparedSend()

        assertFalse(sut.skipConfirmation(prepared) { false })

        verify(quickPayRepo).release(RESERVATION)
        // The wearer then holds to pay, and a failure of that send releases nothing a second time.
        whenever(sparkService.send(any(), any())).thenReturn(payment("key-0", PaymentStatus.FAILED))
        sut.send(prepared)
        verify(quickPayRepo, times(1)).release(any())
    }

    @Test
    fun `quickpay never skips the hold for an amount the wearer typed`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        val amountless = sut.prepare(SendRequest.Invoice(BOLT11, null, null), amountSats = 500uL).getOrThrow()
        val range = SendRequest.Lnurl(lnurl(10_000uL, 20_000uL), minSats = 10uL, maxSats = 20uL, payee = null)
        stubLnurlInvoice()
        val typed = sut.prepare(range, amountSats = 15uL).getOrThrow()

        assertFalse(sut.skipConfirmation(amountless) { true })
        assertFalse(sut.skipConfirmation(typed) { true })

        verify(quickPayRepo, never()).awaitRate()
        verify(quickPayRepo, never()).reserve(any())
    }

    @Test
    fun `quickpay skips the hold for an lnurl or a lightning address that takes one amount, as on the phone`() =
        test {
            whenever(quickPayRepo.reserve(15uL)).thenReturn(RESERVATION)
            val fixed = SendRequest.Lnurl(lnurl(15_000uL, 15_000uL), minSats = 15uL, maxSats = 15uL, payee = null)
            stubLnurlInvoice()

            assertTrue(sut.skipConfirmation(sut.prepare(fixed).getOrThrow()) { true })
        }

    @Test
    fun `quickpay holds for an lnurl whose range only rounds to one amount, as on the phone`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        val rounded = SendRequest.Lnurl(lnurl(14_500uL, 15_500uL), minSats = 15uL, maxSats = 15uL, payee = null)
        stubLnurlInvoice()

        assertFalse(sut.skipConfirmation(sut.prepare(rounded).getOrThrow()) { true })

        verify(quickPayRepo, never()).reserve(any())
    }

    @Test
    fun `quickpay never skips the hold for a spark address`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(OTHER_IDENTITY))
        whenever(sparkService.prepareSend(SPARK_ADDRESS, 500uL)).thenReturn(preparedTransfer())
        val transfer = sut.prepare(SendRequest.SparkAddress(SPARK_ADDRESS), amountSats = 500uL).getOrThrow()

        assertFalse(sut.skipConfirmation(transfer) { true })

        verify(quickPayRepo, never()).reserve(any())
    }

    @Test
    fun `quickpay never skips the hold unless the wallet is ready`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        val prepared = preparedSend()
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }

            assertFalse(sut.skipConfirmation(prepared) { true }, "$status")
        }

        verify(quickPayRepo, never()).reserve(any())
    }

    @Test
    fun `quickpay never skips the hold during a wipe or once the wallet stopped being ready meanwhile`() = test {
        val prepared = preparedSend()
        whenever(sparkService.isWiping).thenReturn(true)
        assertFalse(sut.skipConfirmation(prepared) { true })
        verify(quickPayRepo, never()).reserve(any())

        whenever(sparkService.isWiping).thenReturn(false)
        whenever(quickPayRepo.reserve(any())).doSuspendableAnswer {
            walletState.update { it.copy(status = WalletStatus.Locked) }
            RESERVATION
        }
        assertFalse(sut.skipConfirmation(prepared) { true })
        verify(quickPayRepo).release(RESERVATION)
    }

    @Test
    fun `a quickpay send that is paid or still pending stays counted`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.COMPLETED))
        val prepared = preparedSend()
        sut.skipConfirmation(prepared) { true }

        sut.send(prepared).getOrThrow()

        verify(quickPayRepo, never()).release(any())
    }

    @Test
    fun `a quickpay send that failed or was refused is taken out of the day's count, once`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        whenever(sparkService.send(any(), any())).thenReturn(payment("key-0", PaymentStatus.FAILED))
        val prepared = preparedSend()
        sut.skipConfirmation(prepared) { true }

        assertIs<SendError.Failed>(sut.send(prepared).exceptionOrNull())
        assertIs<SendError.Failed>(sut.send(prepared).exceptionOrNull())

        verify(quickPayRepo, times(1)).release(RESERVATION)
    }

    @Test
    fun `a quickpay send of unknown outcome stays counted`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw SdkException.NetworkException("reset") }
        val prepared = preparedSend()
        sut.skipConfirmation(prepared) { true }

        assertIs<SendError.Unknown>(sut.send(prepared).exceptionOrNull())

        verify(quickPayRepo, never()).release(any())
    }

    @Test
    fun `a send that was held for releases nothing when it fails`() = test {
        whenever(sparkService.send(any(), any())).thenReturn(payment("key-0", PaymentStatus.FAILED))

        assertIs<SendError.Failed>(sut.send(preparedSend()).exceptionOrNull())

        verify(quickPayRepo, never()).release(any())
    }

    @Test
    fun `a request the checks refuse never reaches quickpay`() = test {
        whenever(quickPayRepo.reserve(any())).thenReturn(RESERVATION)
        ownInvoices.add(PAYMENT_HASH, SparkFixtures.TIMESTAMP + SparkFixtures.EXPIRY)

        assertIs<SendError.OwnInvoice>(sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).exceptionOrNull())

        verify(quickPayRepo, never()).reserve(any())
    }

    // endregion

    // region send

    @Test
    fun `nothing is sent unless the wallet is ready`() = test {
        val prepared = preparedSend()
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }

            assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull(), "$status")
        }
        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `an invoice is sent under its first key and returns the completed payment`() = test {
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.COMPLETED))

        val sent = sut.send(preparedSend()).getOrThrow()

        verify(sparkService).send(any(), eq("key-0"))
        assertEquals(PaymentState.Completed, sent.state)
        assertTrue(sent.isSent)
        assertEquals(1_000uL, sent.amountSats)
        assertEquals(3uL, sent.feeSats)
    }

    @Test
    fun `an attempt spark lists as failed moves the send to the next key`() = test {
        whenever(sparkService.listPayments(anyOrNull())).thenReturn(
            listOf(payment("key-0", PaymentStatus.FAILED), payment("key-1", PaymentStatus.FAILED)),
        )
        whenever(sparkService.send(any(), eq("key-2"))).thenReturn(payment("key-2", PaymentStatus.COMPLETED))

        assertTrue(sut.send(preparedSend()).isSuccess)
        verify(sparkService).send(any(), eq("key-2"))
    }

    @Test
    fun `an invoice spark lists as paid or pending is not sent again`() = test {
        val paid = listOf(payment("random", PaymentStatus.COMPLETED))
        whenever(sparkService.listPayments(anyOrNull())).thenReturn(paid)
        assertIs<SendError.AlreadyPaid>(sut.send(preparedSend()).exceptionOrNull())

        whenever(sparkService.listPayments(anyOrNull()))
            .thenReturn(listOf(payment("random", PaymentStatus.PENDING, paymentHash = PAYMENT_HASH.uppercase())))
        assertIs<SendError.InFlight>(sut.send(preparedSend()).exceptionOrNull())

        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `a received payment of the same hash does not stop a send`() = test {
        whenever(sparkService.listPayments(anyOrNull())).thenReturn(listOf(receipt("received")))
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.COMPLETED))

        assertTrue(sut.send(preparedSend()).isSuccess)
    }

    @Test
    fun `a payment list that cannot be read refuses the send`() = test {
        whenever(sparkService.listPayments(anyOrNull()))
            .doSuspendableAnswer { throw SdkException.StorageException("locked") }

        assertIs<SendError.NotReady>(sut.send(preparedSend()).exceptionOrNull())
        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `an invoice is sent only after a sync that reached spark, so the list shows the phone's sends`() = test {
        val prepared = preparedSend()
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.COMPLETED))
        // The watch's list lacks the phone's send of the invoice until it syncs.
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            whenever(sparkService.listPayments(anyOrNull()))
                .thenReturn(listOf(payment("phone", PaymentStatus.PENDING)))
            SparkSync(error = null, syncedAtSecs = 2uL)
        }

        assertIs<SendError.InFlight>(sut.send(prepared).exceptionOrNull())
        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `a sync that brings the phone's failed attempt moves the watch to the phone's next key`() = test {
        val prepared = preparedSend()
        whenever(sparkService.send(any(), eq("key-1"))).thenReturn(payment("key-1", PaymentStatus.COMPLETED))
        whenever(sparkService.syncWallet()).doSuspendableAnswer {
            whenever(sparkService.listPayments(anyOrNull())).thenReturn(listOf(payment("key-0", PaymentStatus.FAILED)))
            SparkSync(error = null, syncedAtSecs = 2uL)
        }

        assertTrue(sut.send(prepared).isSuccess)
        verify(sparkService).send(any(), eq("key-1"))
        verify(sparkService, never()).send(any(), eq("key-0"))
    }

    @Test
    fun `an invoice is not sent when the sync does not reach spark, fails or stalls`() = test {
        val prepared = preparedSend()

        var sync: suspend () -> SparkSync = { SparkSync(error = "offline", syncedAtSecs = 1uL) }
        whenever(sparkService.syncWallet()).doSuspendableAnswer { sync() }
        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        sync = { SparkSync(error = null, syncedAtSecs = null) }
        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        sync = { throw SparkError.NotConnected() }
        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        sync = { CompletableDeferred<SparkSync>().await() }
        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())
        assertEquals(PaymentsRepo.SYNC_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)

        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `a key that cannot be derived refuses the send as not ready`() = test {
        val prepared = preparedSend()
        whenever(sparkService.sendKey(any(), any())).thenAnswer { throw SparkError.NotConnected() }

        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())
        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `a send reports the amount of the payment spark returns, also another device's at another amount`() = test {
        whenever(sparkService.send(any(), eq("key-0")))
            .thenReturn(payment("key-0", PaymentStatus.COMPLETED, amountSats = 700))

        assertEquals(700uL, sut.send(preparedSend()).getOrThrow().amountSats)
    }

    @Test
    fun `a pending send completes with the event that arrives while spark is still sending`() = test {
        whenever(sparkService.send(any(), eq("key-0"))).doSuspendableAnswer {
            events.emit(SdkEvent.PaymentSucceeded(payment("key-0", PaymentStatus.COMPLETED)))
            payment("key-0", PaymentStatus.PENDING)
        }

        assertEquals(PaymentState.Completed, sut.send(preparedSend()).getOrThrow().state)
    }

    @Test
    fun `a pending send waits for its own outcome and fails with it`() = test {
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.PENDING))
        val sending = async { sut.send(preparedSend()) }

        events.emit(SdkEvent.PaymentSucceeded(payment("another", PaymentStatus.COMPLETED)))
        assertFalse(sending.isCompleted)
        events.emit(SdkEvent.PaymentFailed(payment("key-0", PaymentStatus.FAILED)))

        assertIs<SendError.Failed>(sending.await().exceptionOrNull())
    }

    @Test
    fun `a send that does not settle in time is reported as pending`() = test {
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.PENDING))

        val sent = sut.send(preparedSend()).getOrThrow()

        assertEquals(PaymentState.Pending, sent.state)
        assertEquals(PaymentsRepo.SETTLE_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `a send spark fails at once is a failure`() = test {
        whenever(sparkService.send(any(), eq("key-0"))).thenReturn(payment("key-0", PaymentStatus.FAILED))

        assertIs<SendError.Failed>(sut.send(preparedSend()).exceptionOrNull())
    }

    @Test
    fun `spark's refusals before anything goes out are told apart`() = test {
        val prepared = preparedSend()
        var refusal: Throwable = SdkException.InvalidInput("invoice has expired")
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw refusal }

        assertIs<SendError.Expired>(sut.send(prepared).exceptionOrNull())

        refusal = SdkException.InsufficientFunds(null)
        assertIs<SendError.InsufficientBalance>(sut.send(prepared).exceptionOrNull())
    }

    @Test
    fun `a send that throws without a trace has an unknown outcome, never a plain failure`() = test {
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw SdkException.NetworkException("reset") }

        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())
    }

    @Test
    fun `after an unknown outcome a refusal under the same key stays unknown, also after a restart`() = test {
        var refusal: Throwable = SdkException.NetworkException("reset")
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw refusal }
        sut.send(preparedSend())
        restart()

        refusal = SdkException.InsufficientFunds(null)
        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())

        refusal = SdkException.InvalidInput("invoice has expired")
        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())
    }

    @Test
    fun `a refusal under a key of a send the app was closed during is unknown, not unsent`() = test {
        // What the store holds when the process dies inside the send call.
        uncertainSends.begin(UncertainSend("key-0", expiresAtSecs = SparkFixtures.TIMESTAMP + SparkFixtures.EXPIRY))
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw SdkException.InsufficientFunds(null) }

        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())
    }

    @Test
    fun `a refusal is answered with the payment spark lists under the key`() = test {
        whenever(sparkService.send(any(), any())).doSuspendableAnswer {
            whenever(sparkService.listPayments(anyOrNull()))
                .thenReturn(listOf(payment("key-0", PaymentStatus.COMPLETED)))
            throw SdkException.InsufficientFunds(null)
        }

        assertEquals(PaymentState.Completed, sut.send(preparedSend()).getOrThrow().state)
    }

    @Test
    fun `a send is noted before it goes out and forgotten once its outcome is known`() = test {
        var notedDuringSend: Set<String>? = null
        whenever(sparkService.send(any(), eq("key-0"))).doSuspendableAnswer {
            notedDuringSend = uncertainSends.sends.keys.toSet()
            payment("key-0", PaymentStatus.COMPLETED)
        }

        sut.send(preparedSend()).getOrThrow()

        assertEquals(setOf("key-0"), notedDuringSend)
        assertTrue(uncertainSends.sends.isEmpty())
    }

    @Test
    fun `a refusal before anything went out is forgotten, and an unknown outcome is kept`() = test {
        var error: Throwable = SdkException.InsufficientFunds(null)
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw error }

        sut.send(preparedSend())
        assertTrue(uncertainSends.sends.isEmpty())

        error = SdkException.NetworkException("reset")
        sut.send(preparedSend())
        assertEquals(
            UncertainSend("key-0", expiresAtSecs = SparkFixtures.TIMESTAMP + SparkFixtures.EXPIRY),
            uncertainSends.sends.values.single(),
        )
    }

    @Test
    fun `a send that cannot be noted is not sent`() = test {
        val prepared = preparedSend()
        uncertainSends.failure = IllegalStateException("disk")

        assertIs<SendError.Failed>(sut.send(prepared).exceptionOrNull())
        verify(sparkService, never()).send(any(), any())
    }

    @Test
    fun `a send call that stalls ends as unknown, and its request is under way until spark returns it`() = test {
        val prepared = preparedSend()
        var answer = CompletableDeferred<Payment>()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { answer.await() }

        assertIs<SendError.Unknown>(sut.send(prepared).exceptionOrNull())
        assertEquals(PaymentsRepo.SEND_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(setOf("key-0"), uncertainSends.sends.keys)

        assertIs<SendError.InFlight>(sut.send(prepared).exceptionOrNull())
        verify(sparkService).send(any(), any())
        assertEquals(setOf("key-0"), uncertainSends.sends.keys)

        val stalled = answer
        answer = CompletableDeferred(payment("key-0", PaymentStatus.COMPLETED))
        stalled.completeExceptionally(SdkException.NetworkException("reset"))
        assertTrue(sut.send(prepared).isSuccess)
    }

    @Test
    fun `a transfer to the payee of a stalled transfer is under way, by either review, and sends nothing`() = test {
        stubTransfer()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { CompletableDeferred<Payment>().await() }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        val first = sut.prepare(request, 500uL).getOrThrow()
        assertIs<SendError.Unknown>(sut.send(first).exceptionOrNull())
        val noted = uncertainSends.sends.keys.toSet()

        assertIs<SendError.InFlight>(sut.send(first).exceptionOrNull())
        assertIs<SendError.InFlight>(sut.send(sut.prepare(request, 500uL).getOrThrow()).exceptionOrNull())
        assertIs<SendError.Busy>(sut.send(preparedSend()).exceptionOrNull())

        verify(sparkService).send(any(), any())
        assertEquals(noted, uncertainSends.sends.keys)
    }

    @Test
    fun `a send goes on and is settled after its caller is cancelled`() = test {
        val release = CompletableDeferred<Payment>()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { release.await() }
        val prepared = preparedSend()
        val caller = launch { sut.send(prepared) }

        caller.cancel()
        assertIs<SendError.Busy>(sut.send(prepared).exceptionOrNull())
        release.complete(payment("key-0", PaymentStatus.COMPLETED))

        assertTrue(uncertainSends.sends.isEmpty())
        whenever(sparkService.send(any(), any())).thenReturn(payment("key-0", PaymentStatus.COMPLETED))
        assertTrue(sut.send(prepared).isSuccess)
    }

    @Test
    fun `a send that throws after spark took it returns the payment spark lists under its key`() = test {
        whenever(sparkService.send(any(), any())).doSuspendableAnswer {
            whenever(sparkService.listPayments(anyOrNull()))
                .thenReturn(listOf(payment("key-0", PaymentStatus.COMPLETED)))
            throw SdkException.NetworkException("reset")
        }

        assertEquals(PaymentState.Completed, sut.send(preparedSend()).getOrThrow().state)
    }

    @Test
    fun `a second send is refused while one runs`() = test {
        val release = CompletableDeferred<Payment>()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { release.await() }
        val first = async { sut.send(preparedSend()) }

        assertIs<SendError.Busy>(sut.send(preparedSend()).exceptionOrNull())

        release.complete(payment("key-0", PaymentStatus.COMPLETED))
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun `while a stalled send call lives, another request is busy, not under way, and leaves no note`() = test {
        val other = "lnbcrt1other"
        val otherHash = "cd".repeat(32)
        whenever(sparkService.parse(other)).thenReturn(invoiceInput(other, otherHash))
        whenever(sparkService.prepareSend(eq(other), anyOrNull())).thenReturn(prepared(paymentHash = otherHash))
        val otherPrepared = sut.prepare(SendRequest.Invoice(other, 500uL, null)).getOrThrow()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { CompletableDeferred<Payment>().await() }
        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())

        assertIs<SendError.Busy>(sut.send(otherPrepared).exceptionOrNull())

        verify(sparkService).send(any(), any())
        assertEquals(setOf("key-0"), uncertainSends.sends.keys)
    }

    @Test
    fun `a wallet that disconnects after the note and before the sdk call sent nothing`() = test {
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw SparkError.NotConnected() }

        assertIs<SendError.NotReady>(sut.send(preparedSend()).exceptionOrNull())

        assertTrue(uncertainSends.sends.isEmpty())
    }

    @Test
    fun `a disconnect before the sdk call keeps the note of an earlier attempt of unknown outcome`() = test {
        var error: Throwable = SdkException.NetworkException("reset")
        whenever(sparkService.send(any(), any())).doSuspendableAnswer { throw error }
        assertIs<SendError.Unknown>(sut.send(preparedSend()).exceptionOrNull())

        error = SparkError.NotConnected()
        assertIs<SendError.NotReady>(sut.send(preparedSend()).exceptionOrNull())

        assertEquals(setOf("key-0"), uncertainSends.sends.keys)
    }

    @Test
    fun `a send holds the connection from its note until spark returns the call`() = test {
        val order = mutableListOf<String>()
        doAnswer { order += "hold:${uncertainSends.sends.size}" }.whenever(sparkService).holdSend()
        doAnswer { order += "release" }.whenever(sparkService).releaseSend()
        whenever(sparkService.send(any(), any())).doSuspendableAnswer {
            order += "send"
            payment("key-0", PaymentStatus.COMPLETED)
        }

        sut.send(preparedSend()).getOrThrow()

        assertEquals(listOf("hold:0", "send", "release"), order)
    }

    @Test
    fun `a send noted while the wallet is wiped is not sent and leaves no note`() = test {
        val prepared = preparedSend()
        var isWiping = false
        whenever(sparkService.isWiping).thenAnswer { isWiping }
        doAnswer { isWiping = true }.whenever(sparkService).holdSend()

        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        verify(sparkService, never()).send(any(), any())
        assertTrue(uncertainSends.sends.isEmpty())
    }

    @Test
    fun `a send noted after the wallet stopped being ready is not sent and leaves no note`() = test {
        val prepared = preparedSend()
        doAnswer { walletState.update { it.copy(status = WalletStatus.Idle) } }.whenever(sparkService).holdSend()

        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        verify(sparkService, never()).send(any(), any())
        assertTrue(uncertainSends.sends.isEmpty())
    }

    @Test
    fun `a spark address is sent under one key of its own, without reading the list`() = test {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(OTHER_IDENTITY))
        whenever(sparkService.prepareSend(SPARK_ADDRESS, 500uL)).thenReturn(preparedTransfer())
        val keys = mutableListOf<String>()
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            payment(keys.last(), PaymentStatus.COMPLETED, paymentHash = null)
        }
        val prepared = sut.prepare(SendRequest.SparkAddress(SPARK_ADDRESS), amountSats = 500uL).getOrThrow()

        sut.send(prepared).getOrThrow()
        sut.send(prepared).getOrThrow()

        assertEquals(2uL, prepared.feeSats)
        assertEquals(1, keys.distinct().size)
        verify(sparkService, never()).sendKey(any(), any())
    }

    @Test
    fun `a spark address prepared again after an unknown outcome is sent under the same key`() = test {
        val keys = stubTransfer()
        var outcome: () -> Payment = { throw SdkException.NetworkException("reset") }
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            outcome()
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        assertIs<SendError.Unknown>(sut.send(sut.prepare(request, 500uL).getOrThrow()).exceptionOrNull())
        restart()

        outcome = { payment(keys.last(), PaymentStatus.COMPLETED, paymentHash = null) }
        sut.send(sut.prepare(request, 500uL).getOrThrow()).getOrThrow()

        assertEquals(2, keys.size)
        assertEquals(1, keys.distinct().size)
        assertTrue(uncertainSends.sends.isEmpty())
    }

    @Test
    fun `a spark address sent by another review after an unknown outcome keeps the first key`() = test {
        val keys = stubTransfer()
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            throw SdkException.NetworkException("reset")
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        val first = sut.prepare(request, 500uL).getOrThrow()
        val second = sut.prepare(request, 500uL).getOrThrow()

        sut.send(first)
        sut.send(second)

        assertEquals(1, keys.distinct().size)
    }

    @Test
    fun `the other spelling of a payee's spark address is sent under the key of its uncertain transfer`() = test {
        val keys = stubTransfer()
        val otherSpelling = "sprt1pgssy6vty7krpze82ecm8j39gd35v35aqjjmhftc4culawsavkyh564u2uv8v0"
        whenever(sparkService.parse(otherSpelling))
            .thenReturn(sparkAddressInput(OTHER_IDENTITY.uppercase(), otherSpelling))
        whenever(sparkService.prepareSend(otherSpelling, 500uL)).thenReturn(preparedTransfer())
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            throw SdkException.NetworkException("reset")
        }
        sut.send(sut.prepare(SendRequest.SparkAddress(SPARK_ADDRESS), 500uL).getOrThrow())

        sut.send(sut.prepare(SendRequest.SparkAddress(otherSpelling), 500uL).getOrThrow())

        assertEquals(2, keys.size)
        assertEquals(1, keys.distinct().size)
        assertEquals(OTHER_IDENTITY, uncertainSends.sends.values.single().sparkPayee)
    }

    @Test
    fun `a failed transfer whose note could not be dropped never hides the uncertain one after it`() = test {
        val keys = stubTransfer()
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            throw SdkException.NetworkException("reset")
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        sut.send(sut.prepare(request, 500uL).getOrThrow())
        val failed = keys.single()
        // The failed transfer's note stays, as after a settings write that failed, beside the next transfer's.
        val uncertain = "00000000-0000-4000-8000-000000000002"
        uncertainSends.sends[uncertain] = UncertainSend(uncertain, sparkPayee = OTHER_IDENTITY)
        whenever(sparkService.listPayments(anyOrNull()))
            .thenReturn(listOf(payment(failed, PaymentStatus.FAILED, paymentHash = null)))

        sut.send(sut.prepare(request, 500uL).getOrThrow())

        assertEquals(listOf(failed, uncertain), keys)
    }

    @Test
    fun `a spark transfer of unknown outcome that spark lists is not sent again`() = test {
        val keys = stubTransfer()
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            throw SdkException.NetworkException("reset")
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        sut.send(sut.prepare(request, 500uL).getOrThrow())
        val key = keys.single()

        whenever(sparkService.listPayments(anyOrNull()))
            .thenReturn(listOf(payment(key, PaymentStatus.PENDING, paymentHash = null)))
        assertIs<SendError.InFlight>(sut.prepare(request, 500uL).exceptionOrNull())

        whenever(sparkService.listPayments(anyOrNull()))
            .thenReturn(listOf(payment(key, PaymentStatus.COMPLETED, paymentHash = null)))
        assertIs<SendError.AlreadyPaid>(sut.prepare(request, 500uL).exceptionOrNull())
        assertEquals(1, keys.size)

        // Told once: the next transfer to the address is a new payment under a new key.
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            payment(keys.last(), PaymentStatus.COMPLETED, paymentHash = null)
        }
        sut.send(sut.prepare(request, 500uL).getOrThrow()).getOrThrow()
        assertEquals(2, keys.distinct().size)
    }

    @Test
    fun `a spark transfer of unknown outcome that spark lists failed is sent under a new key`() = test {
        val keys = stubTransfer()
        var outcome: () -> Payment = { throw SdkException.NetworkException("reset") }
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            outcome()
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        sut.send(sut.prepare(request, 500uL).getOrThrow())
        whenever(sparkService.listPayments(anyOrNull()))
            .thenReturn(listOf(payment(keys.single(), PaymentStatus.FAILED, paymentHash = null)))

        outcome = { payment(keys.last(), PaymentStatus.COMPLETED, paymentHash = null) }
        sut.send(sut.prepare(request, 500uL).getOrThrow()).getOrThrow()

        assertEquals(2, keys.distinct().size)
    }

    @Test
    fun `a spark transfer of unknown outcome is not sent again while spark cannot be asked`() = test {
        val keys = stubTransfer()
        whenever(sparkService.send(any(), any())).thenAnswer {
            keys += it.arguments[1] as String
            throw SdkException.NetworkException("reset")
        }
        val request = SendRequest.SparkAddress(SPARK_ADDRESS)
        val prepared = sut.prepare(request, 500uL).getOrThrow()
        sut.send(prepared)

        whenever(sparkService.syncWallet()).thenReturn(SparkSync(error = "offline", syncedAtSecs = 1uL))
        assertIs<SendError.NotReady>(sut.prepare(request, 500uL).exceptionOrNull())
        assertIs<SendError.NotReady>(sut.send(prepared).exceptionOrNull())

        whenever(sparkService.syncWallet()).thenReturn(SparkSync(error = null, syncedAtSecs = 2uL))
        uncertainSends.failure = IllegalStateException("disk")
        assertIs<SendError.Failed>(sut.prepare(request, 500uL).exceptionOrNull())
        assertEquals(1, keys.size)
    }

    // endregion

    @Test
    fun `recent payments are the newest of spark's list, as the activity list shows them`() = test {
        whenever(sparkService.listPayments(20u)).thenReturn(
            listOf(payment("sent", PaymentStatus.PENDING), receipt("received", amountSats = 77)),
        )

        val payments = sut.recentPayments().getOrThrow()

        assertEquals(listOf("sent", "received"), payments.map { it.id })
        assertEquals(listOf(true, false), payments.map { it.isSent })
        assertEquals(listOf(PaymentState.Pending, PaymentState.Completed), payments.map { it.state })
        assertEquals(77uL, payments[1].amountSats)
        assertEquals(SparkFixtures.TIMESTAMP, payments[0].timestampSecs)
    }

    @Test
    fun `recent payments fail while spark is not connected`() = test {
        whenever(sparkService.listPayments(anyOrNull())).doSuspendableAnswer { throw SparkError.NotConnected() }

        assertIs<SparkError.NotConnected>(sut.recentPayments().exceptionOrNull())
    }

    @Test
    fun `every spark event tells the activity list to read again`() = test {
        var changes = 0
        val collecting = launch { sut.paymentChanges.collect { changes++ } }

        events.emit(SdkEvent.Synced)
        events.emit(SdkEvent.PaymentSucceeded(receipt("paid")))

        assertEquals(2, changes)
        collecting.cancel()
    }

    private suspend fun stubLnurlInvoice() {
        decoder.lnurlInvoices[15_000uL] = LNURL_BOLT11
        decoder.inputs[LNURL_BOLT11] = lightning(LNURL_BOLT11, amountSats = 15uL)
        whenever(sparkService.parse(LNURL_BOLT11)).thenReturn(invoiceInput(LNURL_BOLT11, "cc".repeat(32)))
        whenever(sparkService.prepareSend(LNURL_BOLT11, null)).thenReturn(prepared(amountMsat = 15_000uL))
    }

    private suspend fun preparedSend(): PreparedSend =
        sut.prepare(SendRequest.Invoice(BOLT11, 500uL, null)).getOrThrow()

    private fun invoice() = ReceiveInvoice(BOLT11, PAYMENT_HASH, SparkFixtures.TIMESTAMP + SparkFixtures.EXPIRY)

    private fun receipt(
        id: String,
        status: PaymentStatus = PaymentStatus.COMPLETED,
        paymentHash: String = PAYMENT_HASH,
        amountSats: Long = 1_000,
    ) = payment(id, status, PaymentType.RECEIVE, paymentHash, amountSats, feeSats = 0)

    /** A Spark address of another wallet that Spark prices; returns the list the test notes its send keys in. */
    private suspend fun stubTransfer(): MutableList<String> {
        whenever(sparkService.parse(SPARK_ADDRESS)).thenReturn(sparkAddressInput(OTHER_IDENTITY))
        whenever(sparkService.prepareSend(SPARK_ADDRESS, 500uL)).thenReturn(preparedTransfer())
        return mutableListOf()
    }

    private fun prepared(
        sparkTransferFeeSats: ULong? = null,
        amountMsat: ULong? = null,
        paymentHash: String = PAYMENT_HASH,
    ) = PrepareSendPaymentResponse(
        paymentMethod = SendPaymentMethod.Bolt11Invoice(
            SparkFixtures.invoice(BOLT11, paymentHash, amountMsat = amountMsat),
            sparkTransferFeeSats,
            FEE,
        ),
        amount = BigInteger.valueOf(500),
        tokenIdentifier = null,
        conversionEstimate = null,
        feePolicy = FeePolicy.FEES_EXCLUDED,
    )

    private fun preparedTransfer() = PrepareSendPaymentResponse(
        paymentMethod = SendPaymentMethod.SparkAddress(SPARK_ADDRESS, BigInteger.valueOf(2), null),
        amount = BigInteger.valueOf(500),
        tokenIdentifier = null,
        conversionEstimate = null,
        feePolicy = FeePolicy.FEES_EXCLUDED,
    )

    private fun lightning(
        bolt11: String,
        amountSats: ULong = 500uL,
        description: String? = null,
        isExpired: Boolean = false,
    ) = Scanner.Lightning(
        LightningInvoice(
            bolt11 = bolt11,
            paymentHash = PAYMENT_HASH.hexToByteArray(),
            amountSatoshis = amountSats,
            timestampSeconds = SparkFixtures.TIMESTAMP,
            expirySeconds = SparkFixtures.EXPIRY,
            isExpired = isExpired,
            description = description,
            networkType = NetworkType.REGTEST,
            payeeNodeId = null,
        ),
    )

    private fun lnurl(minMsat: ULong = 1_000uL, maxMsat: ULong = 1_000_000uL) = LnurlPayData(
        uri = "lnurl1abc",
        callback = "https://pay.example/lnurlp/satoshi/callback",
        minSendable = minMsat,
        maxSendable = maxMsat,
        metadataStr = "[]",
        commentAllowed = null,
        allowsNostr = false,
        nostrPubkey = null,
    )

    private fun onchain(params: Map<String, String>) =
        Scanner.OnChain(OnChainInvoice("bcrt1q", amountSatoshis = 0uL, label = null, message = null, params = params))
}

/** An [InputDecoder] over fixed answers; an input or an amount it has none for fails, as an unreadable one does. */
private class FakeInputDecoder : InputDecoder {
    val inputs = mutableMapOf<String, Scanner>()
    val lnurlInvoices = mutableMapOf<ULong, String>()
    val decoded = mutableListOf<String>()
    val requestedMsats = mutableListOf<ULong>()

    override suspend fun decode(input: String): Scanner {
        decoded += input
        return inputs[input] ?: error("unreadable")
    }

    override suspend fun lnurlInvoice(data: LnurlPayData, amountMsats: ULong): String {
        requestedMsats += amountMsats
        return lnurlInvoices[amountMsats] ?: error("no invoice")
    }
}
