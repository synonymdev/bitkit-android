package to.bitkit.repositories

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaykitPaymentProofStore @Inject constructor(
    private val keychain: Keychain,
) {
    @Serializable
    private data class State(
        val proofs: List<PendingPaykitPaymentProof> = emptyList(),
    )

    fun load(): List<PendingPaykitPaymentProof> {
        val value = keychain.loadString(Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name) ?: return emptyList()
        return Json.decodeFromString<State>(value).proofs
    }

    suspend fun save(proofs: List<PendingPaykitPaymentProof>) {
        keychain.upsertString(
            Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name,
            Json.encodeToString(State(proofs)),
        )
    }
}
