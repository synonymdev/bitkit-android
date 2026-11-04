package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.divideRoundingUp
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import kotlin.time.Instant

/** Rust converts sat/vB to sat/kwu by multiplying by 250; validate before the unchecked FFI factory. */
object OnchainRecoveryFeeRate {
    // Match the normal custom-fee field and iOS ceiling.
    const val maximum: ULong = 999uL

    fun isValid(rate: ULong): Boolean = rate > 0uL && rate <= maximum

    fun parse(input: String): ULong? = input.toULongOrNull()?.takeIf(::isValid)
}

/**
 * The highest rate at which a transaction that pays [fee] at [feeRate] may pay no more than [targetFee]. Its fee is
 * rounded up, so it is larger than (fee - 1) / feeRate vB, and every higher rate pays more than [targetFee].
 */
internal fun highestFeeRateNotAbove(targetFee: ULong, fee: ULong, feeRate: ULong): ULong =
    (targetFee.safe() * feeRate.safe()) / maxOf(fee.safe() - 1uL.safe(), 1uL)

/** The prepared native handle refused this invocation before calling native broadcast. */
internal class PreparedOnchainSendRefused(cause: Throwable) : AppError(cause)

/** One native prepared transaction; its direct submission is cached even if it fails. */
class PreparedOnchainSend(
    val receipt: OnchainPreparedReceipt,
    private val submit: suspend (beforeDispatch: suspend () -> Unit) -> OnchainSendOutcome,
) {
    private val mutex = Mutex()
    private var result: Result<OnchainSendOutcome>? = null

    suspend fun broadcast(beforeDispatch: suspend () -> Unit = {}): OnchainSendOutcome = withContext(NonCancellable) {
        mutex.withLock {
            val cached = result ?: runSuspendCatching { submit(beforeDispatch) }.also { result = it }
            cached.getOrThrow()
        }
    }
}

/** Recovery always uses the original actual recipient amount and exact inputs, including an initial Max send. */
interface OnchainPreparedSender {
    suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend
    suspend fun prepareRecovery(
        attempt: OnchainSendAttempt,
        feeRateSatsPerVByte: ULong,
        paymentDeadlineAt: Instant? = null
    ): PreparedOnchainSend
}

/** Thin boundary for the additive native prepare API, implemented in LightningService once matching bindings exist. */
interface OnchainPreparationProtocol {
    suspend fun prepareFixed(
        address: String,
        amountSats: ULong,
        feeRateSatsPerVByte: ULong,
        inputs: List<OnchainSendInput>?,
        walletIndex: Int,
        paymentDeadlineAt: Instant? = null,
    ): PreparedOnchainSend

    suspend fun prepareMax(
        address: String,
        retainReserves: Boolean,
        feeRateSatsPerVByte: ULong,
        walletIndex: Int,
        paymentDeadlineAt: Instant? = null,
    ): PreparedOnchainSend
}

class OnchainPreparedSenderAdapter(
    private val native: OnchainPreparationProtocol,
    private val initialInputs: List<OnchainSendInput>? = null,
) : OnchainPreparedSender {
    override suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend {
        val prepared = if (attempt.isMaxAmount) {
            native.prepareMax(attempt.address, true, attempt.feeRateSatsPerVByte, attempt.walletIndex)
        } else {
            native.prepareFixed(
                attempt.address,
                attempt.amountSats,
                attempt.feeRateSatsPerVByte,
                initialInputs,
                attempt.walletIndex,
            )
        }
        if (!attempt.isMaxAmount && initialInputs != null) {
            require(initialInputs.isNotEmpty() && initialInputs.distinct().size == initialInputs.size)
            require(prepared.receipt.inputs.toSet() == initialInputs.toSet())
        }
        return prepared
    }

    override suspend fun prepareRecovery(
        attempt: OnchainSendAttempt,
        feeRateSatsPerVByte: ULong,
        paymentDeadlineAt: Instant?,
    ): PreparedOnchainSend {
        val inputs = requireNotNull(attempt.originalInputs)
        require(inputs.isNotEmpty() && inputs.distinct().size == inputs.size)
        return native.prepareFixed(
            attempt.address,
            attempt.amountSats,
            feeRateSatsPerVByte,
            inputs,
            attempt.walletIndex,
            paymentDeadlineAt,
        )
    }
}

