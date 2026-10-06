package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching

/** Rust converts sat/vB to sat/kwu by multiplying by 250; validate before the unchecked FFI factory. */
object OnchainRecoveryFeeRate {
    // Native sat/vB conversion multiplies by 250; shared backup/iOS fees also require UInt32.
    val maximum: ULong = minOf(ULong.MAX_VALUE / 250uL, UInt.MAX_VALUE.toULong())

    fun isValid(rate: ULong): Boolean = rate > 0uL && rate <= maximum

    fun parse(input: String): ULong? = input.toULongOrNull()?.takeIf(::isValid)
}

/** One native prepared transaction; its direct submission is cached even if it fails. */
class PreparedOnchainSend(
    val receipt: OnchainPreparedReceipt,
    private val submit: suspend () -> OnchainSendOutcome,
) {
    private val mutex = Mutex()
    private var result: Result<OnchainSendOutcome>? = null

    suspend fun broadcast(): OnchainSendOutcome = withContext(NonCancellable) {
        mutex.withLock {
            val cached = result ?: runSuspendCatching { submit() }.also { result = it }
            cached.getOrThrow()
        }
    }
}

/** Recovery always uses the original actual recipient amount and exact inputs, including an initial Max send. */
interface OnchainPreparedSender {
    suspend fun prepareInitial(attempt: OnchainSendAttempt): PreparedOnchainSend
    suspend fun prepareRecovery(attempt: OnchainSendAttempt, feeRateSatsPerVByte: ULong): PreparedOnchainSend
}

/** Thin boundary for the additive native prepare API, implemented in LightningService once matching bindings exist. */
interface OnchainPreparationProtocol {
    suspend fun prepareFixed(
        address: String,
        amountSats: ULong,
        feeRateSatsPerVByte: ULong,
        inputs: List<OnchainSendInput>?,
        walletIndex: Int,
    ): PreparedOnchainSend

    suspend fun prepareMax(
        address: String,
        retainReserves: Boolean,
        feeRateSatsPerVByte: ULong,
        walletIndex: Int,
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

    override suspend fun prepareRecovery(attempt: OnchainSendAttempt, feeRateSatsPerVByte: ULong): PreparedOnchainSend {
        val inputs = requireNotNull(attempt.originalInputs)
        require(inputs.isNotEmpty() && inputs.distinct().size == inputs.size)
        return native.prepareFixed(
            attempt.address,
            attempt.amountSats,
            feeRateSatsPerVByte,
            inputs,
            attempt.walletIndex,
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
                if (!attempt.isUnresolved || attempt.originalInputs.isNullOrEmpty() ||
                    attempt.candidateTxids.isEmpty()
                ) {
                    throw OnchainSendBlockedError(attempt)
                }
                require(OnchainRecoveryFeeRate.isValid(feeRateSatsPerVByte))
                val prepared = sender.prepareRecovery(attempt, feeRateSatsPerVByte)
                val retained = store.retainPreparedReceipt(
                    attempt.attemptId,
                    attempt.walletIndex,
                    prepared.receipt.copy(feeRateSatsPerVByte = feeRateSatsPerVByte),
                    isRecovery = true,
                )
                authorizeOriginal(retained)
                submit(retained, prepared)
            }
        }
        if (result.isFailure) {
            val winner = runSuspendCatching { store.current() }.getOrNull()
            if (winner?.attemptId == attemptId && winner.walletId == walletId && winner.hasPositiveEvidence) {
                return@withContext Result.success(OnchainSendOutcome.Accepted(requireNotNull(winner.txid)))
            }
        }
        result
    }

    private suspend fun submit(attempt: OnchainSendAttempt, prepared: PreparedOnchainSend): OnchainSendOutcome {
        val outcome = runSuspendCatching {
            store.broadcastPreparedCandidate(
                attempt.attemptId,
                attempt.walletIndex,
                prepared.receipt.txid,
                prepared::broadcast,
            )
        }.getOrElse { error ->
            val winner = runSuspendCatching { store.current() }.getOrNull()
            if (winner?.hasPositiveEvidence == true) return OnchainSendOutcome.Accepted(requireNotNull(winner.txid))
            throw OnchainSendPendingError(error, prepared.receipt.txid)
        }
        val recorded = runSuspendCatching {
            store.recordOutcome(attempt.attemptId, outcome, attempt.walletIndex)
        }.getOrElse { error ->
            val winner = runSuspendCatching { store.current() }.getOrNull()
            if (winner?.hasPositiveEvidence == true) return OnchainSendOutcome.Accepted(requireNotNull(winner.txid))
            if (outcome is OnchainSendOutcome.Accepted && outcome.txid.equals(prepared.receipt.txid, true)) {
                return outcome
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
