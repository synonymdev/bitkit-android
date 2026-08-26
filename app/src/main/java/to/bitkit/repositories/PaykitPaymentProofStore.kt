package to.bitkit.repositories

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaykitPaymentProofStore @Inject constructor(
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "PaykitPaymentProofStore"
        private val KEY = Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name
    }

    @Serializable
    private data class State(
        val proofs: List<PendingPaykitPaymentProof> = emptyList(),
    )

    fun load(): List<PendingPaykitPaymentProof> {
        val value = keychain.loadString(KEY) ?: return emptyList()
        return runCatching { Json.decodeFromString<State>(value).proofs }
            .getOrElse {
                Logger.warn("Discarded corrupt pending Paykit payment proof state", it, context = TAG)
                emptyList()
            }
    }

    fun completedRequestIdsAwaitingSubmission(identity: String): Set<PaykitPaymentRequestId> = load()
        .filter { PubkyPublicKeyFormat.matches(it.identity, identity) && it.proofData != null }
        .mapTo(mutableSetOf()) { it.requestId }

    fun inFlightRequestIds(identity: String): Set<PaykitPaymentRequestId> = load()
        .filter { PubkyPublicKeyFormat.matches(it.identity, identity) && it.paymentStarted }
        .mapTo(mutableSetOf()) { it.requestId }

    suspend fun save(proofs: List<PendingPaykitPaymentProof>) {
        if (proofs.isEmpty()) {
            keychain.delete(KEY)
        } else {
            keychain.upsertString(KEY, Json.encodeToString(State(proofs)))
        }
    }

    fun hasPendingProofs(): Boolean = keychain.exists(KEY)
}