class OnchainSendCoordinator(
    private val store: OnchainSendAttemptStore,
    private val sender: OnchainPreparedSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun sendInitial(attempt: OnchainSendAttempt): Result<OnchainSendOutcome> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val original = store.current()
                if (original?.attemptId != attempt.attemptId || original.walletId != attempt.walletId) {
                    throw OnchainSendBlockedError(original)
                }
                if (original.hasPositiveEvidence || original.candidateTxids.isNotEmpty()) {
                    throw OnchainSendBlockedError(original)
                }
                val prepared = sender.prepareInitial(original)
                store.retainPreparedReceipt(
                    original.attemptId,
                    original.walletIndex,
                    prepared.receipt.copy(feeRateSatsPerVByte = original.feeRateSatsPerVByte),
                    isRecovery = false,
                )
                submit(original, prepared)
            }
        }
    }

    suspend fun retryOriginal(
        attemptId: String,
        walletId: String,
        feeRateSatsPerVByte: ULong,
        paymentDeadlineAt: Instant? = null,
        approvePrepared: suspend (OnchainPreparedReceipt) -> Unit = {},
        authorizeOriginal: suspend (OnchainSendAttempt) -> Unit,
    ): Result<OnchainSendOutcome> = withContext(ioDispatcher) {
        val result = runSuspendCatching {
            mutex.withLock {
                val attempt = store.current()
                if (attempt?.attemptId != attemptId || attempt.walletId != walletId) {
                    throw OnchainSendBlockedError(attempt)
                }
                if (attempt.hasPositiveEvidence) {
                    return@withLock OnchainSendOutcome.Accepted(requireNotNull(attempt.txid))
                }
                // A release or Cancel payment that may be in a mempool can still confirm, so no payment goes beside it
                if (!attempt.isUnresolved || attempt.originalInputs.isNullOrEmpty() ||
                    attempt.candidateTxids.isEmpty() || attempt.hasSentCancellation
                ) {
                    throw OnchainSendBlockedError(attempt)
                }
                require(OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte))
                val prepared = sender.prepareRecovery(attempt, feeRateSatsPerVByte, paymentDeadlineAt)
                approvePrepared(prepared.receipt.copy(feeRateSatsPerVByte = feeRateSatsPerVByte))
                val retained = store.retainPreparedReceipt(
                    attempt.attemptId,
                    attempt.walletIndex,
                    prepared.receipt.copy(feeRateSatsPerVByte = feeRateSatsPerVByte),
                    isRecovery = true,
                )
                try {
                    authorizeOriginal(retained)
                } catch (error: Throwable) {
                    store.discardUnsubmittedRetryCandidate(attempt, prepared.receipt.txid)
                    throw error
                }
                submit(retained, prepared, recoveryOriginal = attempt)
            }
        }
        if (result.isFailure) {
            val winner = runSuspendCatching {
                if (result.exceptionOrNull() is OnchainSendPendingError) {
                    store.currentDurable()
                } else {
                    store.current()
                }
            }.getOrNull()
            if (winner?.attemptId == attemptId && winner.walletId == walletId && winner.hasPositiveEvidence) {
                return@withContext Result.success(OnchainSendOutcome.Accepted(requireNotNull(winner.txid)))
            }
        }
        result
    }

    /**
     * Spend the exact original inputs back to this wallet, conflicting with every candidate without paying anyone.
     * The guard stays until that spend is final, through [OnchainSendAttemptStore.releaseCancelled]. A candidate,
     * release or earlier Cancel payment that may be in a mempool is replaced only by one paying a higher fee rate and
     * more in total, by at least its own size at the 1 sat/vB incremental relay fee (BIP125 rules 3, 4 and 6), so a
     * smaller one fails before approval with [OnchainSendCancelUnderbidsError].
     */
    suspend fun cancelOriginal(
        attemptId: String,
        walletId: String,
        feeRateSatsPerVByte: ULong,
        prepareCancellation: suspend (OnchainSendAttempt, ULong) -> PreparedOnchainSend,
        approvePrepared: suspend (OnchainPreparedReceipt) -> Unit = {},
    ): Result<OnchainSendOutcome> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val attempt = store.current()
                if (attempt?.attemptId != attemptId || attempt.walletId != walletId || !attempt.isUnresolved) {
                    throw OnchainSendBlockedError(attempt)
                }
                val inputs = attempt.originalInputs
                if (inputs.isNullOrEmpty() || attempt.candidateTxids.isEmpty()) throw OnchainSendBlockedError(attempt)
                require(OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte))
                val rateFloor = attempt.highestSpendFeeRate
                if (feeRateSatsPerVByte <= rateFloor) {
                    throw OnchainSendCancelUnderbidsError(rateFloor, attempt.highestSpendFee)
                }
                val prepared = prepareCancellation(attempt, feeRateSatsPerVByte)
                val receipt = prepared.receipt.copy(feeRateSatsPerVByte = feeRateSatsPerVByte)
                require(receipt.inputs.size == inputs.size && receipt.inputs.toSet() == inputs.toSet())
                require(!receipt.address.equals(attempt.address, ignoreCase = true))
                require(attempt.candidateTxids.none { it.equals(receipt.txid, ignoreCase = true) })
                attempt.highestSpendFee.takeIf { it > 0uL }?.let { replacedFee ->
                    val fee = requireNotNull(receipt.miningFeeSats)
                    // The replaced fee plus its own size at 1 sat/vB. Its fee is rounded up, so the fee over the
                    // rate, rounded up, is at least its size.
                    val floor = replacedFee.safe() + divideRoundingUp(fee, feeRateSatsPerVByte).safe()
                    if (fee < floor) {
                        val payingRate = highestFeeRateNotAbove(floor.safe() - 1uL.safe(), fee, feeRateSatsPerVByte)
                        throw OnchainSendCancelUnderbidsError(maxOf(payingRate, rateFloor), floor.safe() - 1uL.safe())
                    }
                }
                approvePrepared(receipt)
                store.broadcastCancellation(attempt.attemptId, attempt.walletIndex, receipt) { prepared.broadcast(it) }
            }
        }
    }

    /**
     * Replace the pending Cancel payment or release [findPending] reports with one paying [feeRateSatsPerVByte],
     * through [bump], which keeps every output in this wallet, so the replacement still cancels every candidate.
     */
    suspend fun speedUpCancellation(
        attemptId: String,
        walletId: String,
        feeRateSatsPerVByte: ULong,
        findPending: suspend (OnchainSendAttempt) -> OnchainInputSpend?,
        approve: suspend () -> Unit,
        bump: suspend (txid: String, feeRate: ULong) -> String,
    ): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val attempt = store.current()
                if (attempt?.attemptId != attemptId || attempt.walletId != walletId || !attempt.isUnresolved) {
                    throw OnchainSendBlockedError(attempt)
                }
                val pending = findPending(attempt)?.takeIf { !it.isFinal && attempt.isCancelledBy(it) }
                    ?: throw OnchainSendBlockedError(attempt)
                require(OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte))
                val rateFloor = attempt.highestSpendFeeRate
                if (feeRateSatsPerVByte <= rateFloor) {
                    throw OnchainSendCancelUnderbidsError(rateFloor, attempt.highestSpendFee)
                }
                approve()
                store.bumpCancellation(attempt.attemptId, attempt.walletIndex, feeRateSatsPerVByte) {
                    bump(pending.txid, feeRateSatsPerVByte)
                }
            }
        }
    }

    private suspend fun submit(
        attempt: OnchainSendAttempt,
        prepared: PreparedOnchainSend,
        recoveryOriginal: OnchainSendAttempt? = null,
    ): OnchainSendOutcome {
        var nativeDispatchEntered = false
        val outcome = runSuspendCatching {
            store.broadcastPreparedCandidate(
                attempt.attemptId,
                attempt.walletIndex,
                prepared.receipt.txid,
            ) { beforeDispatch ->
                prepared.broadcast {
                    beforeDispatch()
                    nativeDispatchEntered = true
                }
            }
        }.getOrElse { error ->
            if (!nativeDispatchEntered && recoveryOriginal != null) {
                store.discardUnsubmittedRetryCandidate(recoveryOriginal, prepared.receipt.txid)
            }
            val winner = runSuspendCatching { store.current() }.getOrNull()
            if (winner?.attemptId == attempt.attemptId && winner.walletId == attempt.walletId &&
                winner.hasPositiveEvidence
            ) {
                return OnchainSendOutcome.Accepted(requireNotNull(winner.txid))
            }
            throw OnchainSendPendingError(error, prepared.receipt.txid)
        }
        val recorded = runSuspendCatching {
            store.recordOutcome(attempt.attemptId, outcome, attempt.walletIndex)
        }.getOrElse { error ->
            val winner = runSuspendCatching { store.currentDurable() }.getOrNull()
            if (winner?.attemptId == attempt.attemptId && winner.walletId == attempt.walletId &&
                winner.hasPositiveEvidence
            ) {
                return OnchainSendOutcome.Accepted(requireNotNull(winner.txid))
            }
            throw OnchainSendPendingError(error, outcome.txid)
        }
        return if (recorded.hasPositiveEvidence) {
            OnchainSendOutcome.Accepted(requireNotNull(recorded.txid))
        } else {
            outcome
        }
    }
}
