package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// CONTRACT (allowances port): the public API below is fixed; bodies are filled by the core worker.

sealed interface PaykitAllowanceEvent {
    data class PaidAutomatically(val counterparty: String, val amountSats: ULong, val paymentId: String) : PaykitAllowanceEvent
    data class LimitReached(val counterparty: String, val amountSats: ULong) : PaykitAllowanceEvent
    data object LedgerChanged : PaykitAllowanceEvent
}

enum class PaykitAllowanceAutoPayResult { NOT_COVERED, MANUAL, STARTED, COMPLETED }

@Singleton
class PaykitAllowanceRepo @Inject constructor() {
    private val _entries = MutableStateFlow<List<PaykitAllowanceEntry>>(emptyList())

    /** Grants grouped across a contact's links, newest first. */
    val entries: StateFlow<List<PaykitAllowanceEntry>> = _entries.asStateFlow()

    private val _autoPaidSats = MutableStateFlow<Map<String, ULong>>(emptyMap())

    /** Sats paid automatically per entry id, for "Paid so far". */
    val autoPaidSats: StateFlow<Map<String, ULong>> = _autoPaidSats.asStateFlow()

    private val _events = MutableSharedFlow<PaykitAllowanceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PaykitAllowanceEvent> = _events.asSharedFlow()

    fun entry(id: String): PaykitAllowanceEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun activate(identity: String?): Unit = TODO()

    fun deactivate(): Unit = TODO()

    suspend fun refresh(): Result<Unit> = TODO()

    suspend fun propose(contactPublicKey: String, limits: PaykitAllowanceLimits): Result<Unit> = TODO()

    suspend fun accept(entryId: String): Result<Unit> = TODO()

    suspend fun reject(entryId: String): Result<Unit> = TODO()

    suspend fun end(entryId: String): Result<Unit> = TODO()

    fun proposalForPresentation(): PaykitAllowanceEntry? = TODO()

    suspend fun markProposalPresented(entryId: String): Unit = TODO()

    /** Pays covered incoming requests headlessly; returns true when any request was handled. */
    suspend fun processIncomingRequests(requests: List<PaykitPaymentRequest>): Boolean = TODO()

    /** True while a request is covered by an active allowance or is being paid automatically: keep it off the Send sheet. */
    suspend fun isAutomaticallyHandling(request: PaykitPaymentRequest): Boolean = TODO()

    /** Request ids paid automatically, for the "Auto-paid" tag in payment history. */
    fun isAutoPaid(id: PaykitPaymentRequestId): Boolean = TODO()

    /** Reports a manual payment of a request to the allowance ledger; returns the attempt id or null when no ledger applies. */
    suspend fun beginManualPayment(request: PaykitPaymentRequest, paymentEndpointIdentifier: String): Result<String?> = TODO()

    suspend fun manualLightningPaymentSent(attemptId: String, paymentHash: String): Unit = TODO()

    /** [succeeded] null = outcome unknown (pending). */
    suspend fun finishManualPayment(attemptId: String, succeeded: Boolean?, transactionId: String? = null): Unit = TODO()

    suspend fun lightningPaymentSettled(paymentHash: String, succeeded: Boolean): Unit = TODO()

    suspend fun recover(): Unit = TODO()
}
