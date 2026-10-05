package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.models.ActiveOnchainAttemptBackup
import to.bitkit.services.LightningService
import to.bitkit.utils.AppError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OnchainSendOutcome {
    val txid: String

    data class Accepted(override val txid: String) : OnchainSendOutcome
    data class Rejected(override val txid: String, val reason: String) : OnchainSendOutcome
    data class Unknown(override val txid: String) : OnchainSendOutcome
}

@Serializable
enum class OnchainSendEvidence {
    Pending,
    Accepted,
    Rejected,
    Unknown,
    Observed,
}

@Serializable
data class OnchainTransferContext(
    val txTotalSats: ULong,
    val preTransferOnchainSats: ULong,
    val originalOrderClientBalanceSats: ULong? = null,
    val originalOrderFeeSats: ULong? = null,
)

@Serializable
data class OnchainSendInput(val txid: String, val vout: UInt)

data class OnchainPreparedReceipt(
    val txid: String,
    val inputs: List<OnchainSendInput>,
    val address: String,
    val amountSats: ULong,
)

@Serializable
@Suppress("LongParameterList")
data class OnchainSendAttempt(
    val walletId: String,
    val attemptId: String,
    val requestId: PaykitPaymentRequestId?,
    val orderId: String?,
    val address: String,
    val amountSats: ULong,
    val isMaxAmount: Boolean,
    val feeRateSatsPerVByte: ULong,
    val isTransfer: Boolean,
    val channelId: String?,
    val tags: List<String>,
    val evidence: OnchainSendEvidence = OnchainSendEvidence.Pending,
    val txid: String? = null,
    val refusalReason: String? = null,
    val localFollowupComplete: Boolean = false,
    val walletIndex: Int = 0,
    val transferContext: OnchainTransferContext? = null,
    val payerIdentity: String? = null,
    val originalInputs: List<OnchainSendInput>? = null,
    val candidateTxids: List<String> = emptyList(),
    val backupFollowup: ActiveOnchainAttemptBackup.Followup? = null,
    val restoredFromBackup: Boolean = false,
) {
    val isUnresolved: Boolean
        get() = evidence == OnchainSendEvidence.Pending ||
            evidence == OnchainSendEvidence.Rejected ||
            evidence == OnchainSendEvidence.Unknown

    val hasPositiveEvidence: Boolean
        get() = evidence == OnchainSendEvidence.Accepted || evidence == OnchainSendEvidence.Observed

    val blocksNextSend: Boolean
        get() = isUnresolved || (hasPositiveEvidence && !localFollowupComplete)
}

class OnchainSendBlockedError(val attempt: OnchainSendAttempt? = null) :
    AppError("A previous on-chain send is unresolved or this payment was already sent.")

class OnchainSendAttemptUnreadableError(cause: Throwable) : AppError("Failed to read on-chain send attempt", cause)

class OnchainSendNotDispatchedError(cause: Throwable) : AppError("On-chain send did not reach the backend", cause)

class OnchainSendPendingError(cause: Throwable, val txid: String? = null) :
    AppError("On-chain send outcome is unknown; do not send again", cause)

