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

/**
 * Local Allowance state kept per identity: USD labels, the grouping of one grant across a contact's links, and the
 * execution journal that restart recovery reads. The SDK ledger stays authoritative for admission.
 */
@Serializable
data class PaykitAllowanceLocalState(
    val groups: List<Group> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val presentedProposalIds: Set<String> = emptySet(),
    val notifiedRequestIds: Set<String> = emptySet(),
    val lastTrustedTimeMillis: Long? = null,
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
    )

    fun group(containing: String): Group? = groups.firstOrNull { containing in it.allowanceIds }
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

    fun load(identity: String): PaykitAllowanceLocalState =
        runCatching { loadAll().statesByIdentity[storageKey(identity)] }
            .onFailure { Logger.warn("Failed to load Paykit allowance state", it, context = TAG) }
            .getOrNull()
            ?: PaykitAllowanceLocalState()

    suspend fun save(identity: String, state: PaykitAllowanceLocalState) {
        val stored = loadAll()
        val updated = stored.copy(statesByIdentity = stored.statesByIdentity + (storageKey(identity) to state))
        keychain.upsertString(KEY, storeJson.encodeToString(updated))
    }

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
