package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaykitPaymentProofStore @Inject constructor(
    private val keychain: Keychain,
) {
    companion object {
        private val KEY = Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name
    }

    @Serializable
    private data class State(
        val proofs: List<PendingPaykitPaymentProof> = emptyList(),
    )

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()

    fun load(): List<PendingPaykitPaymentProof> {
        val value = keychain.loadString(KEY) ?: return emptyList()
        return Json.decodeFromString<State>(value).proofs
    }

    fun completedRequestProofKindsAwaitingSubmission(
        identity: String,
    ): Map<PaykitPaymentRequestId, PaykitPaymentProofKind> = load()
        .filter { PubkyPublicKeyFormat.matches(it.identity, identity) && it.proofData != null }
        .associate { it.requestId to it.kind }

    fun inFlightRequestIds(identity: String): Set<PaykitPaymentRequestId> = load()
        .filter { PubkyPublicKeyFormat.matches(it.identity, identity) && it.paymentStarted }
        .mapTo(mutableSetOf()) { it.requestId }

    suspend fun save(proofs: List<PendingPaykitPaymentProof>) {
        if (proofs.isEmpty()) {
            keychain.delete(KEY)
        } else {
            keychain.upsertString(KEY, Json.encodeToString(State(proofs)))
        }
        _backupStateVersion.update { it + 1 }
    }

    fun hasPendingProofs(): Boolean = keychain.exists(KEY)
}
