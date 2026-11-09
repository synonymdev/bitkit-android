@file:OptIn(ExperimentalTime::class)

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
import kotlinx.coroutines.NonCancellable
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
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ContactPaymentContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
    val requestId: PaykitPaymentRequestId? = null,
    val binding: UsdtPaymentProofBinding? = null,
    val billingPeriod: PaykitBillingPeriod? = null,
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

    fun matches(request: PaykitPaymentRequest): Boolean =
        requestId == request.id && proofEventId == request.paymentProofEventId
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
        private const val TAG = "PaykitUsdtPaymentRepo"

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

    private data class Reconciliation(val wallet: String, val identity: String, val generation: Long)

    private var activeWallet: String? = null
    private var activeIdentity: String? = null
    private val mutex = Mutex()
    private val reconcileMutex = Mutex()
    private var stateGeneration = 0L
    private var sending = false
    private var reconcilingResumableRequests = emptySet<PaykitPaymentRequestId>()

    private val _attempts = MutableStateFlow<List<PaykitUsdtAttempt>>(emptyList())
    val attempts = _attempts.asStateFlow()
    private val _receipts = MutableStateFlow<List<PaykitUsdtReceipt>>(emptyList())
    val receipts = _receipts.asStateFlow()
    private val _resumableRequests = MutableStateFlow<Set<PaykitPaymentRequestId>>(emptySet())
    val resumableRequests = _resumableRequests.asStateFlow()
    private val unboundAttempts = MutableStateFlow<List<PaykitUsdtAttempt>>(emptyList())

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()
    private val _paymentRequestStateVersion = MutableStateFlow(0L)
    val paymentRequestStateVersion = _paymentRequestStateVersion.asStateFlow()

    suspend fun backupSnapshot(): PaykitUsdtStateBackup = withContext(ioDispatcher) {
        val state = load()
        PaykitUsdtStateBackup(state.attempts, state.receipts.map { PaykitUsdtStateBackup.Receipt(it) })
    }

    suspend fun restoreBackup(backup: PaykitUsdtStateBackup) = withContext(ioDispatcher) {
        mutex.withLock {
            stateGeneration++
            activeWallet = null
            activeIdentity = null
            unboundAttempts.update { emptyList() }
            _resumableRequests.update { emptySet() }
            reconcilingResumableRequests = emptySet()
            val state = load()
            val attempts = state.attempts.associateBy { it.wallet to it.quoteId }.toMutableMap()
            for (restored in backup.attempts) {
                val key = restored.wallet to restored.quoteId
                val local = attempts[key]
                if (local != null && local.bindingState() != restored.bindingState()) {
                    throw UsdtException.InvalidBackup()
                }
                attempts[key] = local?.copy(
                    paymentStarted = local.paymentStarted || restored.paymentStarted ||
                        restored.proof != null || restored.proofQueued,
                ) ?: restored
            }
            save(
                State(
                    attempts = attempts.values.map {
                        it.copy(
                            paymentStarted = it.paymentStarted || it.proof != null || it.proofQueued,
                            proof = null,
                            proofQueued = false,
                        )
                    },
                    receipts = (state.receipts + backup.receipts.map { it.restored() })
                        .distinctBy { it.wallet to it.paymentId }.map { it.copy(verified = false) },
                )
            )
        }
    }

    suspend fun pauseForRecoveryRestore() = mutex.withLock { stateGeneration++ }

    suspend fun resetForWipe() = withContext(ioDispatcher) {
        mutex.withLock {
            stateGeneration++
            activeWallet = null
            activeIdentity = null
            unboundAttempts.update { emptyList() }
            _resumableRequests.update { emptySet() }
            reconcilingResumableRequests = emptySet()
            publish(State())
            _paymentRequestStateVersion.update { it + 1 }
        }
    }

    suspend fun send(
        quote: UsdtQuote,
        submissionDeadline: Instant? = null,
        beforeSend: suspend () -> Unit = {},
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                if (sending) throw PaykitPaymentRequestError.OperationInProgress
                sending = true
                stateGeneration++
            }
            var sent = false
            var wasResumable = false
            try {
                wasResumable = setPaymentStarted(quote.id, true).getOrThrow()
                usdt.send(quote, submissionDeadline, beforeSend).getOrThrow()
                sent = true
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (!sent) {
                            val history = storedTransfer(quote.id)
                            if (history.isSuccess && history.getOrNull() == null) {
                                setPaymentStarted(quote.id, false, wasResumable)
                            }
                        }
                    } finally {
                        mutex.withLock { sending = false }
                    }
                }
            }
            Unit
        }
    }

    private suspend fun setPaymentStarted(
        quoteId: String,
        started: Boolean,
        resumable: Boolean = false,
    ): Result<Boolean> = withContext(ioDispatcher) {
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
                val requestId = attempt.requestId
                val wasResumable = requestId in _resumableRequests.value || requestId in reconcilingResumableRequests
                if (started) {
                    _resumableRequests.update { it - setOfNotNull(requestId) }
                    reconcilingResumableRequests -= setOfNotNull(requestId)
                }
                if (!started && resumable) _resumableRequests.update { it + setOfNotNull(requestId) }
                wasResumable
            }
        }
    }

    private fun load(): State = keychain.loadString(KEY)?.let { Json.decodeFromString<State>(it) } ?: State()

    private suspend fun save(state: State) {
        keychain.upsertString(KEY, Json.encodeToString(state))
        publish(state)
        _backupStateVersion.update { it + 1 }
        _paymentRequestStateVersion.update { it + 1 }
    }

    private fun publish(state: State) {
        _attempts.update { state.attempts.filter { it.wallet == activeWallet && it.identity == activeIdentity } }
        _receipts.update { state.receipts.filter { it.wallet == activeWallet && it.identity == activeIdentity } }
        unboundAttempts.update { known -> known.filter { it in _attempts.value } }
    }

    suspend fun protectedPaymentAttempts(
        identity: String,
    ): Result<List<PaykitUsdtAttempt>> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                load().attempts.filter { attempt ->
                    PubkyPublicKeyFormat.matches(attempt.identity, identity) &&
                        (activeWallet == null || attempt.wallet == activeWallet) &&
                        !attempt.proofQueued && (attempt.paymentStarted || attempt.proof != null) &&
                        !(
                            attempt.wallet == activeWallet && PubkyPublicKeyFormat.matches(activeIdentity, identity) &&
                                attempt.requestId in resumableRequests.value
                            )
                }
            }
        }
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

    fun hasBinding(requestId: PaykitPaymentRequestId): Boolean =
        runCatching {
            load().attempts.any { it.requestId == requestId && it !in unboundAttempts.value }
        }.getOrDefault(true)

    suspend fun prepare(
        context: ContactPaymentContext,
        quote: UsdtQuote,
        amount: PaykitAmount?,
        paymentTerms: PaykitRequestPayment?,
    ) =
        withContext(ioDispatcher) {
            runSuspendCatching {
                quote.requireValidForSubmission(clock.now())
                mutex.withLock {
                    if (sending) throw PaykitPaymentRequestError.OperationInProgress
                    val identity = currentIdentity(Priority.Interactive)
                        ?: throw PaykitPaymentRequestError.RequestUnavailable
                    validateQuote(context, quote, amount, paymentTerms)
                    val request = context.incomingPaymentRequest
                    val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                    quote.requireValidForSubmission(clock.now())
                    activate(owner, identity)
                    val state = load()
                    val removable = validateReplacement(state, owner, identity, request, quote.id)
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
                    val previousBinding = existing?.bindingState()
                    if (previousBinding != null && previousBinding != attempt) {
                        throw PaykitPaymentRequestError.OperationInProgress
                    }
                    unboundAttempts.update { emptyList() }
                    save(
                        state.copy(
                            attempts = state.attempts.filterNot {
                                it.wallet == owner && it.identity == identity &&
                                    (it.quoteId == quote.id || it in removable)
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
        if (terms.amount != payment || request.payment(MethodId.UsdtArbitrum, now, terms.quoteId) != terms) {
            throw PaykitPaymentRequestError.RequestUnavailable
        }
    }

    private suspend fun validateReplacement(
        state: State,
        owner: String,
        identity: String,
        request: PaykitPaymentRequest?,
        quoteId: String,
    ): List<PaykitUsdtAttempt> {
        if (request == null) return emptyList()
        val matching = state.attempts.filter {
            it.wallet == owner && it.identity == identity && it.requestId == request.id && it.quoteId != quoteId
        }
        val removable = mutableListOf<PaykitUsdtAttempt>()
        for (previous in matching) {
            if (previous.proof != null || previous.proofQueued) throw PaykitPaymentRequestError.OperationInProgress
            val transfer = storedTransfer(previous.quoteId).getOrThrow()
            if (transfer.isUnresolved(previous.paymentStarted)) throw PaykitPaymentRequestError.OperationInProgress
            if (transfer == null && !previous.paymentStarted) removable += previous
        }
        return removable
    }

    private fun UsdtTransfer?.isUnresolved(started: Boolean): Boolean = if (this == null) {
        started
    } else {
        status !in setOf(UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED)
    }

    suspend fun reconcile(): Result<Unit> = withContext(ioDispatcher) {
        if (usdt.isRecoveryRestorePending) return@withContext Result.success(Unit)
        runSuspendCatching {
            reconcileMutex.withLock {
                val previousProtection = Triple(activeWallet, activeIdentity, _resumableRequests.value)
                try {
                    val generation = mutex.withLock snapshot@{
                        if (sending) return@snapshot null
                        unboundAttempts.update { emptyList() }
                        reconcilingResumableRequests = _resumableRequests.value
                        _resumableRequests.update { emptySet() }
                        stateGeneration
                    } ?: return@withLock
                    val identity = currentIdentity() ?: return@withLock
                    val owner = usdt.paymentEndpoint().getOrThrow().value.lowercase()
                    val (reconciliation, state) = mutex.withLock snapshot@{
                        if (stateGeneration != generation) return@snapshot null
                        activate(owner, identity)
                        val state = load()
                        publish(state)
                        Reconciliation(owner, identity, stateGeneration) to state
                    } ?: return@withLock
                    reconcileAttempts(reconciliation, state)
                    reconcileReceipts(reconciliation)
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            _resumableRequests.update { it + reconcilingResumableRequests }
                            reconcilingResumableRequests = emptySet()
                        }
                    }
                    if (previousProtection.third.isNotEmpty() ||
                        previousProtection != Triple(activeWallet, activeIdentity, _resumableRequests.value)
                    ) {
                        _paymentRequestStateVersion.update { it + 1 }
                    }
                }
            }
        }
    }

    private fun activate(owner: String, identity: String) {
        if (activeWallet != owner || activeIdentity != identity) {
            stateGeneration++
            unboundAttempts.update { emptyList() }
            _resumableRequests.update { emptySet() }
            reconcilingResumableRequests = emptySet()
        }
        activeWallet = owner
        activeIdentity = identity
    }

    private fun isCurrent(reconciliation: Reconciliation): Boolean =
        stateGeneration == reconciliation.generation && activeWallet == reconciliation.wallet &&
            activeIdentity == reconciliation.identity

    private suspend fun reconcileAttempts(reconciliation: Reconciliation, state: State) {
        val localAttempts = state.attempts.filter {
            it.wallet == reconciliation.wallet && it.identity == reconciliation.identity
        }
        val unbound = mutableListOf<PaykitUsdtAttempt>()
        for (attempt in localAttempts) {
            val requestId = attempt.requestId ?: continue
            if (!mutex.withLock { isCurrent(reconciliation) }) return
            runSuspendCatching {
                val transfer = storedTransfer(attempt.quoteId).getOrThrow()
                val current = if (transfer == null) {
                    updateAttempt(reconciliation, attempt) { it.copy(paymentStarted = false) }
                        ?: return@runSuspendCatching
                } else {
                    attempt
                }
                if (current.canReleaseBinding(transfer)) {
                    unbound += current
                } else {
                    mutex.withLock { reconcilingResumableRequests -= requestId }
                }
                if (!attempt.proofQueued &&
                    (attempt.proof != null || transfer?.status == UsdtTransferStatus.CONFIRMED)
                ) {
                    submitAttemptProof(reconciliation, attempt, requestId)
                }
            }.onFailure { Logger.warn("Failed to reconcile a Paykit USDT payment attempt", it, context = TAG) }
        }
        mutex.withLock {
            if (!isCurrent(reconciliation)) return@withLock
            val current = load()
            publish(current)
            unboundAttempts.update { unbound.filter { it in current.attempts } }
            val protected = current.attempts.filterNot { it in unboundAttempts.value }.filter {
                it.wallet == reconciliation.wallet && it.identity == reconciliation.identity
            }
            val resumable = unboundAttempts.value.mapNotNull { it.requestId }.toSet() -
                protected.mapNotNull { it.requestId }.toSet()
            _resumableRequests.update { resumable }
            reconcilingResumableRequests = emptySet()
        }
    }

    private fun PaykitUsdtAttempt.canReleaseBinding(transfer: UsdtTransfer?): Boolean =
        proof == null && !proofQueued && !transfer.isUnresolved(paymentStarted)

    private suspend fun submitAttemptProof(
        reconciliation: Reconciliation,
        attempt: PaykitUsdtAttempt,
        requestId: PaykitPaymentRequestId,
    ) {
        if (!mutex.withLock { isCurrent(reconciliation) }) return
        val binding = attempt.binding ?: return
        val proof = attempt.proof
            ?: paymentProof(attempt.quoteId, binding).getOrThrow()?.let(::PaykitUsdtProof)
            ?: return
        val saved = updateAttempt(reconciliation, attempt) { it.copy(proof = it.proof ?: proof) } ?: return
        if (saved.proof != proof || saved.proofQueued) return
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
        if (proofs.submit(pending).getOrThrow()) {
            updateAttempt(reconciliation, saved) { if (it.proof == proof) it.copy(proofQueued = true) else it }
        }
    }

    private suspend fun updateAttempt(
        reconciliation: Reconciliation,
        attempt: PaykitUsdtAttempt,
        update: (PaykitUsdtAttempt) -> PaykitUsdtAttempt,
    ): PaykitUsdtAttempt? = mutex.withLock {
        if (!isCurrent(reconciliation)) return@withLock null
        val state = load()
        val current = state.attempts.singleOrNull { it.bindingState() == attempt.bindingState() }
            ?: return@withLock null
        val updated = update(current)
        if (updated != current) save(state.copy(attempts = state.attempts.map { if (it == current) updated else it }))
        updated
    }

    private fun PaykitUsdtAttempt.bindingState() = copy(proof = null, proofQueued = false, paymentStarted = false)

    private suspend fun reconcileReceipts(reconciliation: Reconciliation) {
        if (!mutex.withLock { isCurrent(reconciliation) }) return
        for (record in sdk.paymentRequests().filter { it.localRole == PaymentRequestLocalRole.PAYEE }) {
            val submissions = record.paymentProofs.filter {
                it.paymentEndpointIdentifier == MethodId.UsdtArbitrum.rawValue
            }
            for (submission in submissions) {
                if (!mutex.withLock { isCurrent(reconciliation) }) return
                reconcileReceipt(record, submission, reconciliation)
            }
        }
    }

    private fun receivedRequest(record: PaymentRequestRecord, submission: PaymentProofRecord): PaykitPaymentRequest? {
        val period = submission.billingPeriod
        val request = if (period != null) {
            val startsAt = runCatching { Instant.parse(period.startsAt) }.getOrNull() ?: return null
            val endsAt = runCatching { Instant.parse(period.endsAt) }.getOrNull() ?: return null
            record.toPaykitSubscription()?.receivedPaymentRequests()?.firstOrNull {
                it.billingPeriod?.startsAt == startsAt && it.billingPeriod.endsAt == endsAt
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
        reconciliation: Reconciliation,
    ) {
        val request = receivedRequest(record, submission) ?: return
        val proof = submittedProof(submission) ?: return
        val binding = binding(
            request,
            reconciliation.identity,
            submission.paymentAppId,
            submission.conversionQuoteId,
            submission.billingPeriod
        )
        val paymentResult = verifyPayment(binding, proof.coreValue)
        if (paymentResult.exceptionOrNull() is UsdtException.InvalidPaymentProof ||
            paymentResult.isSuccess && paymentResult.getOrNull() == null
        ) {
            invalidateReceipt(submission.eventId, request.id, reconciliation)
            return
        }
        val payment = paymentResult.getOrThrow() ?: return
        val receivedAt = Instant.fromEpochSeconds(payment.timestamp.toLong())
        val terms = runCatching {
            request.payment(
                MethodId.UsdtArbitrum,
                receivedAt,
                submission.conversionQuoteId
            )
        }.getOrNull()
        if (terms != null) {
            val amount = PaykitAmount(PaykitAsset.USDT, payment.amount)
            val receipt = PaykitUsdtReceipt(
                wallet = reconciliation.wallet,
                identity = reconciliation.identity,
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
            mutex.withLock {
                if (!isCurrent(reconciliation)) return@withLock
                val current = load()
                val samePayment = current.receipts.filter {
                    it.wallet == reconciliation.wallet && it.paymentId == payment.paymentId
                }
                if (samePayment.any { it.identity != reconciliation.identity || it.requestId != request.id }) {
                    return@withLock
                }
                if (samePayment.singleOrNull() != receipt) {
                    save(current.copy(receipts = current.receipts - samePayment.toSet() + receipt))
                }
            }
        }
    }

    private suspend fun invalidateReceipt(
        proofEventId: String,
        requestId: PaykitPaymentRequestId,
        reconciliation: Reconciliation,
    ) = mutex.withLock {
        if (!isCurrent(reconciliation)) return@withLock
        val current = load()
        val matching = current.receipts.filter {
            it.wallet == reconciliation.wallet && it.identity == reconciliation.identity && it.requestId == requestId
        }.filter { it.proofEventId == proofEventId && it.verified }.toSet()
        if (matching.isEmpty()) return@withLock
        save(current.copy(receipts = current.receipts.map { if (it in matching) it.copy(verified = false) else it }))
    }

    private suspend fun paymentProof(quoteId: String, binding: UsdtPaymentProofBinding) =
        usdt.operation { this.paymentProof(quoteId, binding) }
    private suspend fun storedTransfer(id: String) = usdt.operation { wallet().history().firstOrNull { it.id == id } }
    private suspend fun verifyPayment(binding: UsdtPaymentProofBinding, proof: UsdtPaymentProof) =
        usdt.operation { wallet().verifyPaymentProof(binding, proof) }

    private suspend fun currentIdentity(priority: Priority = Priority.Ordered): String? =
        sdk.identityStatus(priority)?.takeIf { it.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE }
            ?.publicKey?.let(PubkyPublicKeyFormat::normalized)
}
