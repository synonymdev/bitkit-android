package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.fromHex
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.WalletScope
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.utils.asNodeException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

/** New proof kinds must be readable on both platforms before either platform writes them to a wallet backup. */
@Serializable
enum class PaykitPaymentProofKind(val type: String) {
    Lightning("bitcoin-bolt11-preimage"),
    Onchain("bitcoin-onchain-txid");

    companion object {
        fun fromPaymentEndpointIdentifier(identifier: String): PaykitPaymentProofKind? {
            val method = MethodId.fromRawValue(identifier) ?: return null
            return if (method.isOnchain) Onchain else Lightning
        }
    }
}

@Serializable
data class PendingPaykitPaymentProof(
    val identity: String,
    val requestId: PaykitPaymentRequestId,
    val paymentEndpointIdentifier: String,
    val kind: PaykitPaymentProofKind,
    val paymentStarted: Boolean = false,
    val paymentIdentifier: String? = null,
    val proofData: String? = null,
    val billingPeriod: PaykitBillingPeriod? = null,
    val onchainAddress: String? = null,
    val onchainAmountSats: ULong? = null,
    val onchainWalletId: String = WalletScope.default,
    val onchainMatchingTransactionIdsBeforeAttempt: Set<String> = emptySet(),
    val onchainAcceptanceVerified: Boolean = false,
)

data class PaykitOnchainPaymentProofResolution(
    val identity: String,
    val requestId: PaykitPaymentRequestId,
    val transactionId: String,
    val walletId: String = WalletScope.default,
    val amountSats: ULong? = null,
)

