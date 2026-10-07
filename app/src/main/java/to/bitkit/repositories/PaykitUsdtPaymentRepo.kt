@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentProof
import com.synonym.bitkitcore.UsdtPaymentProofBinding
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.PaykitPublicKeys
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPayment
import to.bitkit.models.PaykitUsdt
import to.bitkit.models.PaykitUsdtStateBackup
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService
import to.bitkit.viewmodels.ContactPaymentContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class PaykitUsdtProof(
    val type: String,
    @SerialName("chain_id") val chainId: String,
    @SerialName("transaction_hash") val transactionHash: String,
    @SerialName("receipt_log_index") val receiptLogIndex: String,
    val signature: String,
) {
    constructor(value: UsdtPaymentProof) : this(
        "erc20-transfer-eip712",
        value.chainId,
        value.transactionHash,
        value.receiptLogIndex,
        value.signature
    )
    val coreValue: UsdtPaymentProof get() = UsdtPaymentProof(chainId, transactionHash, receiptLogIndex, signature)
}

@Serializable
data class PaykitUsdtAttempt(
    val quoteId: String,
    val wallet: String,
    val identity: String,
    val contact: String,
    val requestId: PaykitPaymentRequestId?,
    val binding: UsdtPaymentProofBinding?,
    val billingPeriod: PaykitBillingPeriod?,
    val proof: PaykitUsdtProof? = null,
    val proofQueued: Boolean = false,
    val paymentStarted: Boolean = false,
)

@Serializable
data class PaykitUsdtReceipt(
    val wallet: String,
    val identity: String,
    val requestId: PaykitPaymentRequestId,
    val paymentId: String,
    val proofEventId: String,
    val verified: Boolean,
    val transferId: String,
    val amount: PaykitAmount,
    val receivedAtMillis: Long,
    val underpaid: Boolean,
    val afterExpiry: Boolean,
) {
    val satisfied: Boolean get() = verified && !underpaid && !afterExpiry
}

