package to.bitkit.wear.repositories

import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendPaymentMethod
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.Scanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.OwnInvoiceStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.PreparedSend
import to.bitkit.wear.models.ReceiveError
import to.bitkit.wear.models.ReceiveInvoice
import to.bitkit.wear.models.SendError
import to.bitkit.wear.models.SendRequest
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.services.InputDecoder
import to.bitkit.wear.services.SelfSendGuard
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.Logger
import java.net.URI
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Receiving and sending with the watch's Spark wallet. Everything that reaches Spark needs the wallet at
 * [WalletStatus.Ready]: connected and synced in this session. Nothing here connects the wallet; `WalletRepo` does.
 */
@Singleton
class PaymentsRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val sparkService: WatchSparkService,
    private val walletRepo: WalletRepo,
    private val inputDecoder: InputDecoder,
    private val selfSendGuard: SelfSendGuard,
    private val ownInvoiceStore: OwnInvoiceStore,
) {
    companion object {
        private const val TAG = "PaymentsRepo"
        private const val MSATS_PER_SAT = 1_000uL

        /** How long a receive invoice lives. The receive screen makes a new one when it expires. */
        const val INVOICE_EXPIRY_SECS = 3_600u

        /** How many payments the activity list shows. */
        const val RECENT_PAYMENTS_LIMIT = 20

        /** How long [send] waits for a payment that Spark took to settle, before it reports it as pending. */
        val SETTLE_TIMEOUT = 60.seconds

        /** Every bitcoin there will be, in sats: no amount is above it. */
        private const val MAX_SATS = 2_100_000_000_000_000uL

        private val SPARK_ADDRESS = Regex("^(spark|sp)(rt|t|s|l)?1[02-9ac-hj-np-z]+$", RegexOption.IGNORE_CASE)
        private const val SPARK_SCHEME = "spark:"

        /** [input] without its `spark:` scheme when it has the shape of a Spark address, or null. */
        internal fun sparkAddressOrNull(input: String): String? {
            val text = if (input.startsWith(SPARK_SCHEME, ignoreCase = true)) input.drop(SPARK_SCHEME.length) else input
            return text.takeIf { SPARK_ADDRESS.matches(it) }
        }
    }

    private val sendMutex = Mutex()

    /** Keys of sends whose outcome was not learned, so a later refusal under the same key is not read as unsent. */
    private val uncertainKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Emits whenever Spark reports a payment or a sync, so the activity list reads [recentPayments] again. */
    val paymentChanges: Flow<Unit> get() = sparkService.events.map { }

    /**
     * Whether a send goes out without the hold to pay (QuickPay). Always false until the watch has QuickPay settings
     * (PR 3), which check [prepared] against the wearer's limit here and nowhere else.
     */
    @Suppress("FunctionOnlyReturningConstant", "UNUSED_PARAMETER")
    fun skipConfirmation(prepared: PreparedSend): Boolean = false

    /**
     * Creates an amountless BOLT11 invoice paid into the Spark balance, valid for [INVOICE_EXPIRY_SECS], and
     * remembers it as this wallet's own, which the self-send guard refuses to pay. An invoice that cannot be
     * remembered is not handed out.
     */
    suspend fun createInvoice(): Result<ReceiveInvoice> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!isReady()) throw ReceiveError.NotReady()
            val bolt11 = sparkService.receiveBolt11(amountSats = null, description = "", INVOICE_EXPIRY_SECS)
            val details = (sparkService.parse(bolt11) as InputType.Bolt11Invoice).v1
            // Remembered before it is shown, so the watch never pays an invoice it handed out.
            ownInvoiceStore.add(details.paymentHash, details.expiresAt)
            ReceiveInvoice(bolt11, details.paymentHash, details.expiresAt)
        }.onFailure {
            Logger.error("Failed to create an invoice", it, context = TAG)
        }
    }

    /**
     * Waits until [invoice] is paid and returns the payment. It never ends by itself otherwise: the caller cancels
     * it when the invoice expires or the screen closes. A payment that arrived before the call, or while the wallet
     * was disconnected, is found in Spark's list, which is read at the start and after every sync.
     */
    suspend fun awaitPaid(invoice: ReceiveInvoice): WatchPayment = withContext(ioDispatcher) {
        channelFlow {
            launch(start = CoroutineStart.UNDISPATCHED) {
                sparkService.events.collect { event ->
                    val paid = event.payment()?.takeIf { it.isReceiptOf(invoice) }
                        ?: if (event is SdkEvent.Synced) findReceipt(invoice) else null
                    paid?.let { send(it) }
                }
            }
            findReceipt(invoice)?.let { send(it) }
        }.first().toWatchPayment()
    }

    private suspend fun findReceipt(invoice: ReceiveInvoice): Payment? =
        runSuspendCatching { sparkService.listPayments().firstOrNull { it.isReceiptOf(invoice) } }.getOrNull()

    /**
     * Reads a payment input: a BOLT11 invoice, an LNURL-pay link, a Lightning address, a BIP21 URI with a
     * `lightning` parameter or a Spark address, each with or without its URI scheme. An LNURL or a Lightning address
     * is fetched from its host. Fails with a [SendError]: [SendError.Invalid], [SendError.Unsupported],
     * [SendError.Expired], and [SendError.OwnInvoice] when the wallet is ready to tell. A Spark address needs the
     * wallet ready ([SendError.NotReady]); the other inputs are read without it, and [prepare] checks them again.
     */
    suspend fun parse(input: String): Result<SendRequest> = withContext(ioDispatcher) {
        runSuspendCatching {
            val text = input.trim()
            val sparkAddress = sparkAddressOrNull(text)
            val request = if (sparkAddress != null) SendRequest.SparkAddress(sparkAddress) else decode(text)
            when (request) {
                is SendRequest.Invoice -> if (isReady()) ensureNotOwn(request.bolt11)
                is SendRequest.SparkAddress -> {
                    if (!isReady()) throw SendError.NotReady()
                    ensureNotOwn(request.address)
                }
                is SendRequest.Lnurl -> Unit
            }
            request
        }.onFailure {
            Logger.warn("Failed to read a payment input", it, context = TAG)
        }
    }

    private suspend fun decode(text: String): SendRequest {
        val scanned = runSuspendCatching { inputDecoder.decode(text) }.getOrElse { throw SendError.Invalid(it) }
        return when (scanned) {
            is Scanner.Lightning -> scanned.invoice.toRequest()
            is Scanner.LnurlPay -> scanned.data.toRequest(text)
            is Scanner.OnChain -> {
                val bolt11 = scanned.invoice.params?.get("lightning") ?: throw SendError.Unsupported()
                val invoice = runSuspendCatching { inputDecoder.decode(bolt11) as? Scanner.Lightning }
                    .getOrElse { throw SendError.Invalid(it) }
                invoice?.invoice?.toRequest() ?: throw SendError.Unsupported()
            }
            else -> throw SendError.Unsupported()
        }
    }

    private fun LightningInvoice.toRequest(): SendRequest.Invoice {
        if (isExpired) throw SendError.Expired()
        return SendRequest.Invoice(bolt11, amountSatoshis.takeIf { it > 0uL }, description?.takeIf { it.isNotBlank() })
    }

    private fun LnurlPayData.toRequest(input: String): SendRequest.Lnurl {
        val minSats = (minSendable + MSATS_PER_SAT - 1uL) / MSATS_PER_SAT
        val maxSats = maxSendable / MSATS_PER_SAT
        if (maxSats == 0uL || minSats > maxSats) throw SendError.Invalid()
        val payee = lnurlPayee(input, this)
        return SendRequest.Lnurl(this, minSats.coerceAtLeast(1uL), maxSats.coerceAtMost(MAX_SATS), payee)
    }

    /** The Lightning address when [input] is one, or else the host of the LNURL callback. */
    private fun lnurlPayee(input: String, data: LnurlPayData): String? {
        val address = input.substringAfter(':').takeIf { '@' in it && !it.contains('/') }
        return address ?: runCatching { URI(data.callback).host }.getOrNull()
    }

    /**
     * Prices a send of [request] and runs every check that comes before the hold to pay. [amountSats] is needed when
     * the request fixes no amount ([SendRequest.fixedAmountSats] is null) and ignored when it does. For an LNURL
     * request it fetches the invoice of that amount from the service. Fails with a [SendError]:
     * [SendError.NotReady], [SendError.AmountRequired], [SendError.AmountOutOfRange], [SendError.Expired],
     * [SendError.OwnInvoice], [SendError.InsufficientBalance], [SendError.Invalid] when an LNURL service answers with
     * an invoice of another amount, and [SendError.Failed] when Spark cannot price it. Nothing is paid here.
     */
    suspend fun prepare(request: SendRequest, amountSats: ULong? = null): Result<PreparedSend> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                if (!isReady()) throw SendError.NotReady()
                val amount = request.fixedAmountSats ?: amountSats ?: throw SendError.AmountRequired()
                when (request) {
                    is SendRequest.Invoice -> {
                        ensureAmount(amount, 1uL, MAX_SATS)
                        prepare(request.bolt11, amount, isAmountInRequest = request.amountSats != null, request.payee)
                    }
                    is SendRequest.Lnurl -> {
                        ensureAmount(amount, request.minSats, request.maxSats)
                        prepare(lnurlInvoice(request, amount), amount, isAmountInRequest = true, request.payee)
                    }
                    is SendRequest.SparkAddress -> {
                        ensureAmount(amount, 1uL, MAX_SATS)
                        prepare(request.address, amount, isAmountInRequest = false, request.payee)
                    }
                }
            }.onFailure {
                Logger.warn("Failed to prepare a send", it, context = TAG)
            }
        }

    private fun ensureAmount(amountSats: ULong, minSats: ULong, maxSats: ULong) {
        if (amountSats < minSats || amountSats > maxSats) throw SendError.AmountOutOfRange(minSats, maxSats)
    }

    /** The invoice the LNURL service gives for [amountSats], refused unless it is for exactly that amount. */
    private suspend fun lnurlInvoice(request: SendRequest.Lnurl, amountSats: ULong): String {
        val bolt11 = runSuspendCatching { inputDecoder.lnurlInvoice(request.data, amountSats * MSATS_PER_SAT) }
            .getOrElse { throw SendError.Failed(it) }
        val invoice = runSuspendCatching { (inputDecoder.decode(bolt11) as Scanner.Lightning).invoice }
            .getOrElse { throw SendError.Invalid(it) }
        if (invoice.amountSatoshis != amountSats) throw SendError.Invalid()
        if (invoice.isExpired) throw SendError.Expired()
        return bolt11
    }

    private suspend fun prepare(
        paymentRequest: String,
        amountSats: ULong,
        isAmountInRequest: Boolean,
        payee: String?,
    ): PreparedSend {
        ensureNotOwn(paymentRequest)
        val response = runSuspendCatching {
            sparkService.prepareSend(paymentRequest, amountSats.takeIf { !isAmountInRequest })
        }.getOrElse { throw it.toSendError() }
        val (feeSats, paymentHash) = when (val method = response.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> method.feeSats() to method.invoiceDetails.paymentHash
            is SendPaymentMethod.SparkAddress -> method.fee.toLong().toULong() to null
            else -> throw SendError.Unsupported()
        }
        val balanceSats = sparkService.getInfo().balanceSats
        val totalSats = amountSats + feeSats
        if (totalSats > balanceSats) throw SendError.InsufficientBalance(totalSats - balanceSats)
        val transferKey = if (paymentHash == null) UUID.randomUUID().toString() else null
        return PreparedSend(amountSats, feeSats, payee, response, paymentHash, transferKey)
    }

    /**
     * Refuses [paymentRequest], a BOLT11 invoice or a Spark address, when [SelfSendGuard] says it pays this wallet.
     * A request the SDK cannot read, or a guard that cannot tell, refuses it too.
     */
    private suspend fun ensureNotOwn(paymentRequest: String) {
        val input = runSuspendCatching { sparkService.parse(paymentRequest) }.getOrElse { throw SendError.Invalid(it) }
        if (input !is InputType.Bolt11Invoice && input !is InputType.SparkAddress) throw SendError.Invalid()
        val isOwn = runSuspendCatching { selfSendGuard.isOwn(input) }.getOrElse { throw SendError.NotReady() }
        if (isOwn) throw SendError.OwnInvoice()
    }

    private fun SendPaymentMethod.Bolt11Invoice.feeSats(): ULong =
        sparkTransferFeeSats?.takeIf { sparkService.prefersSpark } ?: lightningFeeSats

    /**
     * Pays [prepared] and returns the payment: [PaymentState.Completed], or [PaymentState.Pending] when Spark took
     * it and it did not settle within [SETTLE_TIMEOUT]. Once Spark has the send, the caller cannot cancel it. Fails
     * with a [SendError]: [SendError.NotReady], [SendError.AlreadyPaid], [SendError.InFlight] (also while another
     * send runs), [SendError.Expired], [SendError.InsufficientBalance], [SendError.Failed], and [SendError.Unknown]
     * when the payment may have gone out.
     *
     * An invoice is sent under the idempotency key the phone derives for it too, so Spark pays it once however often
     * and from whichever device it is sent; each attempt Spark lists as failed moves to the next key.
     */
    suspend fun send(prepared: PreparedSend): Result<WatchPayment> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!sendMutex.tryLock()) throw SendError.InFlight()
            try {
                if (!isReady()) throw SendError.NotReady()
                val key = prepared.transferKey ?: invoiceKey(checkNotNull(prepared.paymentHash))
                sendWatched(prepared.response, key).toWatchPayment()
            } finally {
                sendMutex.unlock()
            }
        }.onFailure {
            Logger.error("Failed to send a payment", it, context = TAG)
        }
    }

    /**
     * The key of the next attempt at the invoice with [paymentHash], after Spark's list shows that no earlier send of
     * it completed or is still pending. A list that cannot be read refuses the send.
     */
    private suspend fun invoiceKey(paymentHash: String): String {
        val listed = runSuspendCatching { sparkService.listPayments() }.getOrElse { throw SendError.NotReady() }
        val states = listed.filter { it.sendHash().equals(paymentHash, ignoreCase = true) }.map { it.status }
        if (PaymentStatus.COMPLETED in states) throw SendError.AlreadyPaid()
        if (PaymentStatus.PENDING in states) throw SendError.InFlight()
        val failed = listed.filter { it.status == PaymentStatus.FAILED }.mapTo(HashSet()) { it.id }
        var attempt = 0
        while (sparkService.sendKey(paymentHash, attempt) in failed) attempt++
        return sparkService.sendKey(paymentHash, attempt)
    }

    /** Sends under [key] while already listening for the payment's outcome, which can arrive before the call ends. */
    private suspend fun sendWatched(response: PrepareSendPaymentResponse, key: String): Payment = coroutineScope {
        val settled = MutableStateFlow<Map<String, Payment>>(emptyMap())
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sparkService.events.mapNotNull { it.payment() }
                .filter { it.status != PaymentStatus.PENDING }
                .collect { payment -> settled.update { it + (payment.id to payment) } }
        }
        try {
            val sent = runSuspendCatching { sparkService.send(response, key) }.getOrElse { recoverSend(it, key) }
            val payment = if (sent.status == PaymentStatus.PENDING) {
                withTimeoutOrNull(SETTLE_TIMEOUT) { settled.first { sent.id in it }.getValue(sent.id) } ?: sent
            } else {
                sent
            }
            if (payment.status == PaymentStatus.FAILED) throw SendError.Failed()
            uncertainKeys.remove(key)
            Logger.info("Sent payment '${payment.id}' with status '${payment.status}'", context = TAG)
            payment
        } finally {
            watcher.cancel()
        }
    }

    /**
     * What a send that threw under [key] comes to. Spark refuses an expired invoice and a short balance before
     * anything goes out. Any other error can follow a transfer Spark committed, so Spark's list decides: a payment
     * it shows under the key is this send, and a send it does not show is [SendError.Unknown].
     */
    private suspend fun recoverSend(error: Throwable, key: String): Payment {
        val isRefusal = error.isInvoiceExpired() || error is SdkException.InsufficientFunds
        if (isRefusal && key !in uncertainKeys) throw error.toSendError()
        val listed = runSuspendCatching { sparkService.listPayments().firstOrNull { it.id == key } }.getOrNull()
        if (listed == null) {
            uncertainKeys.add(key)
            throw SendError.Unknown(error)
        }
        return listed
    }

    /** The wallet's newest payments, at most [limit], from Spark's local list. It needs Spark connected. */
    suspend fun recentPayments(limit: Int = RECENT_PAYMENTS_LIMIT): Result<List<WatchPayment>> =
        withContext(ioDispatcher) {
            runSuspendCatching { sparkService.listPayments(limit.toUInt()).map { it.toWatchPayment() } }
                .onFailure { Logger.warn("Failed to list payments", it, context = TAG) }
        }

    private fun isReady(): Boolean = walletRepo.walletState.value.status == WalletStatus.Ready
}