@Singleton
@Suppress("TooManyFunctions")
class PaykitPaymentProofRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val lightningRepo: LightningRepo,
    private val store: PaykitPaymentProofStore,
    private val hwWalletRepo: HwWalletRepo,
) {
    companion object {
        private const val TAG = "PaykitPaymentProofRepo"
        private const val HASH_BYTE_COUNT = 32
        private val HARDWARE_OBSERVATION_TIMEOUT = 15.seconds
    }

    private val operationMutex = Mutex()

    suspend fun backupSnapshot(): List<PaykitPaymentStateBackup.Proof> = withContext(ioDispatcher) {
        operationMutex.withLock { store.load().map { PaykitPaymentStateBackup.Proof(it) } }
    }

    suspend fun restoreBackup(proofs: List<PaykitPaymentStateBackup.Proof>) = withContext(ioDispatcher) {
        val restored = proofs.map { it.restored() }
        operationMutex.withLock { persist(restored) }
    }
    private val _onchainPaymentResolutions = MutableStateFlow<List<PaykitOnchainPaymentProofResolution>>(emptyList())
    val onchainPaymentResolutions = _onchainPaymentResolutions.asStateFlow()

    suspend fun prepare(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        kind: PaykitPaymentProofKind,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val onchainAttempt = lightningRepo.currentOnchainSendAttempt()
            if (onchainAttempt?.requestId == request.id) throw PaykitPaymentRequestError.OperationInProgress
            operationMutex.withLock {
                val proof = pendingProof(request, paymentEndpointIdentifier, kind)
                val currentProofs = loadProofs()
                if (currentProofs.any { it.isStartedFor(proof.identity, request.id) }) {
                    throw PaykitPaymentRequestError.OperationInProgress
                }
                val alreadyPaid = paykitSdkService.paymentRequests().any { record ->
                    record.paymentRequestId == request.paymentRequestId &&
                        PubkyPublicKeyFormat.matches(record.counterparty, request.counterparty) &&
                        record.counterpartyReceiverPath == request.counterpartyReceiverPath &&
                        record.paymentProofs.any { it.billingPeriod.matches(request.billingPeriod) }
                }
                if (alreadyPaid) throw PaykitPaymentRequestError.OperationInProgress
                val proofs = currentProofs
                    .filterNot { it.isUnstartedFor(proof.identity, request.id) } +
                    proof
                persist(proofs)
            }
        }.onFailure { Logger.warn("Failed to prepare a Paykit payment proof", it, context = TAG) }
    }

    suspend fun associateLightningPayment(
        request: PaykitPaymentRequest,
        paymentHash: String,
        paymentEndpointIdentifier: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!paymentHash.isHex(HASH_BYTE_COUNT)) throw PaykitPaymentRequestError.RequestUnavailable
            val onchainAttempt = lightningRepo.currentOnchainSendAttempt()
            if (onchainAttempt?.requestId == request.id) throw PaykitPaymentRequestError.OperationInProgress
            val identity = currentIdentity() ?: throw PaykitPaymentRequestError.RequestUnavailable
            operationMutex.withLock {
                val proofs = loadProofs().toMutableList()
                if (proofs.any { it.isStartedFor(identity, request.id) }) {
                    throw PaykitPaymentRequestError.OperationInProgress
                }
                val index = proofs.indexOfLast {
                    PubkyPublicKeyFormat.matches(it.identity, identity) &&
                        it.requestId == request.id &&
                        it.kind == PaykitPaymentProofKind.Lightning &&
                        !it.paymentStarted &&
                        it.paymentIdentifier == null &&
                        it.proofData == null
                }
                val proof = if (index >= 0) {
                    proofs[index].copy(
                        paymentStarted = true,
                        paymentIdentifier = paymentHash.lowercase(),
                    )
                } else {
                    pendingProof(request, paymentEndpointIdentifier, PaykitPaymentProofKind.Lightning)
                        .copy(
                            paymentStarted = true,
                            paymentIdentifier = paymentHash.lowercase(),
                        )
                }
                if (index >= 0) proofs[index] = proof else proofs += proof
                persist(proofs)
            }
        }.onFailure { Logger.warn("Failed to associate a Paykit Lightning payment proof", it, context = TAG) }
    }

    suspend fun markOnchainPaymentStarted(
        request: PaykitPaymentRequest,
        address: String,
        walletId: String = WalletScope.default,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val identity = currentIdentity() ?: throw PaykitPaymentRequestError.RequestUnavailable
            operationMutex.withLock {
                val proofs = loadProofs().toMutableList()
                if (proofs.any { it.isStartedFor(identity, request.id) }) {
                    throw PaykitPaymentRequestError.OperationInProgress
                }
                val index = proofs.indexOfLast {
                    PubkyPublicKeyFormat.matches(it.identity, identity) &&
                        it.requestId == request.id &&
                        it.kind == PaykitPaymentProofKind.Onchain &&
                        !it.paymentStarted &&
                        it.paymentIdentifier == null &&
                        it.proofData == null
                }
                if (index < 0) throw PaykitPaymentRequestError.RequestUnavailable
                proofs[index] = proofs[index].copy(
                    paymentStarted = true,
                    onchainAddress = address,
                    onchainAmountSats = request.amountSats,
                    onchainWalletId = walletId,
                )
                persist(proofs)
            }
        }.onFailure { Logger.warn("Failed to mark a Paykit on-chain payment as started", it, context = TAG) }
    }

    suspend fun completeLightningPayment(paymentHash: String, preimage: String?) = withContext(ioDispatcher) {
        if (preimage == null) return@withContext
        if (!preimage.matchesPaymentHash(paymentHash)) {
            Logger.warn("Ignored a Paykit Lightning proof whose preimage did not match its payment hash", context = TAG)
            return@withContext
        }

        operationMutex.withLock {
            runSuspendCatching {
                val currentProofs = loadProofs()
                val matchingProofs = currentProofs.filter {
                    it.kind == PaykitPaymentProofKind.Lightning &&
                        it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
                }
                if (matchingProofs.isEmpty()) return@runSuspendCatching
                val proofs = currentProofs.map {
                    if (
                        it.kind == PaykitPaymentProofKind.Lightning &&
                        it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
                    ) {
                        it.copy(proofData = preimage.lowercase())
                    } else {
                        it
                    }
                }
                val completedProofs = proofs.filter {
                    it.kind == PaykitPaymentProofKind.Lightning &&
                        it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
                }
                persistAndSubmit(completedProofs, proofs)
            }.onFailure { Logger.warn("Failed to complete a Paykit Lightning payment proof", it, context = TAG) }
        }
    }

    suspend fun completeOnchainPayment(
        request: PaykitPaymentRequest,
        txid: String,
        paymentEndpointIdentifier: String,
        acceptedOutcome: OnchainSendOutcome.Accepted? = null,
    ): Boolean = withContext(ioDispatcher) {
        if (!txid.isHex(HASH_BYTE_COUNT)) {
            Logger.warn("Ignored a Paykit on-chain proof with an invalid transaction id", context = TAG)
            return@withContext false
        }

        val identity = currentIdentity() ?: return@withContext false
        if (acceptedOutcome != null && !acceptedOutcome.txid.equals(txid, ignoreCase = true)) return@withContext false
        val attempt = if (acceptedOutcome == null) {
            runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
        } else {
            null
        }
        fun hasPositiveEvidence(proof: PendingPaykitPaymentProof): Boolean = acceptedOutcome != null ||
            (attempt.matchesPositiveShopProof(proof) && attempt?.txid.equals(txid, ignoreCase = true))
        val fallbackProof = runSuspendCatching {
            pendingProof(request, paymentEndpointIdentifier, PaykitPaymentProofKind.Onchain)
        }.getOrNull()
        val completed = operationMutex.withLock {
            val completion = runSuspendCatching {
                val proofs = loadProofs().toMutableList()
                val index = proofs.indexOfLast {
                    PubkyPublicKeyFormat.matches(it.identity, identity) &&
                        it.requestId == request.id &&
                        it.kind == PaykitPaymentProofKind.Onchain &&
                        it.paymentStarted &&
                        it.paymentIdentifier == null &&
                        it.proofData == null
                }
                val proof = if (index >= 0) {
                    proofs[index].copy(
                        paymentIdentifier = txid.lowercase(),
                        proofData = txid.lowercase(),
                        onchainAcceptanceVerified = true,
                    )
                } else {
                    pendingProof(request, paymentEndpointIdentifier, PaykitPaymentProofKind.Onchain).copy(
                        paymentStarted = true,
                        paymentIdentifier = txid.lowercase(),
                        proofData = txid.lowercase(),
                        onchainAcceptanceVerified = true,
                    )
                }
                if (!hasPositiveEvidence(proof)) return@runSuspendCatching false
                if (index >= 0) proofs[index] = proof else proofs += proof
                val retained = persistAndSubmit(listOf(proof), proofs)
                if (retained) publishOnchainResolution(proof, txid)
                retained
            }
            completion.onFailure {
                Logger.warn(
                    "Failed to load a Paykit on-chain payment proof; attempting immediate delivery",
                    it,
                    context = TAG,
                )
            }
            if (completion.isFailure && fallbackProof != null && hasPositiveEvidence(fallbackProof)) {
                val proof = fallbackProof.copy(
                    paymentStarted = true,
                    paymentIdentifier = txid.lowercase(),
                    proofData = txid.lowercase(),
                    onchainAcceptanceVerified = true,
                )
                val delivered = runSuspendCatching { submitReady(proof) }
                    .onFailure { Logger.warn("Failed to complete a Paykit on-chain payment proof", it, context = TAG) }
                    .getOrDefault(false)
                if (delivered) publishOnchainResolution(proof, txid)
                delivered
            } else {
                completion.getOrDefault(false)
            }
        }
        if (completed) {
            runSuspendCatching { lightningRepo.completeAcceptedShopFollowup(request.id, txid) }
                .onFailure { Logger.warn("Failed to finish accepted Shop send locally", it, context = TAG) }
        }
        completed
    }

    suspend fun failLightningPayment(paymentHash: String) = removeProofs {
        it.kind == PaykitPaymentProofKind.Lightning && it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
    }

    suspend fun failLightningPayment(paymentHash: String, submissionError: Throwable): Boolean {
        val error = submissionError.asNodeException() ?: submissionError
        when (error) {
            is NodeNotRunningError,
            is NodeRunTimeoutError,
            is ServiceError.NodeNotSetup,
            is ServiceError.NodeNotStarted,
            is NodeException.NotRunning,
            is NodeException.InvalidInvoice,
            is NodeException.InvalidAmount,
            is NodeException.PaymentSendingFailed -> failLightningPayment(paymentHash)
            else -> return false
        }
        return true
    }

    suspend fun completeHardwareOnchainPayment(
        requestId: PaykitPaymentRequestId,
        walletId: String,
        txid: String,
        identity: String? = null,
    ): Boolean = withContext(ioDispatcher) {
        if (walletId == WalletScope.default || !txid.isHex(HASH_BYTE_COUNT)) return@withContext false
        val originalIdentity = identity?.let(PubkyPublicKeyFormat::normalized) ?: return@withContext false
        operationMutex.withLock {
            runSuspendCatching {
                val proofs = loadProofs().toMutableList()
                val index = proofs.indices.singleOrNull { index ->
                    val proof = proofs[index]
                    PubkyPublicKeyFormat.matches(proof.identity, originalIdentity) &&
                        proof.requestId == requestId && proof.onchainWalletId == walletId &&
                        proof.kind == PaykitPaymentProofKind.Onchain && proof.paymentStarted
                } ?: return@runSuspendCatching false
                val original = proofs[index]
                if ((original.paymentIdentifier != null && !original.paymentIdentifier.equals(txid, true)) ||
                    (original.proofData != null && !original.proofData.equals(txid, true))
                ) return@runSuspendCatching false
                // This id identifies the original lookup; it is not yet a payment proof or acceptance evidence.
                val pending = original.copy(paymentIdentifier = txid.lowercase())
                proofs[index] = pending
                persist(proofs)
                reconcileHardwareOnchainProof(pending)
            }.onFailure { Logger.warn("Failed to retain or observe the original hardware Shop payment", it, context = TAG) }
                .getOrDefault(false)
        }
    }

    suspend fun failOnchainPayment(request: PaykitPaymentRequest) {
        removeRequestProofs(request) {
            it.kind == PaykitPaymentProofKind.Onchain &&
                it.onchainWalletId == WalletScope.default &&
                it.paymentStarted &&
                it.paymentIdentifier == null &&
                it.proofData == null
        }
    }

    suspend fun cancelPreparation(request: PaykitPaymentRequest) {
        removeRequestProofs(request) {
            !it.paymentStarted &&
                it.paymentIdentifier == null &&
                it.proofData == null
        }
    }

    suspend fun protectedRequestIdsForSubscriptionCancellation(
        identity: String,
        subscriptionId: PaykitSubscriptionId,
    ): Result<Set<PaykitPaymentRequestId>> = withContext(ioDispatcher) {
        runSuspendCatching {
            operationMutex.withLock {
                val proofs = loadProofs()
                val belongsToSubscription: (PendingPaykitPaymentProof) -> Boolean = {
                    PubkyPublicKeyFormat.matches(it.identity, identity) &&
                        it.requestId.billingPeriodStartsAt != null &&
                        it.requestId.paymentRequestId == subscriptionId.paymentRequestId &&
                        it.requestId.counterparty == subscriptionId.counterparty &&
                        it.requestId.counterpartyReceiverPath == subscriptionId.counterpartyReceiverPath
                }
                val protectedRequestIds = proofs.filter(belongsToSubscription)
                    .filter { it.paymentStarted || it.paymentIdentifier != null || it.proofData != null }
                    .mapTo(mutableSetOf()) { it.requestId }
                val remainingProofs = proofs.filter {
                    !belongsToSubscription(it) ||
                        it.paymentStarted ||
                        it.paymentIdentifier != null ||
                        it.proofData != null
                }
                if (remainingProofs != proofs) persist(remainingProofs)
                protectedRequestIds
            }
        }.onFailure { Logger.warn("Failed to prepare Paykit subscription cancellation", it, context = TAG) }
    }

    suspend fun reconcile() = withContext(ioDispatcher) {
        val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }
            .onFailure {
                Logger.warn("Failed to read on-chain send evidence during proof reconciliation", it, context = TAG)
            }
            .getOrNull()
        if (attempt != null && attempt.hasPositiveEvidence && attempt.requestId != null) {
            runSuspendCatching { finishAlreadyQueuedOnchainProof(attempt) }
                .onFailure { Logger.warn("Failed to check queued Shop proof", it, context = TAG) }
        }
        if (!store.hasPendingProofs()) return@withContext

        var completedShopTxid: String? = null
        operationMutex.withLock {
            runSuspendCatching {
                val storedProofs = loadProofs()
                if (storedProofs.isEmpty()) {
                    persist(emptyList())
                    return@runSuspendCatching
                }
                val identityStatus = paykitSdkService.identityStatus()
                if (identityStatus?.liveSessionAvailable != true) return@runSuspendCatching
                val publicKey = identityStatus.publicKey ?: return@runSuspendCatching
                val identity = PubkyPublicKeyFormat.normalized(publicKey) ?: return@runSuspendCatching
                val proofs = storedProofs.filter { PubkyPublicKeyFormat.matches(it.identity, identity) }
                val payments = if (proofs.any { it.kind == PaykitPaymentProofKind.Lightning && it.proofData == null }) {
                    lightningRepo.getPayments().getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                proofs.forEach { proof ->
                    runSuspendCatching { reconcileProof(proof, payments, attempt) }
                        .onSuccess { if (it && proof.onchainWalletId == WalletScope.default) completedShopTxid = attempt?.txid }
                        .onFailure {
                            Logger.warn(
                                "Failed to reconcile a pending Paykit payment proof",
                                it,
                                context = TAG,
                            )
                        }
                }
            }.onFailure { Logger.error("Failed to reconcile pending Paykit payment proofs", it, context = TAG) }
        }
        val requestId = attempt?.requestId
        val txid = completedShopTxid
        if (requestId != null && txid != null) {
            runSuspendCatching { lightningRepo.completeAcceptedShopFollowup(requestId, txid) }
                .onFailure { Logger.warn("Failed to finish reconciled Shop send locally", it, context = TAG) }
        }
    }

    private suspend fun reconcileProof(
        proof: PendingPaykitPaymentProof,
        payments: List<PaymentDetails>,
        attempt: OnchainSendAttempt?,
    ): Boolean {
        return when {
            proof.kind == PaykitPaymentProofKind.Onchain && proof.proofData != null -> {
                if (!proof.paymentStarted || !proof.proofData.isHex(HASH_BYTE_COUNT) ||
                    !proof.paymentIdentifier.equals(proof.proofData, ignoreCase = true)
                ) return false
                if (proof.onchainAcceptanceVerified != true) {
                    if (proof.onchainWalletId != WalletScope.default) return reconcileHardwareOnchainProof(proof)
                    if (!attempt.matchesPositiveShopProof(proof)) return false
                    val proofs = loadProofs().toMutableList()
                    val index = proofs.indexOf(proof)
                    if (index < 0) return false
                    val verified = proof.copy(onchainAcceptanceVerified = true)
                    proofs[index] = verified
                    return persistAndSubmit(listOf(verified), proofs)
                }
                submitReady(proof)
                attempt.matchesPositiveShopProof(proof)
            }
            proof.proofData != null -> {
                submitReady(proof)
                false
            }
            proof.kind == PaykitPaymentProofKind.Onchain && proof.paymentStarted ->
                reconcileOnchainProof(proof, attempt)
            proof.kind == PaykitPaymentProofKind.Lightning -> {
                reconcileLightningProof(proof, payments)
                false
            }
            else -> false
        }
    }

    private suspend fun reconcileLightningProof(
        proof: PendingPaykitPaymentProof,
        payments: List<PaymentDetails>,
    ) {
        val paymentHash = proof.paymentIdentifier
        if (paymentHash == null) return
        val payment = payments.firstOrNull {
            it.direction == PaymentDirection.OUTBOUND && it.id.equals(paymentHash, ignoreCase = true)
        } ?: return
        when (payment.status) {
            PaymentStatus.PENDING -> Unit
            PaymentStatus.FAILED -> removeProofsLocked {
                it.kind == PaykitPaymentProofKind.Lightning &&
                    it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
            }
            PaymentStatus.SUCCEEDED -> {
                val preimage = (payment.kind as? PaymentKind.Bolt11)?.preimage
                if (preimage != null && preimage.matchesPaymentHash(paymentHash)) {
                    val completed = proof.copy(proofData = preimage.lowercase())
                    val proofs = loadProofs().toMutableList()
                    val index = proofs.indexOf(proof)
                    if (index >= 0) {
                        proofs[index] = completed
                        persistAndSubmit(listOf(completed), proofs)
                    }
                }
            }
        }
    }

    private suspend fun reconcileOnchainProof(
        proof: PendingPaykitPaymentProof,
        attempt: OnchainSendAttempt?,
    ): Boolean {
        if (proof.onchainWalletId != WalletScope.default) return reconcileHardwareOnchainProof(proof)
        if (!attempt.matchesPositiveShopProof(proof)) return false
        val txid = attempt?.txid ?: return false

        val proofs = loadProofs().toMutableList()
        val index = proofs.indexOf(proof)
        if (index < 0) return false
        val completed = proof.copy(
            paymentIdentifier = txid.lowercase(), proofData = txid.lowercase(), onchainAcceptanceVerified = true,
        )
        proofs[index] = completed
        val retained = persistAndSubmit(listOf(completed), proofs)
        if (retained) publishOnchainResolution(proof, txid)
        return retained
    }

    private suspend fun reconcileHardwareOnchainProof(proof: PendingPaykitPaymentProof): Boolean {
        val txid = proof.paymentIdentifier?.takeIf { it.isHex(HASH_BYTE_COUNT) } ?: return false
        if (!proof.paymentStarted || proof.onchainWalletId == WalletScope.default ||
            (proof.proofData != null && !proof.proofData.equals(txid, true))
        ) return false
        if (!proof.onchainAcceptanceVerified) {
            val address = proof.onchainAddress?.takeIf { it.isNotBlank() } ?: return false
            val amount = proof.onchainAmountSats ?: return false
            val observed = withTimeoutOrNull(HARDWARE_OBSERVATION_TIMEOUT) {
                hwWalletRepo.observeExactTransaction(proof.onchainWalletId, txid, address, amount).getOrDefault(false)
            } == true
            if (!observed) return false
        }
        val proofs = loadProofs().toMutableList()
        val index = proofs.indexOf(proof)
        if (index < 0) return false
        val completed = proof.copy(proofData = txid.lowercase(), onchainAcceptanceVerified = true)
        proofs[index] = completed
        val retained = persistAndSubmit(listOf(completed), proofs)
        if (retained) publishOnchainResolution(completed, txid)
        // Hardware completion never acknowledges an unrelated node-wallet guard.
        return retained
    }

    private fun OnchainSendAttempt?.matchesPositiveShopProof(proof: PendingPaykitPaymentProof): Boolean =
        this != null && hasPositiveEvidence && requestId == proof.requestId &&
            walletId == proof.onchainWalletId && txid?.isHex(HASH_BYTE_COUNT) == true &&
            (proof.proofData == null || proof.proofData.equals(txid, ignoreCase = true))

    private suspend fun finishAlreadyQueuedOnchainProof(attempt: OnchainSendAttempt) {
        val requestId = attempt.requestId ?: return
        val txid = attempt.txid?.takeIf { it.isHex(HASH_BYTE_COUNT) } ?: return
        val record = paykitSdkService.paymentRequests().firstOrNull {
            it.paymentRequestId == requestId.paymentRequestId &&
                PubkyPublicKeyFormat.matches(it.counterparty, requestId.counterparty) &&
                it.counterpartyReceiverPath == requestId.counterpartyReceiverPath
        } ?: return
        val expected = proofJson(PaykitPaymentProofKind.Onchain, txid.lowercase()).proofValues()
        if (record.paymentProofs.any {
                it.billingPeriod?.startsAt == requestId.billingPeriodStartsAt &&
                    it.proof.exportText().proofValues() == expected
            }
        ) {
            lightningRepo.completeAcceptedShopFollowup(requestId, txid)
        }
    }

    private fun publishOnchainResolution(proof: PendingPaykitPaymentProof, txid: String) {
        val resolution = PaykitOnchainPaymentProofResolution(
            identity = proof.identity,
            requestId = proof.requestId,
            transactionId = txid.lowercase(),
            walletId = proof.onchainWalletId,
            amountSats = proof.onchainAmountSats,
        )
        _onchainPaymentResolutions.update { resolutions ->
            if (resolution in resolutions) resolutions else resolutions + resolution
        }
    }

    fun consumeOnchainPaymentResolution(resolution: PaykitOnchainPaymentProofResolution) {
        _onchainPaymentResolutions.update { it - resolution }
    }

    fun clearOnchainPaymentResolutions() {
        _onchainPaymentResolutions.update { emptyList() }
    }

    private suspend fun currentIdentity(): String? = paykitSdkService.identityStatus()
        ?.publicKey
        ?.let(PubkyPublicKeyFormat::normalized)

    private suspend fun submitReady(proof: PendingPaykitPaymentProof): Boolean {
        if (proof.kind == PaykitPaymentProofKind.Onchain && proof.onchainAcceptanceVerified != true) return false
        val proofData = proof.proofData ?: return false
        val identityStatus = paykitSdkService.identityStatus()
        if (
            identityStatus?.liveSessionAvailable != true ||
            !PubkyPublicKeyFormat.matches(identityStatus.publicKey, proof.identity)
        ) {
            return false
        }

        val record = paykitSdkService.paymentRequests().firstOrNull {
            it.paymentRequestId == proof.requestId.paymentRequestId &&
                PubkyPublicKeyFormat.matches(it.counterparty, proof.requestId.counterparty) &&
                it.counterpartyReceiverPath == proof.requestId.counterpartyReceiverPath
        } ?: return false
        val proofJson = proofJson(proof.kind, proofData)
        val alreadyQueued = record.paymentProofs.any {
            it.billingPeriod.matches(proof.billingPeriod) &&
                it.paymentEndpointIdentifier == proof.paymentEndpointIdentifier &&
                it.proof.exportText().proofValues() == proofJson.proofValues()
        }
        if (!alreadyQueued) {
            paykitSdkService.submitPaymentProof(
                counterparty = proof.requestId.counterparty,
                counterpartyReceiverPath = proof.requestId.counterpartyReceiverPath,
                paymentRequestId = proof.requestId.paymentRequestId,
                paymentEndpointIdentifier = proof.paymentEndpointIdentifier,
                proofJson = proofJson,
                billingPeriod = proof.billingPeriod,
            )
            Logger.info("Queued a Paykit payment proof for private delivery", context = TAG)
            runSuspendCatching { paykitSdkService.processPendingPrivateMessages() }
                .onFailure {
                    Logger.warn(
                        "Paykit payment proof remains queued for private delivery",
                        it,
                        context = TAG,
                    )
                }
        }
        runSuspendCatching {
            removeProofsLocked {
                PubkyPublicKeyFormat.matches(it.identity, proof.identity) && it.requestId == proof.requestId
            }
        }.onFailure { Logger.warn("Failed to clear a submitted Paykit payment proof", it, context = TAG) }
        return true
    }

    private suspend fun removeProofs(predicate: (PendingPaykitPaymentProof) -> Boolean) = withContext(ioDispatcher) {
        operationMutex.withLock {
            runSuspendCatching { removeProofsLocked(predicate) }
                .onFailure { Logger.warn("Failed to clear a pending Paykit payment proof", it, context = TAG) }
        }
    }

    private suspend fun removeRequestProofs(
        request: PaykitPaymentRequest,
        predicate: (PendingPaykitPaymentProof) -> Boolean,
    ) = withContext(ioDispatcher) {
        val identity = currentIdentity()
        operationMutex.withLock {
            runSuspendCatching {
                val proofs = loadProofs()
                val candidateIdentities = proofs
                    .filter { it.requestId == request.id && predicate(it) }
                    .mapNotNull { PubkyPublicKeyFormat.normalized(it.identity) }
                    .distinct()
                val targetIdentity = identity ?: candidateIdentities.singleOrNull() ?: return@runSuspendCatching
                val remaining = proofs.filterNot {
                    it.requestId == request.id &&
                        PubkyPublicKeyFormat.matches(it.identity, targetIdentity) &&
                        predicate(it)
                }
                if (remaining != proofs) persist(remaining)
            }.onFailure { Logger.warn("Failed to clear a pending Paykit payment proof", it, context = TAG) }
        }
    }

    private suspend fun removeProofsLocked(predicate: (PendingPaykitPaymentProof) -> Boolean) {
        val current = loadProofs()
        val remaining = current.filterNot(predicate)
        if (remaining != current) persist(remaining)
    }

    private suspend fun persistAndSubmit(
        completedProofs: List<PendingPaykitPaymentProof>,
        allProofs: List<PendingPaykitPaymentProof>,
    ): Boolean {
        val didPersist = runSuspendCatching { persist(allProofs) }
            .onFailure {
                Logger.warn(
                    "Failed to persist a completed Paykit payment proof; attempting immediate delivery",
                    it,
                    context = TAG,
                )
            }.isSuccess
        var hasUndeliveredProof = false
        completedProofs.forEach { proof ->
            val wasDelivered = runSuspendCatching { submitReady(proof) }
                .onFailure { Logger.warn("Failed to queue a Paykit payment proof", it, context = TAG) }
                .getOrDefault(false)
            hasUndeliveredProof = hasUndeliveredProof || !wasDelivered
        }
        if (!didPersist && hasUndeliveredProof) {
            return runSuspendCatching { persist(allProofs) }
                .onFailure {
                    Logger.warn(
                        "Failed to retain a completed Paykit payment proof for retry",
                        it,
                        context = TAG,
                    )
                }.isSuccess
        }
        return didPersist || !hasUndeliveredProof
    }

    private suspend fun pendingProof(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        kind: PaykitPaymentProofKind,
    ): PendingPaykitPaymentProof {
        if (
            paymentEndpointIdentifier !in request.acceptedPaymentEndpointIdentifiers ||
            !endpointSupports(paymentEndpointIdentifier, kind)
        ) {
            throw PaykitPaymentRequestError.RequestUnavailable
        }
        val identityStatus = paykitSdkService.identityStatus()
        val identity = identityStatus?.publicKey?.let { PubkyPublicKeyFormat.normalized(it) }
        if (identityStatus?.liveSessionAvailable != true || identity == null) {
            throw PaykitPaymentRequestError.RequestUnavailable
        }
        return PendingPaykitPaymentProof(
            identity = identity,
            requestId = request.id,
            paymentEndpointIdentifier = paymentEndpointIdentifier,
            kind = kind,
            billingPeriod = request.billingPeriod,
        )
    }

    private fun loadProofs(): List<PendingPaykitPaymentProof> = store.load()

    private suspend fun persist(proofs: List<PendingPaykitPaymentProof>) {
        store.save(proofs)
    }
}