@Singleton
class OnchainSendAttemptStore @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningService: LightningService,
) {
    companion object {
        private val KEY = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name
    }

    private val mutex = Mutex()
    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()

    // One known positive result per existing wallet guard; entries disappear after a successful durable write.
    private val retainedPositive = mutableMapOf<Int, OnchainSendAttempt>()

    suspend fun current(): OnchainSendAttempt? = withContext(ioDispatcher) {
        mutex.withLock { loadWithRetainedAccepted(lightningService.currentWalletIndex) }
    }

    @Suppress("LongParameterList")
    suspend fun admit(
        walletId: String,
        requestId: PaykitPaymentRequestId?,
        orderId: String?,
        address: String,
        amountSats: ULong,
        isMaxAmount: Boolean,
        feeRateSatsPerVByte: ULong,
        isTransfer: Boolean,
        channelId: String?,
        tags: List<String>,
        transferContext: OnchainTransferContext? = null,
        beforeSendAttempt: suspend () -> Unit,
        payerIdentity: String? = null,
    ): OnchainSendAttempt = withContext(ioDispatcher) {
        mutex.withLock {
            val walletIndex = lightningService.currentWalletIndex
            val previous = load(walletIndex)
            if (previous != null && (
                    previous.walletId != walletId ||
                        previous.blocksNextSend ||
                        (requestId != null && previous.requestId == requestId) ||
                        (orderId != null && previous.orderId == orderId)
                    )
            ) {
                throw OnchainSendBlockedError(loadWithRetainedAccepted(walletIndex) ?: previous)
            }
            beforeSendAttempt()
            val attempt = OnchainSendAttempt(
                walletId = walletId,
                attemptId = UUID.randomUUID().toString(),
                requestId = requestId,
                orderId = orderId,
                address = address,
                amountSats = amountSats,
                isMaxAmount = isMaxAmount,
                feeRateSatsPerVByte = feeRateSatsPerVByte,
                isTransfer = isTransfer,
                channelId = channelId,
                tags = tags,
                walletIndex = walletIndex,
                transferContext = transferContext,
                payerIdentity = payerIdentity,
                backupFollowup = ActiveOnchainAttemptBackup.Followup(
                    feeSats = "0",
                    tags = tags,
                    createdAtMillis = System.currentTimeMillis().toString(),
                    channelId = channelId,
                ),
            )
            persist(attempt)
            attempt
        }
    }

    suspend fun retainPreparedReceipt(
        attemptId: String,
        walletIndex: Int,
        receipt: OnchainPreparedReceipt,
        isRecovery: Boolean,
    ): OnchainSendAttempt = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId != attemptId || walletIndex != lightningService.currentWalletIndex ||
                current.hasPositiveEvidence
            ) {
                throw OnchainSendBlockedError(current)
            }
            require(receipt.txid.matches(Regex("[0-9a-fA-F]{64}")))
            require(receipt.inputs.isNotEmpty() && receipt.inputs.distinct().size == receipt.inputs.size)
            require(receipt.inputs.all { it.txid.matches(Regex("[0-9a-fA-F]{64}")) })
            require(receipt.address == current.address)
            if (current.requestId != null) require(!current.payerIdentity.isNullOrBlank())
            require(receipt.amountSats > 0uL)
            if (isRecovery) {
                requireNotNull(current.originalInputs)
                require(current.candidateTxids.isNotEmpty())
                require(receipt.inputs.toSet() == current.originalInputs.toSet())
                require(receipt.amountSats == current.amountSats)
            } else {
                require(current.originalInputs == null && current.candidateTxids.isEmpty() && current.txid == null)
                require((current.isMaxAmount && current.requestId == null) || receipt.amountSats == current.amountSats)
            }
            current.copy(
                amountSats = receipt.amountSats,
                originalInputs = current.originalInputs ?: receipt.inputs,
                candidateTxids = (current.candidateTxids + receipt.txid.lowercase()).distinct(),
                txid = receipt.txid.lowercase(),
                evidence = OnchainSendEvidence.Pending,
                refusalReason = null,
            ).also { persist(it) }
        }
    }

    suspend fun broadcastPreparedCandidate(
        attemptId: String,
        walletIndex: Int,
        txid: String,
        broadcast: suspend () -> OnchainSendOutcome,
    ): OnchainSendOutcome = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId != attemptId || walletIndex != lightningService.currentWalletIndex) {
                throw OnchainSendBlockedError(current)
            }
            if (current.hasPositiveEvidence) return@withLock OnchainSendOutcome.Accepted(requireNotNull(current.txid))
            require(current.txid.equals(txid, ignoreCase = true) && txid.lowercase() in current.candidateTxids)
            broadcast()
        }
    }

    suspend fun recordOutcome(attemptId: String, outcome: OnchainSendOutcome, walletIndex: Int): OnchainSendAttempt =
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val current = loadWithRetainedAccepted(walletIndex)
                if (current?.attemptId != attemptId) throw OnchainSendBlockedError()
                if (current.candidateTxids.isNotEmpty() &&
                    outcome.txid.lowercase() !in current.candidateTxids
                ) {
                    throw OnchainSendBlockedError(current)
                }
                if (current.hasPositiveEvidence) return@withLock current
                val evidence = when (outcome) {
                    is OnchainSendOutcome.Accepted -> OnchainSendEvidence.Accepted
                    is OnchainSendOutcome.Rejected -> OnchainSendEvidence.Rejected
                    is OnchainSendOutcome.Unknown -> OnchainSendEvidence.Unknown
                }
                val recorded = current.copy(
                    evidence = evidence,
                    txid = outcome.txid,
                    refusalReason = (outcome as? OnchainSendOutcome.Rejected)?.reason,
                )
                if (outcome is OnchainSendOutcome.Accepted) retainedPositive[walletIndex] = recorded
                persist(recorded)
                retainedPositive.remove(walletIndex)
                recorded
            }
        }

    suspend fun releaseBeforeDispatch(attemptId: String, walletIndex: Int) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId == attemptId && current.evidence == OnchainSendEvidence.Pending &&
                current.candidateTxids.isEmpty()
            ) {
                keychain.delete(KEY, walletIndex)
                _backupStateVersion.update { it + 1 }
            }
        }
    }

    suspend fun markLocalFollowupComplete(attemptId: String, walletIndex: Int) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId == attemptId && current.hasPositiveEvidence) {
                check(
                    (!current.restoredFromBackup || current.backupFollowup != null) &&
                        current.backupFollowup?.contact == null
                ) {
                    "Original local follow-up context is unavailable"
                }
                persist(current.copy(localFollowupComplete = true))
                retainedPositive.remove(walletIndex)
            }
        }
    }

    suspend fun observeExactTransaction(txid: String): OnchainSendAttempt? =
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val current = loadWithRetainedAccepted(lightningService.currentWalletIndex) ?: return@withLock null
                val matchesCandidate = current.txid.equals(txid, ignoreCase = true) ||
                    txid.lowercase() in current.candidateTxids
                if (!matchesCandidate || !current.isUnresolved) return@withLock null
                val observed = current.copy(evidence = OnchainSendEvidence.Observed, txid = txid.lowercase())
                retainedPositive[current.walletIndex] = observed
                persist(observed)
                retainedPositive.remove(current.walletIndex)
                observed
            }
        }

    suspend fun backupSnapshot(walletIndex: Int): OnchainSendAttempt? = withContext(ioDispatcher) {
        mutex.withLock { loadWithRetainedAccepted(walletIndex)?.takeIf { it.blocksNextSend } }
    }

    suspend fun restoreActive(attempt: OnchainSendAttempt) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val existing = loadWithRetainedAccepted(attempt.walletIndex)
            check(existing == null || !existing.blocksNextSend || existing == attempt) {
                "Cannot replace an existing active on-chain operation"
            }
            persist(attempt.copy(localFollowupComplete = false))
            retainedPositive.remove(attempt.walletIndex)
        }
    }

    private fun load(walletIndex: Int): OnchainSendAttempt? {
        val value = keychain.loadString(KEY, walletIndex) ?: return null
        return runCatching {
            Json.decodeFromString<OnchainSendAttempt>(value).also { attempt ->
                require(attempt.walletId.isNotBlank() && attempt.attemptId.isNotBlank() && attempt.walletIndex == walletIndex)
                require(attempt.evidence == OnchainSendEvidence.Pending ||
                    attempt.txid?.matches(Regex("[0-9a-fA-F]{64}")) == true)
                require(!attempt.localFollowupComplete || attempt.hasPositiveEvidence)
                require(attempt.candidateTxids.distinct().size == attempt.candidateTxids.size)
                require(attempt.candidateTxids.all { it.matches(Regex("[0-9a-f]{64}")) })
                if (attempt.originalInputs != null) {
                    require(attempt.originalInputs.isNotEmpty())
                    require(attempt.originalInputs.distinct().size == attempt.originalInputs.size)
                    require(attempt.originalInputs.all { it.txid.matches(Regex("[0-9a-fA-F]{64}")) })
                    require(attempt.candidateTxids.isNotEmpty())
                    require(attempt.txid?.lowercase() in attempt.candidateTxids)
                } else {
                    require(attempt.candidateTxids.isEmpty())
                }
            }
        }
            .getOrElse { throw OnchainSendAttemptUnreadableError(it) }
    }

    private fun loadWithRetainedAccepted(walletIndex: Int): OnchainSendAttempt? {
        val persisted = load(walletIndex)
        val retained = retainedPositive[walletIndex] ?: return persisted
        if (persisted?.attemptId != retained.attemptId || persisted.walletId != retained.walletId ||
            (
                persisted.txid != null && !persisted.txid.equals(retained.txid, ignoreCase = true) &&
                    retained.txid?.lowercase() !in persisted.candidateTxids
                )
        ) {
            retainedPositive.remove(walletIndex)
            return persisted
        }
        if (persisted.hasPositiveEvidence) {
            retainedPositive.remove(walletIndex)
            return persisted
        }
        return retained
    }

    private suspend fun persist(attempt: OnchainSendAttempt) {
        keychain.upsertString(KEY, Json.encodeToString(attempt), attempt.walletIndex)
        _backupStateVersion.update { it + 1 }
    }
}
