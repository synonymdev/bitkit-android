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
import kotlinx.serialization.json.JsonPrimitive
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.ActiveOnchainAttemptBackup
import to.bitkit.models.WalletScope
import to.bitkit.services.LightningService
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    val feeRateSatsPerVByte: ULong? = null,
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
    val candidateFeeRates: Map<String, ULong> = emptyMap(),
    val backupFollowup: ActiveOnchainAttemptBackup.Followup? = null,
    val restoredFromBackup: Boolean = false,
    val preparationPending: Boolean = false,
) {
    val winningFeeRateSatsPerVByte: ULong
        get() = candidateFeeRates[txid?.lowercase()] ?: feeRateSatsPerVByte.takeIf {
            candidateTxids.isEmpty() || txid.equals(candidateTxids.first(), ignoreCase = true)
        } ?: error("Winning candidate fee rate is unavailable")

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

@OptIn(ExperimentalTime::class)
@Singleton
class OnchainSendAttemptStore @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningService: LightningService,
    private val clock: Clock,
) {
    companion object {
        private val KEY = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name
    }

    private val mutex = Mutex()
    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion = _backupStateVersion.asStateFlow()

    // One known positive result per existing wallet guard; entries disappear after a successful durable write.
    private val retainedPositive = mutableMapOf<Int, OnchainSendAttempt>()
    private val inFlightPreparations = mutableSetOf<String>()
    // Process-local proof that this initial receipt has never entered the native submission boundary.
    private val firstSubmissions = mutableSetOf<String>()

    suspend fun current(): OnchainSendAttempt? = withContext(ioDispatcher) {
        mutex.withLock { loadWithRetainedAccepted(lightningService.currentWalletIndex) }
    }

    /** Read only restart-safe evidence, excluding a positive result retained after a failed write. */
    suspend fun currentDurable(): OnchainSendAttempt? = withContext(ioDispatcher) {
        mutex.withLock { load(lightningService.currentWalletIndex) }
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
        contactPublicKey: String? = null,
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
                preparationPending = requestId != null,
                backupFollowup = ActiveOnchainAttemptBackup.Followup(
                    feeSats = "0",
                    tags = tags,
                    createdAtMillis = nowMillis(clock).toString(),
                    channelId = channelId,
                    contact = contactPublicKey?.let(::JsonPrimitive),
                ),
            )
            persist(attempt)
            inFlightPreparations += attempt.attemptId
            var callbackCompleted = false
            try {
                beforeSendAttempt()
                callbackCompleted = true
            } finally {
                if (!callbackCompleted) inFlightPreparations -= attempt.attemptId
            }
            attempt
        }
    }

    suspend fun releaseInterruptedShopPreparation(
        removeOriginalProof: suspend (OnchainSendAttempt) -> Boolean,
    ): Boolean = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(lightningService.currentWalletIndex) ?: return@withLock false
            if (current.attemptId in inFlightPreparations) return@withLock false
            if (!current.preparationPending || current.restoredFromBackup || current.requestId == null) {
                return@withLock false
            }
            if (current.walletId != WalletScope.default || current.payerIdentity.isNullOrBlank()) return@withLock false
            if (current.evidence != OnchainSendEvidence.Pending || current.txid != null) return@withLock false
            if (current.candidateTxids.isNotEmpty() || current.originalInputs != null) return@withLock false
            if (!removeOriginalProof(current)) return@withLock false
            keychain.delete(KEY, current.walletIndex)
            _backupStateVersion.update { it + 1 }
            true
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
            val feeRate = receipt.feeRateSatsPerVByte ?: current.feeRateSatsPerVByte
            require(OnchainRecoveryFeeRate.isValid(feeRate))
            val existingFeeRate = current.candidateFeeRates[receipt.txid.lowercase()]
            require(existingFeeRate == null || existingFeeRate == feeRate)
            current.copy(
                amountSats = receipt.amountSats,
                originalInputs = current.originalInputs ?: receipt.inputs,
                candidateTxids = (current.candidateTxids + receipt.txid.lowercase()).distinct(),
                candidateFeeRates = current.candidateFeeRates + (receipt.txid.lowercase() to feeRate),
                txid = receipt.txid.lowercase(),
                evidence = OnchainSendEvidence.Pending,
                refusalReason = null,
                preparationPending = false,
            ).also {
                persist(it)
                inFlightPreparations -= attemptId
                if (!isRecovery) firstSubmissions += attemptId
            }
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
            val firstSubmission = firstSubmissions.remove(attemptId)
            try {
                broadcast()
            } catch (error: ServiceError.PaymentDeadlineExpired) {
                // Only the queued deadline check raises this before prepared.broadcast(). A retry,
                // reopened guard or any earlier dispatch has no process-local first-submission proof.
                if (!firstSubmission || current.restoredFromBackup ||
                    current.evidence != OnchainSendEvidence.Pending || current.candidateTxids.size != 1
                ) throw error
                persist(current.copy(
                    txid = null,
                    originalInputs = null,
                    candidateTxids = emptyList(),
                    candidateFeeRates = emptyMap(),
                    preparationPending = current.requestId != null,
                ))
                // Shop proof cleanup still precedes guard deletion through the existing preparation path.
                throw OnchainSendNotDispatchedError(error)
            }
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
            inFlightPreparations -= attemptId
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId == attemptId && current.evidence == OnchainSendEvidence.Pending &&
                current.candidateTxids.isEmpty() && !current.preparationPending
            ) {
                keychain.delete(KEY, walletIndex)
                _backupStateVersion.update { it + 1 }
            }
        }
    }

    suspend fun retainWinningFee(attemptId: String, walletIndex: Int, txid: String, feeSats: ULong) =
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val current = loadWithRetainedAccepted(walletIndex)
                check(current?.attemptId == attemptId && walletIndex == lightningService.currentWalletIndex &&
                    current.hasPositiveEvidence && current.txid.equals(txid, true))
                val followup = requireNotNull(current.backupFollowup)
                persist(current.copy(backupFollowup = followup.copy(feeSats = feeSats.toString())))
            }
        }

    suspend fun markLocalFollowupComplete(attemptId: String, walletIndex: Int) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = loadWithRetainedAccepted(walletIndex)
            if (current?.attemptId == attemptId && current.hasPositiveEvidence) {
                check(
                    !current.restoredFromBackup || current.backupFollowup != null
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

    // Admission and receipt retention take this mutex before the proof mutex. Capture both
    // under the same ordering so a pre-dispatch backup cannot mix an empty guard with a sent proof.
    suspend fun backupSnapshot(
        walletIndex: Int,
        captureProofs: suspend () -> List<PaykitPaymentStateBackup.Proof>,
    ): Pair<OnchainSendAttempt?, List<PaykitPaymentStateBackup.Proof>> = withContext(ioDispatcher) {
        mutex.withLock {
            loadWithRetainedAccepted(walletIndex)?.takeIf { it.blocksNextSend } to captureProofs()
        }
    }

    suspend fun restoreActive(attempt: OnchainSendAttempt) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val existing = loadWithRetainedAccepted(attempt.walletIndex)
            if (existing?.attemptId == attempt.attemptId) {
                // A restore retry may replay an older receipt after fee/activity persistence progressed.
                // Normalize only progress fields; recipient, payer, inputs and original context must match.
                val sameOperation = existing.copy(
                    evidence = attempt.evidence,
                    txid = attempt.txid,
                    refusalReason = attempt.refusalReason,
                    localFollowupComplete = attempt.localFollowupComplete,
                    candidateTxids = attempt.candidateTxids,
                    candidateFeeRates = attempt.candidateFeeRates,
                    backupFollowup = existing.backupFollowup?.copy(feeSats = attempt.backupFollowup?.feeSats.orEmpty()),
                    restoredFromBackup = attempt.restoredFromBackup,
                    preparationPending = attempt.preparationPending,
                ) == attempt
                check(sameOperation && existing.candidateTxids.containsAll(attempt.candidateTxids)) {
                    "Cannot change a restored on-chain operation's original context"
                }
                persist(
                    if (existing.restoredFromBackup) {
                        existing
                    } else {
                        existing.copy(
                            restoredFromBackup = attempt.restoredFromBackup,
                            localFollowupComplete = false,
                            preparationPending = false,
                        )
                    }
                )
                return@withLock
            }
            check(existing == null || !existing.blocksNextSend) {
                "Cannot replace an existing active on-chain operation"
            }
            persist(attempt.copy(localFollowupComplete = false, preparationPending = false))
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
