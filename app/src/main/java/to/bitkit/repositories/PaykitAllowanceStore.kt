package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Allowance state kept per identity: USD labels, the grouping of one grant across a contact's links, and the
 * execution journal that restart recovery reads. The SDK ledger stays authoritative for admission.
 */
@Serializable
data class PaykitAllowanceLocalState(
    val groups: List<Group> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    /** [PaykitAllowance.key]s of received proposals already shown. */
    val presentedProposalIds: Set<String> = emptySet(),
    /** [PaykitAllowance.key]s of offers being accepted, announced once their allowance is accepted. */
    val acceptingOfferIds: Set<String> = emptySet(),
    val notifiedRequestIds: Set<String> = emptySet(),
    val lastTrustedTimeMillis: Long? = null,
    /** Sats paid automatically per [PaykitAllowance.key], kept apart from the journal, which is trimmed. */
    val autoPaidSatsByAllowance: Map<String, ULong> = emptyMap(),
    /** Requests paid automatically, kept apart from the journal for the "Auto-paid" tag. */
    val autoPaidRequestIds: Set<PaykitPaymentRequestId> = emptySet(),
) {
    @Serializable
    data class Group(
        val id: String,
        val counterparty: String,
        val limits: PaykitAllowanceLimits,
        val allowanceIds: List<String>,
        val createdAtMillis: Long,
    )

    @Serializable
    enum class Stage { PREPARED, SUBMITTED, SENDING, SENT, SUCCEEDED, FAILED, UNKNOWN }

    @Serializable
    data class JournalEntry(
        val attemptId: String,
        val isAutomatic: Boolean,
        val requestId: PaykitPaymentRequestId,
        val allowanceId: String?,
        val amountSats: ULong,
        val paymentEndpointIdentifier: String,
        val paymentHash: String? = null,
        val onchainAddress: String? = null,
        val transactionId: String? = null,
        val stage: Stage,
        val createdAtMillis: Long,
        /** The payee's private payment list this attempt consumed, given back if the payment fails. */
        val paymentListVersion: ULong? = null,
        val previousPaymentListVersion: ULong? = null,
    )

    /** Allowance ids are unique per contact only, so a group matches on its contact as well. */
    fun group(containing: String, counterparty: String): Group? = groups.firstOrNull {
        containing in it.allowanceIds && PubkyPublicKeyFormat.matches(it.counterparty, counterparty)
    }
}

@Singleton
class PaykitAllowanceStore @Inject constructor(
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "PaykitAllowanceStore"
        private val KEY = Keychain.Key.PAYKIT_ALLOWANCE_STATE.name
        private val storeJson = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class Stored(
        val statesByIdentity: Map<String, PaykitAllowanceLocalState> = emptyMap(),
    )

    private val _backupStateVersion = MutableStateFlow(0L)

    /** Moves when backed-up state changes; a new trusted-time watermark alone does not ask for a backup. */
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    fun load(identity: String): PaykitAllowanceLocalState =
        runCatching { loadAll().statesByIdentity[storageKey(identity)] }
            .onFailure { Logger.warn("Failed to load Paykit allowance state", it, context = TAG) }
            .getOrNull()
            ?: PaykitAllowanceLocalState()

    suspend fun save(identity: String, state: PaykitAllowanceLocalState) {
        val stored = loadAll()
        val previous = stored.statesByIdentity[storageKey(identity)]
        val updated = stored.copy(statesByIdentity = stored.statesByIdentity + (storageKey(identity) to state))
        keychain.upsertString(KEY, storeJson.encodeToString(updated))
        if (previous?.withoutWatermark() != state.withoutWatermark()) _backupStateVersion.update { it + 1 }
    }

    /** Every identity's allowance state, for the wallet backup. */
    fun backupSnapshot(): Map<String, PaykitAllowanceLocalState> = loadAll().statesByIdentity

    /** Replaces the allowance state of each identity in [states], before recovery reads it. */
    suspend fun restoreBackup(states: Map<String, PaykitAllowanceLocalState>) {
        if (states.isEmpty()) return
        val stored = loadAll()
        val restored = states.mapKeys { (identity, _) -> storageKey(identity) }
        keychain.upsertString(KEY, storeJson.encodeToString(stored.copy(stored.statesByIdentity + restored)))
        _backupStateVersion.update { it + 1 }
    }

    private fun PaykitAllowanceLocalState.withoutWatermark() = copy(lastTrustedTimeMillis = null)

    private fun loadAll(): Stored {
        val value = keychain.loadString(KEY) ?: return Stored()
        return runCatching { storeJson.decodeFromString<Stored>(value) }
            .getOrElse {
                Logger.warn("Discarded corrupt Paykit allowance state", it, context = TAG)
                Stored()
            }
    }

    private fun storageKey(identity: String): String = PubkyPublicKeyFormat.normalized(identity) ?: identity
}
