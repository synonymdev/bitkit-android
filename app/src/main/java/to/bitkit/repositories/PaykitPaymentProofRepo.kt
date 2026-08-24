package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.fromHex
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class PaykitPaymentProofKind(val type: String) {
    Lightning("bitcoin-bolt11-preimage"),
    Onchain("bitcoin-onchain-txid"),
}

@Serializable
data class PendingPaykitPaymentProof(
    val identity: String,
    val requestId: PaykitPaymentRequestId,
    val paymentEndpointIdentifier: String,
    val kind: PaykitPaymentProofKind,
    val paymentIdentifier: String? = null,
    val proofData: String? = null,
)

@Singleton
class PaykitPaymentProofRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val lightningRepo: LightningRepo,
    private val store: PaykitPaymentProofStore,
) {
    companion object {
        private const val TAG = "PaykitPaymentProofRepo"
        private const val HASH_BYTE_COUNT = 32
    }

    private val operationMutex = Mutex()
    private var pendingProofs: List<PendingPaykitPaymentProof>? = null

    suspend fun prepare(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        kind: PaykitPaymentProofKind,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            operationMutex.withLock {
                require(paymentEndpointIdentifier in request.acceptedPaymentEndpointIdentifiers)
                require(endpointSupports(paymentEndpointIdentifier, kind))
                val identityStatus = paykitSdkService.identityStatus()
                check(identityStatus?.liveSessionAvailable == true)
                val publicKey = checkNotNull(identityStatus.publicKey)
                val identity = checkNotNull(PubkyPublicKeyFormat.normalized(publicKey))
                val proofs = loadProofs()
                    .filterNot { PubkyPublicKeyFormat.matches(it.identity, identity) && it.requestId == request.id } +
                    PendingPaykitPaymentProof(
                        identity = identity,
                        requestId = request.id,
                        paymentEndpointIdentifier = paymentEndpointIdentifier,
                        kind = kind,
                    )
                persist(proofs)
            }
        }.onFailure { Logger.warn("Failed to prepare a Paykit payment proof", it, context = TAG) }
    }

    suspend fun associateLightningPayment(request: PaykitPaymentRequest, paymentHash: String): Result<Unit> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                require(paymentHash.isHex(HASH_BYTE_COUNT))
                operationMutex.withLock {
                    val proofs = loadProofs().toMutableList()
                    val index = proofs.indexOfFirst {
                        it.requestId == request.id && it.kind == PaykitPaymentProofKind.Lightning
                    }
                    check(index >= 0)
                    proofs[index] = proofs[index].copy(paymentIdentifier = paymentHash.lowercase())
                    persist(proofs)
                }
            }.onFailure { Logger.warn("Failed to associate a Paykit Lightning payment proof", it, context = TAG) }
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
                val matchingRequestIds = matchingProofs.map { it.requestId }.toSet()
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
                persist(proofs)
                proofs.filter { it.requestId in matchingRequestIds }
                    .forEach { submitReady(it) }
            }.onFailure { Logger.warn("Failed to complete a Paykit Lightning payment proof", it, context = TAG) }
        }
    }

    suspend fun completeOnchainPayment(request: PaykitPaymentRequest, txid: String) = withContext(ioDispatcher) {
        if (!txid.isHex(HASH_BYTE_COUNT)) {
            Logger.warn("Ignored a Paykit on-chain proof with an invalid transaction id", context = TAG)
            return@withContext
        }

        operationMutex.withLock {
            runSuspendCatching {
                val proofs = loadProofs().toMutableList()
                val index = proofs.indexOfFirst {
                    it.requestId == request.id && it.kind == PaykitPaymentProofKind.Onchain
                }
                if (index < 0) return@runSuspendCatching
                val proof = proofs[index].copy(
                    paymentIdentifier = txid.lowercase(),
                    proofData = txid.lowercase(),
                )
                proofs[index] = proof
                persist(proofs)
                submitReady(proof)
            }.onFailure { Logger.warn("Failed to complete a Paykit on-chain payment proof", it, context = TAG) }
        }
    }

    suspend fun failLightningPayment(paymentHash: String) = removeProofs {
        it.kind == PaykitPaymentProofKind.Lightning && it.paymentIdentifier.equals(paymentHash, ignoreCase = true)
    }

    suspend fun cancel(request: PaykitPaymentRequest) = removeProofs { it.requestId == request.id }

    suspend fun reconcile() = withContext(ioDispatcher) {
        operationMutex.withLock {
            runSuspendCatching {
                val identityStatus = paykitSdkService.identityStatus()
                if (identityStatus?.liveSessionAvailable != true) return@runSuspendCatching
                val publicKey = identityStatus.publicKey ?: return@runSuspendCatching
                val identity = PubkyPublicKeyFormat.normalized(publicKey) ?: return@runSuspendCatching
                val proofs = loadProofs().filter { PubkyPublicKeyFormat.matches(it.identity, identity) }
                val payments = if (proofs.any { it.kind == PaykitPaymentProofKind.Lightning && it.proofData == null }) {
                    lightningRepo.getPayments().getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                proofs.forEach { reconcileProof(it, payments) }
            }.onFailure { Logger.warn("Failed to reconcile pending Paykit payment proofs", it, context = TAG) }
        }
    }

    private suspend fun reconcileProof(
        proof: PendingPaykitPaymentProof,
        payments: List<PaymentDetails>,
    ) {
        if (proof.proofData != null) {
            submitReady(proof)
            return
        }
        val paymentHash = proof.paymentIdentifier
        if (proof.kind != PaykitPaymentProofKind.Lightning || paymentHash == null) return
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
                    replaceProof(completed)
                    submitReady(completed)
                }
            }
        }
    }

    private suspend fun submitReady(proof: PendingPaykitPaymentProof) {
        val proofData = proof.proofData ?: return
        val identityStatus = paykitSdkService.identityStatus()
        if (
            identityStatus?.liveSessionAvailable != true ||
            !PubkyPublicKeyFormat.matches(identityStatus.publicKey, proof.identity)
        ) {
            return
        }

        val record = paykitSdkService.paymentRequests().firstOrNull {
            it.paymentRequestId == proof.requestId.paymentRequestId &&
                PubkyPublicKeyFormat.matches(it.counterparty, proof.requestId.counterparty) &&
                it.counterpartyReceiverPath == proof.requestId.counterpartyReceiverPath
        } ?: return
        val proofJson = proofJson(proof.kind, proofData)
        val alreadyQueued = record.paymentProofs.any {
            it.billingPeriod == null &&
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
            )
            Logger.info("Queued a Paykit payment proof for private delivery", context = TAG)
        }
        removeProofsLocked { it == proof }
        runSuspendCatching { paykitSdkService.processPendingPrivateMessages() }
            .onFailure { Logger.warn("Paykit payment proof remains queued for private delivery", it, context = TAG) }
    }

    private suspend fun removeProofs(predicate: (PendingPaykitPaymentProof) -> Boolean) = withContext(ioDispatcher) {
        operationMutex.withLock {
            runSuspendCatching { removeProofsLocked(predicate) }
                .onFailure { Logger.warn("Failed to clear a pending Paykit payment proof", it, context = TAG) }
        }
    }

    private suspend fun removeProofsLocked(predicate: (PendingPaykitPaymentProof) -> Boolean) {
        val current = loadProofs()
        val remaining = current.filterNot(predicate)
        if (remaining != current) persist(remaining)
    }

    private suspend fun replaceProof(proof: PendingPaykitPaymentProof) {
        persist(loadProofs().map { if (it.requestId == proof.requestId) proof else it })
    }

    private fun loadProofs(): List<PendingPaykitPaymentProof> =
        pendingProofs ?: store.load().also { pendingProofs = it }

    private suspend fun persist(proofs: List<PendingPaykitPaymentProof>) {
        store.save(proofs)
        pendingProofs = proofs
    }
}

private fun endpointSupports(identifier: String, kind: PaykitPaymentProofKind): Boolean {
    val method = MethodId.fromRawValue(identifier) ?: return false
    return when (kind) {
        PaykitPaymentProofKind.Lightning -> method == MethodId.Bolt11 || method == MethodId.Lnurl
        PaykitPaymentProofKind.Onchain -> method.isOnchain
    }
}

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
