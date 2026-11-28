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
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.Logger
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val quickPayRepo: QuickPayRepo,
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

        /** The schemes the decoder reads an input without, as the phone's scanner strips them. */
        private val STRIPPED_SCHEMES = setOf("lightning", "lnurl", "lnurlp")
        private val URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")
        private const val ONION_TLD = ".onion"

        /**
         * [input] as the decoder reads it. The scheme is lowercased, and `lightning:`, `lnurl:` and `lnurlp:` are
         * dropped, as the phone's scanner does. A LUD-17 `lnurlp://host/path` link becomes the LNURL-pay URL
         * `https://host/path` (`http` for an onion host). A payload without a lowercase letter, as an uppercase QR
         * code has, is lowercased: bech32 is read in one case, and BIP21 keys are matched lowercase.
         */
        internal fun decoderInput(input: String): String {
            val text = input.trim()
            val scheme = text.substringBefore(':', missingDelimiterValue = "").takeIf { URI_SCHEME.matches(it) }
                ?.lowercase()
            val payload = if (scheme == null) text else text.substringAfter(':')
            if (scheme == "lnurlp" && payload.startsWith("//")) {
                val host = payload.drop(2).substringBefore('/').substringBefore('?').substringBefore(':')
                return (if (host.endsWith(ONION_TLD, ignoreCase = true)) "http:" else "https:") + payload
            }
            val oneCase = if (payload.none { it.isLowerCase() }) payload.lowercase() else payload
            return if (scheme == null || scheme in STRIPPED_SCHEMES) oneCase else "$scheme:$oneCase"
        }

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

    /** The request [sendCall] pays: its invoice's payment hash or its Spark payee. Guarded by [sendMutex]. */
    private var sendCallRequest: String? = null

    /** Emits whenever Spark reports a payment or a sync, so the activity list reads [recentPayments] again. */
    val paymentChanges: Flow<Unit> get() = sparkService.events.map { }

    /** What [skipConfirmation] counted against the QuickPay limit, by [PreparedSend.transferKey], until [send] ends. */
    private val quickPayReservations = ConcurrentHashMap<String, QuickPayReservation>()

    /**
     * Whether [prepared] goes out without the hold to pay (QuickPay), by the phone's rules. This is the one place
     * that decides it. It does only when all of these hold:
     * - the wallet is ready;
     * - [prepared] came from [prepare], so the self-send guard and every check before the hold passed;
     * - its request fixes the amount, as a BOLT11 invoice with an amount, also inside a `bitcoin:` URI, or an LNURL
     *   or a Lightning address that takes one amount only; an amount the wearer typed is always reviewed, and so is
     *   a Spark address;
     * - [QuickPayRepo.reserve] takes the payment's amount, without its fee, under the phone's QuickPay settings;
     * - [canSend] still says yes after all of that, which is where the caller answers for the wearer being there:
     *   see `SendViewModel`.
     *
     * A yes counts the payment against today's QuickPay limit, so ask once per send and only when about to [send],
     * which takes it out again when nothing was paid. A no counts nothing. Anything that cannot be told answers no,
     * and the wearer holds to pay.
     */
    suspend fun skipConfirmation(prepared: PreparedSend, canSend: () -> Boolean): Boolean =
        withContext(ioDispatcher) {
            if (!isReady() || sparkService.isWiping) return@withContext false
            if (!prepared.isAmountFixed) return@withContext false
            quickPayRepo.awaitRate()
            val reservation = quickPayRepo.reserve(prepared.amountSats) ?: return@withContext false
            // The wallet may have locked, paused or been wiped, or the wearer left, while the limit was read.
            if (!isReady() || sparkService.isWiping || !canSend()) {
                quickPayRepo.release(reservation)
                return@withContext false
            }
            quickPayReservations[prepared.transferKey] = reservation
            true
        }

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
     * `lightning` parameter or a Spark address, each with or without its URI scheme, in either case (see
     * [decoderInput]). An LNURL or a Lightning address
     * is fetched from its host. Fails with a [SendError]: [SendError.Invalid], [SendError.Unsupported],
     * [SendError.Expired], and [SendError.OwnInvoice] when the wallet is ready to tell. A Spark address needs the
     * wallet ready ([SendError.NotReady]); the other inputs are read without it, and [prepare] checks them again.
     */
    suspend fun parse(input: String): Result<SendRequest> = withContext(ioDispatcher) {
        runSuspendCatching {
            val text = decoderInput(input)
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
                val bolt11 = scanned.invoice.params?.entries
                    ?.firstOrNull { it.key.equals("lightning", ignoreCase = true) }
                    ?.value ?: throw SendError.Unsupported()
                val invoice = runSuspendCatching { inputDecoder.decode(decoderInput(bolt11)) as? Scanner.Lightning }
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
                        val isAmountInRequest = request.amountSats != null
                        prepare(
                            paymentRequest = request.bolt11,
                            amountSats = amount,
                            isAmountInRequest = isAmountInRequest,
                            payee = request.payee,
                            isAmountFixed = isAmountInRequest,
                        )
                    }
                    is SendRequest.Lnurl -> {
                        ensureAmount(amount, request.minSats, request.maxSats)
                        prepare(
                            paymentRequest = lnurlInvoice(request, amount),
                            amountSats = amount,
                            isAmountInRequest = true,
                            payee = request.payee,
                            exactMsats = amount * MSATS_PER_SAT,
                            isAmountFixed = request.data.minSendable == request.data.maxSendable,
                        )
                    }
                    is SendRequest.SparkAddress -> {
                        ensureAmount(amount, 1uL, MAX_SATS)
                        val sparkPayee = sparkPayee(request.address)
                        // Told before the review, so the wearer never holds to pay a transfer that already went out.
                        earlierTransferKey(sparkPayee)
                        prepare(
                            paymentRequest = request.address,
                            amountSats = amount,
                            isAmountInRequest = false,
                            payee = request.payee,
                            sparkPayee = sparkPayee,
                        )
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
        sparkPayee: String? = null,
        isAmountFixed: Boolean = false,
    ): PreparedSend {
        ensureNotOwn(paymentRequest)
        val response = runSuspendCatching {
            sparkService.prepareSend(paymentRequest, amountSats.takeIf { !isAmountInRequest })
        }.getOrElse { throw it.toSendError() }
        val method = response.paymentMethod
        val (feeSats, invoice) = when (method) {
            is SendPaymentMethod.Bolt11Invoice -> method.feeSats() to method.invoiceDetails
            is SendPaymentMethod.SparkAddress -> {
                // A transfer is only sent with the identity it is noted under.
                if (sparkPayee == null) throw SendError.Invalid()
                method.fee.toLong().toULong() to null
            }
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
            sparkPayee = sparkPayee.takeIf { invoice == null },
            transferKey = UUID.randomUUID().toString(),
            isAmountFixed = isAmountFixed,
        )
    }

    /**
     * Refuses [paymentRequest], a BOLT11 invoice or a Spark address, when [SelfSendGuard] says it pays this wallet.
     * A request the SDK cannot read, or a guard that cannot tell, refuses it too.
     */
    private suspend fun ensureNotOwn(paymentRequest: String) {
        val input = runSuspendCatching { sparkService.parse(paymentRequest) }.getOrElse { throw it.toInputError() }
        if (input !is InputType.Bolt11Invoice && input !is InputType.SparkAddress) throw SendError.Invalid()
        val isOwn = runSuspendCatching { selfSendGuard.isOwn(input) }.getOrElse { throw SendError.NotReady() }
        if (isOwn) throw SendError.OwnInvoice()
    }

    /**
     * The Spark identity public key the Spark [address] encodes, in lowercase hex. The `sp…` and the `spark…`
     * spelling of one wallet's address give the same key, which is what an uncertain transfer is noted under.
     */
    private suspend fun sparkPayee(address: String): String {
        val input = runSuspendCatching { sparkService.parse(address) }.getOrElse { throw it.toInputError() }
        return (input as? InputType.SparkAddress)?.v1?.identityPublicKey?.lowercase() ?: throw SendError.Invalid()
    }

    private fun SendPaymentMethod.Bolt11Invoice.feeSats(): ULong =
        sparkTransferFeeSats?.takeIf { sparkService.prefersSpark } ?: lightningFeeSats

    /**
     * Pays [prepared] and returns the payment as Spark made it: [PaymentState.Completed], or [PaymentState.Pending]
     * when Spark took it and it did not settle within [SETTLE_TIMEOUT]. The send does not run in the caller's scope:
     * a caller that goes away neither cancels it nor loses its bookkeeping. Fails with a [SendError]:
     * [SendError.NotReady], also when Spark cannot be reached to bring its list up to date, [SendError.AlreadyPaid],
     * [SendError.InFlight], also while Spark still holds the stalled call of this same request, [SendError.Busy]
     * while another send runs or Spark holds the stalled call of another request, [SendError.Expired],
     * [SendError.InsufficientBalance], [SendError.Failed], and [SendError.Unknown] when the payment may have gone
     * out.
     *
     * An invoice is sent under the idempotency key the phone derives for it too, so Spark pays it once however often
     * and from whichever device it is sent; each attempt Spark lists as failed moves to the next key. A Spark address
     * has no such key, so the key of a transfer to it is kept in [UncertainSendStore], under the identity key the
     * address encodes, until its outcome is known, and a retry goes out under the same key. Every send is noted there
     * before it goes out, so a refusal that follows an attempt of unknown outcome, also one the app was closed
     * during, is never read as nothing sent.
     *
     * A send that [skipConfirmation] counted against the QuickPay limit is taken out of the count again when it
     * fails with anything but [SendError.Unknown].
     */
    suspend fun send(prepared: PreparedSend): Result<WatchPayment> = sendScope.async {
        runSuspendCatching {
            // Another send holds the wallet: nothing of this request is sent, so it is not reported as under way.
            if (!sendMutex.tryLock()) throw SendError.Busy()
            try {
                if (sendCall?.isActive == true) {
                    // The stalled call is this request's own and may still pay, so it is under way, not refused.
                    val request = prepared.requestId()
                    throw if (request != null && request == sendCallRequest) SendError.InFlight() else SendError.Busy()
                }
                if (!isReady()) throw SendError.NotReady()
                val sparkPayee = prepared.sparkPayee
                val key = if (sparkPayee != null) {
                    earlierTransferKey(sparkPayee) ?: prepared.transferKey
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
        }.also { result ->
            // As on the phone, a QuickPay payment that did not go out or failed is not counted. One whose outcome
            // is unknown may have been paid, so it stays counted.
            val reservation = quickPayReservations.remove(prepared.transferKey)
            if (reservation != null && result.isFailure && result.exceptionOrNull() !is SendError.Unknown) {
                quickPayRepo.release(reservation)
            }
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
     * The key a transfer to the wallet with the identity key [sparkPayee] has to go out under again, or null when a
     * new key will do: no earlier transfer to it is noted with an outcome never learned. When one is, Spark's synced
     * list decides: a transfer it shows completed is not sent again ([SendError.AlreadyPaid], once, as the wearer was
     * never told), one it shows pending is under way ([SendError.InFlight]), one it shows failed is over, and one it
     * does not show goes out again under its key, which pays once. Every noted transfer is asked about, so one that
     * could not be dropped never hides another. A store or a list that cannot be read refuses the send.
     */
    private suspend fun earlierTransferKey(sparkPayee: String): String? {
        val earlier = runSuspendCatching { uncertainSendStore.transferKeys(sparkPayee) }
            .getOrElse { throw SendError.Failed(it) }
        if (earlier.isEmpty()) return null
        syncForSend()
        val listed = runSuspendCatching { sparkService.listPayments().filter { it.id in earlier } }
            .getOrElse { throw SendError.NotReady() }
            .associate { it.id to it.status }
        earlier.filter { listed[it] == PaymentStatus.FAILED }.forEach { settle(it) }
        val completed = earlier.filter { listed[it] == PaymentStatus.COMPLETED }
        if (completed.isNotEmpty()) {
            completed.forEach { settle(it) }
            throw SendError.AlreadyPaid()
        }
        if (earlier.any { listed[it] == PaymentStatus.PENDING }) throw SendError.InFlight()
        return earlier.firstOrNull { it !in listed }
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
            val sent = sendNoted(prepared, key)
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
     * Notes the send under [key], hands it to Spark and returns the payment Spark made. The connection is held from
     * the note on, so a lock or a pause does not disconnect between the note and the call. A wallet that was wiped
     * or stopped being ready before the note was written sends nothing and leaves no note behind.
     */
    private suspend fun sendNoted(prepared: PreparedSend, key: String): Payment {
        sparkService.holdSend()
        try {
            // Noted before it goes out, so a send the app is closed during is known to be in doubt afterwards.
            val wasUncertain = runSuspendCatching {
                uncertainSendStore.begin(UncertainSend(key, prepared.sparkPayee, prepared.expiresAtSecs))
            }.getOrElse { throw SendError.Failed(it) }
            // A wipe that ran meanwhile cleared the store before the note, which would outlive the wiped wallet.
            if (!isReady() || sparkService.isWiping) {
                if (!wasUncertain) settle(key)
                throw SendError.NotReady()
            }
            // The SDK call cannot be cancelled, so it runs beside this wait, which gives up on a call that stalls.
            val call = sendScope.async { runSuspendCatching { sparkService.send(prepared.response, key) } }
            sendCall = call
            sendCallRequest = prepared.requestId()
            val result = withTimeoutOrNull(SEND_TIMEOUT) { call.await() }
                ?: Result.failure(SendError.Unknown())
            return result.getOrElse { recoverSend(it, key, wasUncertain) }
        } finally {
            sparkService.releaseSend()
        }
    }

    /**
     * What a send that threw under [key] comes to. Spark's list decides first: a payment it shows under the key is
     * this send, also when an earlier attempt or the phone made it. Spark refuses an expired invoice and a short
     * balance before anything goes out, so such a refusal is final, unless an earlier attempt under the key has an
     * unknown outcome ([wasUncertain]): the refusal can follow the transfer that attempt committed. A wallet that was
     * disconnected before the SDK got the call sent nothing either ([SendError.NotReady]). Any other error can follow
     * a committed transfer too, so it is [SendError.Unknown].
     */
    private suspend fun recoverSend(error: Throwable, key: String, wasUncertain: Boolean): Payment {
        runSuspendCatching { sparkService.listPayments().firstOrNull { it.id == key } }.getOrNull()?.let { return it }
        val isRefusal = error.isInvoiceExpired() || error is SdkException.InsufficientFunds
        if (isRefusal && !wasUncertain) {
            settle(key)
            throw error.toSendError()
        }
        // Thrown by the service before it calls the SDK, so this attempt sent nothing; an earlier one keeps its note.
        if (error is SparkError.NotConnected) {
            if (!wasUncertain) settle(key)
            throw SendError.NotReady()
        }
        throw SendError.Unknown(error)
    }

    private fun PreparedSend.requestId(): String? = paymentHash?.lowercase() ?: sparkPayee

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

/** Why an input could not be read by Spark: a wallet that disconnected meanwhile is not an unreadable input. */
private fun Throwable.toInputError(): SendError =
    if (this is SparkError.NotConnected) SendError.NotReady() else SendError.Invalid(this)

private fun Throwable.toSendError(): SendError = when {
    this is SendError -> this
    isInvoiceExpired() -> SendError.Expired(this)
    this is SdkException.InsufficientFunds -> SendError.InsufficientBalance(null)
    else -> SendError.Failed(this)
}