private fun BillingPeriod?.matches(period: PaykitBillingPeriod?): Boolean = when {
    this == null && period == null -> true
    this == null || period == null -> false
    else -> runCatching {
        Instant.parse(startsAt) == period.startsAt && Instant.parse(endsAt) == period.endsAt
    }.getOrDefault(false)
}

private fun endpointSupports(identifier: String, kind: PaykitPaymentProofKind): Boolean {
    val method = MethodId.fromRawValue(identifier) ?: return false
    return when (kind) {
        PaykitPaymentProofKind.Lightning -> method == MethodId.Bolt11 || method == MethodId.Lnurl
        PaykitPaymentProofKind.Onchain -> method.isOnchain
    }
}

private fun PendingPaykitPaymentProof.isStartedFor(
    identity: String,
    requestId: PaykitPaymentRequestId,
): Boolean = PubkyPublicKeyFormat.matches(this.identity, identity) &&
    this.requestId == requestId &&
    (paymentStarted || paymentIdentifier != null || proofData != null)

private fun PendingPaykitPaymentProof.isUnstartedFor(
    identity: String,
    requestId: PaykitPaymentRequestId,
): Boolean = PubkyPublicKeyFormat.matches(this.identity, identity) &&
    this.requestId == requestId &&
    !paymentStarted &&
    paymentIdentifier == null &&
    proofData == null

private fun proofJson(kind: PaykitPaymentProofKind, data: String): String = buildJsonObject {
    put("data", JsonPrimitive(data))
    put("type", JsonPrimitive(kind.type))
}.toString()

private fun String.proofValues(): JsonObject? = runCatching {
    Json.parseToJsonElement(this).jsonObject.let { values ->
        buildJsonObject {
            values["data"]?.jsonPrimitive?.contentOrNull?.let { put("data", JsonPrimitive(it)) }
            values["type"]?.jsonPrimitive?.contentOrNull?.let { put("type", JsonPrimitive(it)) }
        }
    }
}.getOrNull()

private fun String.matchesPaymentHash(paymentHash: String): Boolean {
    val preimage = hexBytes() ?: return false
    if (preimage.size != 32) return false
    val hash = MessageDigest.getInstance("SHA-256").digest(preimage).toHex()
    return hash.equals(paymentHash, ignoreCase = true)
}

private fun String.isHex(byteCount: Int): Boolean = hexBytes()?.size == byteCount

private fun String.hexBytes(): ByteArray? = runCatching { fromHex() }.getOrNull()