@Singleton
class PaykitUsdtPaymentRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val sdk: PaykitSdkService,
    private val proofs: PaykitPaymentProofRepo,
    private val usdt: UsdtRepo,
    private val clock: Clock,
) {
    companion object {
        private val KEY = Keychain.Key.PAYKIT_USDT_PAYMENTS.name

        fun binding(
            request: PaykitPaymentRequest,
            identity: String,
            paymentAppId: String,
            quoteId: String?,
            period: BillingPeriod?,
        ): UsdtPaymentProofBinding {
            val local = PaykitPublicKeys.raw(identity)
            val remote = PaykitPublicKeys.raw(request.counterparty)
            val incoming = request.direction == PaykitPaymentRequestDirection.Incoming
            return UsdtPaymentProofBinding(
                payer = if (incoming) local else remote,
                payee = if (incoming) remote else local,
                paymentAppId = paymentAppId,
                paymentRequestId = UUID.fromString(request.paymentRequestId).toString(),
                paymentReference = request.paymentReference,
                paymentEndpointIdentifier = MethodId.UsdtArbitrum.rawValue,
                // EIP-712 binds the proof's timestamp strings, not their normalized spelling.
                periodStartsAt = period?.startsAt.orEmpty(),
                periodEndsAt = period?.endsAt.orEmpty(),
                conversionQuoteId = quoteId.orEmpty(),
            )
        }

        fun validateProofSize(binding: UsdtPaymentProofBinding) {
            val profile =
                PaykitUsdtProof(
                    UsdtPaymentProof("42161", "0x" + "0".repeat(64), "9".repeat(78), "0x" + "0".repeat(130))
                )
            val message = buildJsonObject {
                put("version", 1)
                put("kind", "paykit.payment_proof")
                put("app_id", "bitkit")
                put("event_id", UUID.randomUUID().toString())
                put("payment_request_id", binding.paymentRequestId)
                put("payment_reference", binding.paymentReference)
                put("payment_app_id", binding.paymentAppId)
                put("payment_endpoint_identifier", binding.paymentEndpointIdentifier)
                put(
                    "billing_period",
                    if (binding.periodStartsAt.isEmpty()) {
                        JsonNull
                    } else {
                        buildJsonObject {
                            put("starts_at", binding.periodStartsAt)
                            put("ends_at", binding.periodEndsAt)
                        }
                    }
                )
                if (binding.conversionQuoteId.isNotEmpty()) put("conversion_quote_id", binding.conversionQuoteId)
                put("proof", Json.encodeToJsonElement(PaykitUsdtProof.serializer(), profile))
            }
            if (message.toString().toByteArray(Charsets.UTF_8).size > PaykitSdkService.MAX_MESSAGE_BYTES) {
                throw PaykitPaymentRequestError.RequestUnavailable
            }
        }
    }

    @Serializable
    private data class State(
        val attempts: List<PaykitUsdtAttempt> = emptyList(),
        val receipts: List<PaykitUsdtReceipt> = emptyList(),
    )

    private var activeWallet: String? = null
    private var activeIdentity: String? = null
    private val mutex = Mutex()
    private val _attempts = MutableStateFlow<List<PaykitUsdtAttempt>>(emptyList())
    val attempts = _attempts.asStateFlow()
    private val _receipts = MutableStateFlow<List<PaykitUsdtReceipt>>(emptyList())
    val receipts = _receipts.asStateFlow()
    private val _resumableRequests = MutableStateFlow<Set<PaykitPaymentRequestId>>(emptySet())
    val resumableRequests = _resumableRequests.asStateFlow()

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()

    suspend fun backupSnapshot(): PaykitUsdtStateBackup = withContext(ioDispatcher) {
        val state = load()
        PaykitUsdtStateBackup(state.attempts, state.receipts.map { PaykitUsdtStateBackup.Receipt(it) })
    }

    suspend fun restoreBackup(backup: PaykitUsdtStateBackup) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = load()
            val attempts = state.attempts.associateBy { it.wallet to it.quoteId }.toMutableMap()
            for (restored in backup.attempts) {
                val key = restored.wallet to restored.quoteId
                val local = attempts[key]
                attempts[key] = local?.copy(
                    paymentStarted = local.paymentStarted || restored.paymentStarted,
                    proof = local.proof ?: restored.proof,
                ) ?: restored
            }
            save(
                State(
                    attempts = attempts.values.map { it.copy(proofQueued = false) },
                    receipts = (state.receipts + backup.receipts.map { it.restored() })
                        .distinctBy { it.wallet to it.paymentId },
                )
            )
        }
    }

    suspend fun send(quote: UsdtQuote, beforeSend: suspend () -> Unit = {}): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            setPaymentStarted(quote.id, true).getOrThrow()
            val result = runSuspendCatching {
                beforeSend()
                usdt.send(quote).getOrThrow()
            }
            if (result.isFailure) {
                val history = storedTransfer(quote.id)
                if (history.isSuccess && history.getOrNull() == null) setPaymentStarted(quote.id, false)
            }
            result.getOrThrow()
            Unit
        }
    }

    private suspend fun setPaymentStarted(quoteId: String, started: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val state = load()
                val attempt = state.attempts.singleOrNull {
                    it.quoteId == quoteId && it.wallet == activeWallet && it.identity == activeIdentity
                } ?: throw PaykitPaymentRequestError.RequestUnavailable
                save(
                    state.copy(
                        attempts = state.attempts.map {
                            if (it == attempt) it.copy(paymentStarted = started) else it
                        }
                    )
                )
                if (started) _resumableRequests.update { it - setOfNotNull(attempt.requestId) }
            }
        }
    }

    private fun load(): State = keychain.loadString(KEY)?.let { Json.decodeFromString<State>(it) } ?: State()

    private suspend fun save(state: State) {
        keychain.upsertString(KEY, Json.encodeToString(state))
        publish(state)
        _backupStateVersion.update { it + 1 }
    }

    private fun publish(state: State) {
        _attempts.update { state.attempts.filter { it.wallet == activeWallet && it.identity == activeIdentity } }
        _receipts.update { state.receipts.filter { it.wallet == activeWallet && it.identity == activeIdentity } }
    }

    suspend fun protectedRequestIdsForSubscriptionCancellation(
        identity: String,
        subscriptionId: PaykitSubscriptionId,
    ): Result<Set<PaykitPaymentRequestId>> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val state = load()
                val matching = state.attempts.filter {
                    PubkyPublicKeyFormat.matches(it.identity, identity) &&
                        it.requestId?.paymentRequestId == subscriptionId.paymentRequestId &&
                        PubkyPublicKeyFormat.matches(it.requestId.counterparty, subscriptionId.counterparty) &&
                        !it.proofQueued
                }
                if (matching.isEmpty()) return@withLock emptySet()
                val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                matching.filter { it.wallet == owner }.mapNotNull { attempt ->
                    val transfer = storedTransfer(attempt.quoteId).getOrThrow()
                    val terminalFailure = transfer?.status in setOf(
                        UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED,
                    )
                    attempt.requestId.takeIf {
                        !terminalFailure && (attempt.paymentStarted || attempt.proof != null || transfer != null)
                    }
                }.toSet()
            }
        }
    }

    suspend fun verifiedReceipts(identity: String): Result<List<PaykitUsdtReceipt>> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val matching = load().receipts.filter {
                    PubkyPublicKeyFormat.matches(it.identity, identity) && it.satisfied
                }
                if (matching.isEmpty()) return@withLock emptyList()
                val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                matching.filter { it.wallet == owner }
            }
        }
    }

    fun hasBinding(requestId: PaykitPaymentRequestId): Boolean = _attempts.value.any { it.requestId == requestId }

    fun contact(transferId: String): String? = _attempts.value.firstOrNull { it.quoteId == transferId }?.contact
        ?: _receipts.value.firstOrNull { it.transferId == transferId }?.requestId?.counterparty

    suspend fun prepare(
        context: ContactPaymentContext,
        quote: UsdtQuote,
        amount: PaykitAmount?,
        paymentTerms: PaykitRequestPayment?,
    ) =
        withContext(ioDispatcher) {
            runSuspendCatching {
                mutex.withLock {
                    val identity = currentIdentity() ?: throw PaykitPaymentRequestError.RequestUnavailable
                    validateQuote(context, quote, amount, paymentTerms)
                    val request = context.incomingPaymentRequest
                    val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                    activeWallet = owner
                    activeIdentity = identity
                    val state = load()
                    validateReplacement(state, owner, identity, request, quote.id)
                    val binding = request?.let {
                        binding(
                            it,
                            identity,
                            context.privatePaymentContext?.paymentAppsByEndpoint?.get(MethodId.UsdtArbitrum.rawValue)
                                ?: throw PaykitPaymentRequestError.RequestUnavailable,
                            paymentTerms?.quoteId,
                            it.billingPeriod?.sdkValue
                        )
                    }
                    binding?.let(::validateProofSize)
                    val attempt =
                        PaykitUsdtAttempt(
                            quote.id,
                            owner,
                            identity,
                            context.publicKey,
                            request?.id,
                            binding,
                            request?.billingPeriod
                        )
                    val existing = state.attempts.firstOrNull { it.wallet == owner && it.quoteId == quote.id }
                    val previousBinding = existing?.copy(proof = null, proofQueued = false, paymentStarted = false)
                    if (previousBinding != null && previousBinding != attempt) {
                        throw PaykitPaymentRequestError.OperationInProgress
                    }
                    save(
                        state.copy(
                            attempts = state.attempts.filterNot {
                                it.wallet == owner && it.identity == identity &&
                                    (it.quoteId == quote.id || (request != null && it.requestId == request.id))
                            } + (existing ?: attempt)
                        )
                    )
                }
            }
        }

    private fun validateQuote(
        context: ContactPaymentContext,
        quote: UsdtQuote,
        amount: PaykitAmount?,
        paymentTerms: PaykitRequestPayment?,
    ) {
        val recipient = context.endpoints.firstOrNull { it.methodId == MethodId.UsdtArbitrum }
            ?.let { PaykitUsdt.address(it.rawPayload) }
        if (recipient == null || quote.destination != UsdtDestination.ARBITRUM ||
            !quote.recipient.equals(recipient, true)
        ) {
            throw PaykitPaymentRequestError.RequestUnavailable
        }
        val payment = PaykitAmount(PaykitAsset.USDT, quote.amount)
        if (payment != amount || quote.amount == 0uL) throw PaykitPaymentRequestError.RequestUnavailable
        context.incomingPaymentRequest?.let { validateRequestTerms(it, payment, paymentTerms) }
    }

    private fun validateRequestTerms(
        request: PaykitPaymentRequest,
        payment: PaykitAmount,
        terms: PaykitRequestPayment?,
    ) {
        val now = clock.now()
        val isAvailable = MethodId.UsdtArbitrum.rawValue in request.acceptedPaymentEndpointIdentifiers &&
            !request.isExpired(now)
        if (!isAvailable || terms == null || !terms.isValid(now)) throw PaykitPaymentRequestError.RequestUnavailable
        if (terms.amount != payment || request.payment(PaykitAsset.USDT, now, terms.quoteId) != terms) {
            throw PaykitPaymentRequestError.RequestUnavailable
        }
    }

    private suspend fun validateReplacement(
        state: State,
        owner: String,
        identity: String,
        request: PaykitPaymentRequest?,
        quoteId: String,
    ) {
        if (request == null) return
        val previous = state.attempts.firstOrNull {
            it.wallet == owner && it.identity == identity && it.requestId == request.id
        }?.takeIf { it.quoteId != quoteId } ?: return
        val transfer = storedTransfer(previous.quoteId).getOrThrow()
        if (transfer.isUnresolved(previous.paymentStarted)) throw PaykitPaymentRequestError.OperationInProgress
    }

    private fun UsdtTransfer?.isUnresolved(started: Boolean): Boolean = if (this == null) {
        started
    } else {
        status !in setOf(UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED)
    }

    suspend fun reconcile() = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val identity = currentIdentity() ?: return@withLock
                val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                activeWallet = owner
                activeIdentity = identity
                val state = load()
                publish(state)
                reconcileAttempts(owner, identity, state)
                reconcileReceipts(owner, identity)
            }
        }
    }

    private suspend fun reconcileAttempts(owner: String, identity: String, state: State) {
        val localAttempts = state.attempts.filter { it.wallet == owner && it.identity == identity }
        val resumable = mutableSetOf<PaykitPaymentRequestId>()
        for (attempt in localAttempts) {
            val requestId = attempt.requestId ?: continue
            val transfer = storedTransfer(attempt.quoteId).getOrThrow()
            if (!transfer.isUnresolved(attempt.paymentStarted)) resumable += requestId
            if (!attempt.proofQueued && (attempt.proof != null || transfer?.status == UsdtTransferStatus.CONFIRMED)) {
                submitAttemptProof(attempt, requestId)
            }
        }
        _resumableRequests.update { resumable }
    }

    private suspend fun submitAttemptProof(attempt: PaykitUsdtAttempt, requestId: PaykitPaymentRequestId) {
        val binding = attempt.binding ?: return
        val proof = attempt.proof
            ?: paymentProof(attempt.quoteId, binding).getOrThrow()?.let(::PaykitUsdtProof)
            ?: return
        if (attempt.proof == null) updateAttempt(attempt) { it.copy(proof = proof) }
        val pending = PendingPaykitPaymentProof(
            identity = attempt.identity,
            requestId = requestId,
            paymentAppId = binding.paymentAppId,
            paymentEndpointIdentifier = binding.paymentEndpointIdentifier,
            kind = PaykitPaymentProofKind.Usdt,
            paymentIdentifier = attempt.quoteId,
            proofData = Json.encodeToString(proof),
            billingPeriod = attempt.billingPeriod,
            conversionQuoteId = binding.conversionQuoteId.takeIf { it.isNotEmpty() },
        )
        if (proofs.submit(pending).getOrThrow()) updateAttempt(attempt) { it.copy(proofQueued = true) }
    }

    private suspend fun updateAttempt(attempt: PaykitUsdtAttempt, update: (PaykitUsdtAttempt) -> PaykitUsdtAttempt) {
        val current = load()
        save(
            current.copy(
                attempts = current.attempts.map {
                    if (
                        it.quoteId == attempt.quoteId && it.wallet == attempt.wallet && it.identity == attempt.identity
                    ) {
                        update(
                            it
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }

    private suspend fun reconcileReceipts(owner: String, identity: String) {
        for (record in sdk.paymentRequests().filter { it.localRole == PaymentRequestLocalRole.PAYEE }) {
            val submissions = record.paymentProofs.filter {
                it.paymentEndpointIdentifier == MethodId.UsdtArbitrum.rawValue
            }
            for (submission in submissions) {
                reconcileReceipt(record, submission, owner, identity)
            }
        }
    }

    private fun receivedRequest(record: PaymentRequestRecord, submission: PaymentProofRecord): PaykitPaymentRequest? {
        val period = submission.billingPeriod
        val request = if (period != null) {
            record.toPaykitSubscription()?.receivedPaymentRequests()?.firstOrNull {
                it.billingPeriod?.startsAt == Instant.parse(period.startsAt) &&
                    it.billingPeriod.endsAt == Instant.parse(period.endsAt)
            }
        } else {
            record.toPaykitPaymentRequestHistory(clock.now())
        }
        return request?.takeIf { submission.paymentEndpointIdentifier in it.acceptedPaymentEndpointIdentifiers }
    }

    private fun submittedProof(submission: PaymentProofRecord): PaykitUsdtProof? = runCatching {
        Json.decodeFromString<PaykitUsdtProof>(submission.proof.exportText())
    }.getOrNull()?.takeIf { it.type == "erc20-transfer-eip712" && submission.paymentAppId == "bitkit" }

    private suspend fun reconcileReceipt(
        record: PaymentRequestRecord,
        submission: PaymentProofRecord,
        owner: String,
        identity: String,
    ) {
        val request = receivedRequest(record, submission) ?: return
        val proof = submittedProof(submission) ?: return
        val binding = binding(
            request,
            identity,
            submission.paymentAppId,
            submission.conversionQuoteId,
            submission.billingPeriod
        )
        val paymentResult = verifyPayment(binding, proof.coreValue)
        if (paymentResult.exceptionOrNull() is UsdtException.InvalidPaymentProof ||
            paymentResult.isSuccess && paymentResult.getOrNull() == null
        ) {
            invalidateReceipt(submission.eventId, request.id, owner, identity)
            return
        }
        val payment = paymentResult.getOrThrow() ?: return
        val current = load()
        val samePayment = current.receipts.filter { it.wallet == owner && it.paymentId == payment.paymentId }
        if (samePayment.any { it.identity != identity || it.requestId != request.id }) return
        val receivedAt = Instant.fromEpochSeconds(payment.timestamp.toLong())
        val terms = runCatching {
            request.payment(
                PaykitAsset.USDT,
                receivedAt,
                submission.conversionQuoteId
            )
        }.getOrNull()
        if (terms != null) {
            val amount = PaykitAmount(PaykitAsset.USDT, payment.amount)
            val receipt = PaykitUsdtReceipt(
                wallet = owner,
                identity = identity,
                requestId = request.id,
                paymentId = payment.paymentId,
                proofEventId = submission.eventId,
                verified = true,
                transferId = payment.transferId,
                amount = amount,
                receivedAtMillis = receivedAt.toEpochMilliseconds(),
                underpaid = amount.atomic < terms.amount.atomic,
                afterExpiry = !terms.isValid(receivedAt),
            )
            save(current.copy(receipts = current.receipts - samePayment.toSet() + receipt))
        }
    }

    private suspend fun invalidateReceipt(
        proofEventId: String,
        requestId: PaykitPaymentRequestId,
        owner: String,
        identity: String,
    ) {
        val current = load()
        val matching = current.receipts.filter {
            it.wallet == owner && it.identity == identity && it.requestId == requestId
        }.filter { it.proofEventId == proofEventId && it.verified }.toSet()
        if (matching.isEmpty()) return
        save(current.copy(receipts = current.receipts.map { if (it in matching) it.copy(verified = false) else it }))
    }

    private suspend fun paymentProof(quoteId: String, binding: UsdtPaymentProofBinding) =
        usdt.operation { this.paymentProof(quoteId, binding) }
    private suspend fun storedTransfer(id: String) = usdt.operation { wallet().history().firstOrNull { it.id == id } }
    private suspend fun verifyPayment(binding: UsdtPaymentProofBinding, proof: UsdtPaymentProof) =
        usdt.operation { wallet().verifyPaymentProof(binding, proof) }

    private suspend fun currentIdentity(): String? = sdk.identityStatus()?.takeIf {
        it.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
    }
        ?.publicKey?.let(PubkyPublicKeyFormat::normalized)
}
