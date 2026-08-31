@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

data class PaykitSubscriptionPresentationState(
    val acceptedAt: Map<PaykitSubscriptionId, Instant> = emptyMap(),
    val presentedProposalIds: Set<PaykitSubscriptionId> = emptySet(),
    val dismissedPaymentIds: Set<PaykitPaymentRequestId> = emptySet(),
)

@Singleton
class PaykitPaymentRequestPresentationStore @Inject constructor(
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "PaykitPaymentRequestPresentationStore"
    }

    private val mutex = Mutex()

    @Serializable
    private data class State(
        val idsByIdentity: Map<String, List<PaykitPaymentRequestId>> = emptyMap(),
        val subscriptionStatesByIdentity: Map<String, SubscriptionState> = emptyMap(),
    )

    @Serializable
    private data class SubscriptionState(
        val acceptances: List<SubscriptionAcceptance> = emptyList(),
        val presentedProposalIds: List<PaykitSubscriptionId> = emptyList(),
        val dismissedPaymentIds: List<PaykitPaymentRequestId> = emptyList(),
    )

    @Serializable
    private data class SubscriptionAcceptance(
        val id: PaykitSubscriptionId,
        val acceptedAt: String,
    )

    fun load(identity: String): Set<PaykitPaymentRequestId> {
        val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return emptySet()
        val value = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name) ?: return emptySet()
        return decode(value).idsByIdentity[normalizedIdentity].orEmpty().toSet()
    }

    suspend fun save(identity: String, ids: Set<PaykitPaymentRequestId>) {
        mutex.withLock {
            val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return@withLock
            val current = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name)
                ?.let(::decode)
                ?: State()
            val state = current.copy(idsByIdentity = current.idsByIdentity + (normalizedIdentity to ids.toList()))
            keychain.upsertString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name, Json.encodeToString(state))
        }
    }

    fun loadSubscriptionState(identity: String): PaykitSubscriptionPresentationState {
        val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity)
            ?: return PaykitSubscriptionPresentationState()
        val value = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name)
            ?: return PaykitSubscriptionPresentationState()
        val state = decode(value).subscriptionStatesByIdentity[normalizedIdentity]
            ?: return PaykitSubscriptionPresentationState()
        val acceptedAt = state.acceptances.mapNotNull { acceptance ->
            runCatching { Instant.parse(acceptance.acceptedAt) }
                .getOrNull()
                ?.let { acceptance.id to it }
        }
            .toMap()
        return PaykitSubscriptionPresentationState(
            acceptedAt = acceptedAt,
            presentedProposalIds = state.presentedProposalIds.toSet(),
            dismissedPaymentIds = state.dismissedPaymentIds.toSet(),
        )
    }

    suspend fun saveSubscriptionState(
        identity: String,
        subscriptionState: PaykitSubscriptionPresentationState,
    ) {
        mutex.withLock {
            val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return@withLock
            val current = keychain.loadString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name)
                ?.let(::decode)
                ?: State()
            val storedState = SubscriptionState(
                acceptances = subscriptionState.acceptedAt.map { SubscriptionAcceptance(it.key, it.value.toString()) },
                presentedProposalIds = subscriptionState.presentedProposalIds.toList(),
                dismissedPaymentIds = subscriptionState.dismissedPaymentIds.toList(),
            )
            val state = current.copy(
                subscriptionStatesByIdentity = current.subscriptionStatesByIdentity +
                    (normalizedIdentity to storedState),
            )
            keychain.upsertString(Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name, Json.encodeToString(state))
        }
    }

    private fun decode(value: String): State = runCatching { Json.decodeFromString<State>(value) }
        .getOrElse {
            Logger.warn("Discarded corrupt Paykit payment request presentation state", context = TAG)
            State()
        }
}