private val Bolt11InvoiceDetails.expiresAt: ULong get() = timestamp + expiry

private fun SdkEvent.payment(): Payment? = when (this) {
    is SdkEvent.PaymentSucceeded -> payment
    is SdkEvent.PaymentPending -> payment
    is SdkEvent.PaymentFailed -> payment
    else -> null
}

private fun Payment.lightningHash(): String? = (details as? PaymentDetails.Lightning)?.htlcDetails?.paymentHash

private fun Payment.sendHash(): String? = lightningHash()?.takeIf { paymentType == PaymentType.SEND }

private fun Payment.isReceiptOf(invoice: ReceiveInvoice): Boolean =
    paymentType == PaymentType.RECEIVE && status == PaymentStatus.COMPLETED &&
        lightningHash().equals(invoice.paymentHash, ignoreCase = true)

private fun Payment.toWatchPayment() = WatchPayment(
    id = id,
    isSent = paymentType == PaymentType.SEND,
    amountSats = amount.toLong().toULong(),
    feeSats = fees.toLong().toULong(),
    timestampSecs = timestamp,
    state = when (status) {
        PaymentStatus.COMPLETED -> PaymentState.Completed
        PaymentStatus.PENDING -> PaymentState.Pending
        PaymentStatus.FAILED -> PaymentState.Failed
    },
)

/** Spark refuses an expired Lightning invoice with this input error, at prepare and again at send. */
private fun Throwable.isInvoiceExpired(): Boolean =
    this is SdkException.InvalidInput && v1.contains("invoice has expired", ignoreCase = true)

private fun Throwable.toSendError(): SendError = when {
    this is SendError -> this
    isInvoiceExpired() -> SendError.Expired(this)
    this is SdkException.InsufficientFunds -> SendError.InsufficientBalance(null)
    else -> SendError.Failed(this)
}
