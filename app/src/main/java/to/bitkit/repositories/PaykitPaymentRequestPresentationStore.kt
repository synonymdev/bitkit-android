@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.PubkyPublicKeyFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ExperimentalTime
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
        private val KEY = Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name
    }

    private val mutex = Mutex()
    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()

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
        val value = keychain.loadString(KEY) ?: return emptySet()
        return decode(value).idsByIdentity[normalizedIdentity].orEmpty().toSet()
    }

    suspend fun save(identity: String, ids: Set<PaykitPaymentRequestId>) {
        mutex.withLock {
            val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return@withLock
            val current = keychain.loadString(KEY)
                ?.let(::decode)
                ?: State()
            val state = current.copy(idsByIdentity = current.idsByIdentity + (normalizedIdentity to ids.toList()))
            keychain.upsertString(KEY, Json.encodeToString(state))
        }
    }

    fun loadSubscriptionState(identity: String): PaykitSubscriptionPresentationState {
        val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity)
            ?: return PaykitSubscriptionPresentationState()
        val value = keychain.loadString(KEY)
            ?: return PaykitSubscriptionPresentationState()
        val state = decode(value).subscriptionStatesByIdentity[normalizedIdentity]
            ?: return PaykitSubscriptionPresentationState()
        val acceptedAt = state.acceptances.associate { it.id to Instant.parse(it.acceptedAt) }
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
            val current = keychain.loadString(KEY)
                ?.let(::decode)
                ?: State()
            val currentBackup = current.subscriptionStatesByIdentity[normalizedIdentity]?.backupOrNull()
            val storedState = SubscriptionState(
                acceptances = subscriptionState.acceptedAt.map { SubscriptionAcceptance(it.key, it.value.toString()) },
                presentedProposalIds = subscriptionState.presentedProposalIds.toList(),
                dismissedPaymentIds = subscriptionState.dismissedPaymentIds.toList(),
            )
            val state = current.copy(
                subscriptionStatesByIdentity = current.subscriptionStatesByIdentity +
                    (normalizedIdentity to storedState),
            )
            keychain.upsertString(KEY, Json.encodeToString(state))
            if (currentBackup != storedState.backupOrNull()) {
                _backupStateVersion.update { it + 1 }
            }
        }
    }

    fun backupSnapshot(): Map<String, PaykitPaymentStateBackup.Subscription> {
        val value = keychain.loadString(KEY) ?: return emptyMap()
        return decode(value).subscriptionStatesByIdentity.mapNotNull { (identity, state) ->
            state.backupOrNull()?.let { identity to it }
        }.toMap()
    }

    suspend fun restoreBackup(subscriptions: Map<String, PaykitPaymentStateBackup.Subscription>) {
        val restored = subscriptions.mapValues { (_, state) ->
            state.restored()
            SubscriptionState(
                acceptances = state.acceptances.map { SubscriptionAcceptance(it.id, it.acceptedAt) },
                presentedProposalIds = state.presentedProposalIds.toList(),
            )
        }
        mutex.withLock {
            val state = State(subscriptionStatesByIdentity = restored)
            keychain.upsertString(KEY, Json.encodeToString(state))
            _backupStateVersion.update { it + 1 }
        }
    }

    private fun SubscriptionState.backupOrNull(): PaykitPaymentStateBackup.Subscription? {
        if (acceptances.isEmpty() && presentedProposalIds.isEmpty()) return null

        return PaykitPaymentStateBackup.Subscription(
            acceptances = acceptances.map { PaykitPaymentStateBackup.Acceptance(it.id, it.acceptedAt) },
            presentedProposalIds = presentedProposalIds.toSet(),
        )
    }

    private fun decode(value: String): State = runCatching {
        Json.decodeFromString<State>(value).also { state ->
            state.subscriptionStatesByIdentity.values.forEach { subscription ->
                subscription.acceptances.forEach { Instant.parse(it.acceptedAt) }
            }
        }
    }.getOrElse { throw PaykitPaymentStateUnreadableError(KEY, it) }
}
