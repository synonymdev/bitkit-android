package to.bitkit.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaykitPaymentRequestPresentationStore @Inject constructor(
    private val keychain: Keychain,
) {
    private val mutex = Mutex()

    @Serializable
    private data class State(
        val idsByIdentity: Map<String, List<PaykitPaymentRequestId>> = emptyMap(),
    )

    fun load(identity: String): Set<PaykitPaymentRequestId> {
        val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return emptySet()
        val value = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name) ?: return emptySet()
        return Json.decodeFromString<State>(value).idsByIdentity[normalizedIdentity].orEmpty().toSet()
    }

    suspend fun save(identity: String, ids: Set<PaykitPaymentRequestId>) {
        mutex.withLock {
            val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return@withLock
            val current = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name)
                ?.let { Json.decodeFromString<State>(it) }
                ?: State()
            val state = current.copy(idsByIdentity = current.idsByIdentity + (normalizedIdentity to ids.toList()))
            keychain.upsertString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name, Json.encodeToString(state))
        }
    }
}
