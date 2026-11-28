package to.bitkit.wear.repositories

import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendPaymentMethod
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.Scanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import to.bitkit.wear.data.UncertainSend
import to.bitkit.wear.data.UncertainSendStore
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
    private val uncertainSendStore: UncertainSendStore,
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

        /** How long [send] waits for the sync that brings Spark's list up to date before it picks a key. */
        val SYNC_TIMEOUT = 20.seconds

        /** How long [send] waits for Spark to take a payment, before it reports its outcome as unknown. */
        val SEND_TIMEOUT = 60.seconds

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

    /** Sends run here, so the screen that started one cannot cancel it by closing. */
    private val sendScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** The SDK's send call of the last send, which can outlive it when it stalls. Guarded by [sendMutex]. */
    private var sendCall: Job? = null

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
            // A wipe that began meanwhile clears the store before or after the add, so the wiped wallet's invoice
            // is taken out again and never shown.
            if (!isReady() || sparkService.isWiping) {
                runSuspendCatching { ownInvoiceStore.remove(details.paymentHash) }
                    .onFailure { Logger.warn("Failed to forget an invoice made during a wipe", it, context = TAG) }
                throw ReceiveError.NotReady()
            }
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
     * an invoice of another amount, [SendError.AlreadyPaid] and [SendError.InFlight] for a Spark address whose earlier
     * transfer of unknown outcome Spark now lists, and [SendError.Failed] when Spark cannot price it. Nothing is paid
     * here.
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
                        prepare(
                            paymentRequest = lnurlInvoice(request, amount),
                            amountSats = amount,
                            isAmountInRequest = true,
                            payee = request.payee,
                            exactMsats = amount * MSATS_PER_SAT,
                        )
                    }
                    is SendRequest.SparkAddress -> {
                        ensureAmount(amount, 1uL, MAX_SATS)
                        // Told before the review, so the wearer never holds to pay a transfer that already went out.
                        earlierTransferKey(request.address)
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
        exactMsats: ULong? = null,
    ): PreparedSend {
        ensureNotOwn(paymentRequest)
        val response = runSuspendCatching {
            sparkService.prepareSend(paymentRequest, amountSats.takeIf { !isAmountInRequest })
        }.getOrElse { throw it.toSendError() }
        val method = response.paymentMethod
        val (feeSats, invoice) = when (method) {
            is SendPaymentMethod.Bolt11Invoice -> method.feeSats() to method.invoiceDetails
            is SendPaymentMethod.SparkAddress -> method.fee.toLong().toULong() to null
            else -> throw SendError.Unsupported()
        }
        // Spark pays what the invoice asks to the millisat, which whole sats can hide by up to 999 msat.
        if (exactMsats != null && invoice?.amountMsat != exactMsats) throw SendError.Invalid()
        val balanceSats = runSuspendCatching { sparkService.getInfo().balanceSats }
            .getOrElse { throw SendError.NotReady() }
        val totalSats = amountSats + feeSats
        if (totalSats > balanceSats) throw SendError.InsufficientBalance(totalSats - balanceSats)
        return PreparedSend(
            amountSats = amountSats,
            feeSats = feeSats,
            payee = payee,
            response = response,
            paymentHash = invoice?.paymentHash,
            expiresAtSecs = invoice?.expiresAt,
            sparkAddress = paymentRequest.takeIf { invoice == null },
            transferKey = UUID.randomUUID().toString(),
        )
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
     * Pays [prepared] and returns the payment as Spark made it: [PaymentState.Completed], or [PaymentState.Pending]
     * when Spark took it and it did not settle within [SETTLE_TIMEOUT]. The send does not run in the caller's scope:
     * a caller that goes away neither cancels it nor loses its bookkeeping. Fails with a [SendError]:
     * [SendError.NotReady], also when Spark cannot be reached to bring its list up to date, [SendError.AlreadyPaid],
     * [SendError.InFlight] (also while another send runs, or Spark still holds the call of one that stalled),
     * [SendError.Expired], [SendError.InsufficientBalance], [SendError.Failed], and [SendError.Unknown] when the
     * payment may have gone out.
     *
     * An invoice is sent under the idempotency key the phone derives for it too, so Spark pays it once however often
     * and from whichever device it is sent; each attempt Spark lists as failed moves to the next key. A Spark address
     * has no such key, so the key of a transfer to it is kept in [UncertainSendStore] until its outcome is known, and
     * a retry goes out under the same key. Every send is noted there before it goes out, so a refusal that follows an
     * attempt of unknown outcome, also one the app was closed during, is never read as nothing sent.
     */
    suspend fun send(prepared: PreparedSend): Result<WatchPayment> = sendScope.async {
        runSuspendCatching {
            if (!sendMutex.tryLock()) throw SendError.InFlight()
            try {
                if (sendCall?.isActive == true) throw SendError.InFlight()
                if (!isReady()) throw SendError.NotReady()
                val address = prepared.sparkAddress
                val key = if (address != null) {
                    earlierTransferKey(address) ?: prepared.transferKey
                } else {
                    syncForSend()
                    invoiceKey(checkNotNull(prepared.paymentHash))
                }
                sendWatched(prepared, key).toWatchPayment()
            } finally {
                sendMutex.unlock()
            }
        }.onFailure {
            Logger.error("Failed to send a payment", it, context = TAG)
        }
    }.await()

    /**
     * Syncs with Spark, so its list shows what the phone sent from the same wallet as far as Spark's operators tell.
     * A sync that does not reach Spark in [SYNC_TIMEOUT] refuses the send.
     */
    private suspend fun syncForSend() {
        val sync = withTimeoutOrNull(SYNC_TIMEOUT) { runSuspendCatching { sparkService.syncWallet() }.getOrNull() }
        if (sync?.hasReachedSpark != true) throw SendError.NotReady()
    }

    /**
     * The key of the next attempt at the invoice with [paymentHash], after Spark's list shows that no earlier send of
     * it completed or is still pending. A list that cannot be read, or a key that cannot be derived because Spark
     * disconnected, refuses the send.
     */
    private suspend fun invoiceKey(paymentHash: String): String {
        val listed = runSuspendCatching { sparkService.listPayments() }.getOrElse { throw SendError.NotReady() }
        val states = listed.filter { it.sendHash().equals(paymentHash, ignoreCase = true) }.map { it.status }
        if (PaymentStatus.COMPLETED in states) throw SendError.AlreadyPaid()
        if (PaymentStatus.PENDING in states) throw SendError.InFlight()
        val failed = listed.filter { it.status == PaymentStatus.FAILED }.mapTo(HashSet()) { it.id }
        fun key(attempt: Int) = runCatching { sparkService.sendKey(paymentHash, attempt) }
            .getOrElse { throw SendError.NotReady() }
        var attempt = 0
        while (key(attempt) in failed) attempt++
        return key(attempt)
    }

    /**
     * The key a transfer to the Spark [address] has to go out under again, or null when a new key will do: no earlier
     * transfer to the address is noted with an outcome never learned. When one is, Spark's synced list decides: a
     * transfer it shows completed is not sent again ([SendError.AlreadyPaid], once, as the wearer was never told),
     * one it shows pending is under way ([SendError.InFlight]), one it shows failed is over, and one it does not show
     * goes out again under its key, which pays once. A store or a list that cannot be read refuses the send.
     */
    private suspend fun earlierTransferKey(address: String): String? {
        val earlier = runSuspendCatching { uncertainSendStore.transferKey(address) }
            .getOrElse { throw SendError.Failed(it) }
            ?: return null
        syncForSend()
        val listed = runSuspendCatching { sparkService.listPayments().firstOrNull { it.id == earlier } }
            .getOrElse { throw SendError.NotReady() }
        return when (listed?.status) {
            PaymentStatus.COMPLETED -> {
                settle(earlier)
                throw SendError.AlreadyPaid()
            }
            PaymentStatus.PENDING -> throw SendError.InFlight()
            PaymentStatus.FAILED -> {
                settle(earlier)
                null
            }
            null -> earlier
        }
    }

    /** Sends under [key] while already listening for the payment's outcome, which can arrive before the call ends. */
    private suspend fun sendWatched(prepared: PreparedSend, key: String): Payment = coroutineScope {
        val settled = MutableStateFlow<Map<String, Payment>>(emptyMap())
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sparkService.events.mapNotNull { it.payment() }
                .filter { it.status != PaymentStatus.PENDING }
                .collect { payment -> settled.update { it + (payment.id to payment) } }
        }
        try {
            // Noted before it goes out, so a send the app is closed during is known to be in doubt afterwards.
            val wasUncertain = runSuspendCatching {
                uncertainSendStore.begin(UncertainSend(key, prepared.sparkAddress, prepared.expiresAtSecs))
            }.getOrElse { throw SendError.Failed(it) }
            // The SDK call cannot be cancelled, so it runs beside this wait, which gives up on a call that stalls.
            val call = sendScope.async { runSuspendCatching { sparkService.send(prepared.response, key) } }
            sendCall = call
            val result = withTimeoutOrNull(SEND_TIMEOUT) { call.await() }
                ?: Result.failure(SendError.Unknown())
            val sent = result.getOrElse { recoverSend(it, key, wasUncertain) }
            // Spark lists the payment from here on, so the list tells its outcome.
            settle(key)
            val payment = if (sent.status == PaymentStatus.PENDING) {
                withTimeoutOrNull(SETTLE_TIMEOUT) { settled.first { sent.id in it }.getValue(sent.id) } ?: sent
            } else {
                sent
            }
            if (payment.status == PaymentStatus.FAILED) throw SendError.Failed()
            if (payment.amount.toLong().toULong() != prepared.amountSats) {
                Logger.warn("Sent payment '${payment.id}' is an earlier send at another amount", context = TAG)
            }
            Logger.info("Sent payment '${payment.id}' with status '${payment.status}'", context = TAG)
            payment
        } finally {
            watcher.cancel()
        }
    }

    /**
     * What a send that threw under [key] comes to. Spark's list decides first: a payment it shows under the key is
     * this send, also when an earlier attempt or the phone made it. Spark refuses an expired invoice and a short
     * balance before anything goes out, so such a refusal is final, unless an earlier attempt under the key has an
     * unknown outcome ([wasUncertain]): the refusal can follow the transfer that attempt committed. Any other error
     * can follow a committed transfer too, so it is [SendError.Unknown].
     */
    private suspend fun recoverSend(error: Throwable, key: String, wasUncertain: Boolean): Payment {
        runSuspendCatching { sparkService.listPayments().firstOrNull { it.id == key } }.getOrNull()?.let { return it }
        val isRefusal = error.isInvoiceExpired() || error is SdkException.InsufficientFunds
        if (isRefusal && !wasUncertain) {
            settle(key)
            throw error.toSendError()
        }
        throw SendError.Unknown(error)
    }

    /** Drops the note of the send under [key]. A note that stays only makes a later send more careful. */
    private suspend fun settle(key: String) {
        runSuspendCatching { uncertainSendStore.settle(key) }
            .onFailure { Logger.warn("Failed to settle the send '$key'", it, context = TAG) }
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
