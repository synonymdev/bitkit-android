package to.bitkit.repositories

import com.synonym.bitkitcore.OnchainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.bitkit.async.appScope
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.WalletScope
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Singleton
class RbfBoostRepo @Inject constructor(
    @BgDispatcher dispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val activityRepo: ActivityRepo,
    private val connectionRecovery: WalletConnectionRecovery,
    private val timeSource: TimeSource,
) {
    private val scope = appScope(dispatcher, TAG)
    private val lock = Any()
    private var generation: Long? = null
    private val attempts = mutableMapOf<String, Attempt>()

    fun walletGeneration(): Long? = connectionRecovery.currentGeneration()

    fun isCurrentWallet(expected: Long?): Boolean = connectionRecovery.isCurrentGeneration(expected)

    fun find(original: OnchainActivity, expectedGeneration: Long?): Attempt? = synchronized(lock) {
        if (!admit(original, expectedGeneration)) return@synchronized null
        attempts[scopedActivityId(original.walletId, original.txId)]
    }

    fun confirm(original: OnchainActivity, expectedGeneration: Long?, quote: Quote): Attempt? {
        val key = scopedActivityId(original.walletId, original.txId)
        val attempt = synchronized(lock) {
            if (!admit(original, expectedGeneration)) return null
            val existing = attempts[key]
            if (existing != null && existing.state.value !is Status.Pending) return existing
            (existing ?: Attempt(original, quote).also { attempts[key] = it }).also {
                it.update(if (it.replacementTxId == null) Status.Broadcasting else Status.Publishing())
            }
        }
        scope.launch { runAttempt(key, attempt, checkNotNull(expectedGeneration)) }
        return attempt
    }

    private fun admit(original: OnchainActivity, expectedGeneration: Long?): Boolean {
        val current = walletGeneration()
        if (generation != current) {
            generation = current
            attempts.clear()
        }
        return current != null && current == expectedGeneration && original.walletId == WalletScope.default
    }

    private suspend fun runAttempt(key: String, attempt: Attempt, expectedGeneration: Long) {
        try {
            requireCurrent(expectedGeneration)
            val owner = connectionRecovery.captureRuntimeOwner()
            requireCurrent(expectedGeneration)
            connectionRecovery.withOperation(owner) { session ->
                requireCurrent(expectedGeneration)
                val original = attempt.original
                val replacement = attempt.replacementTxId ?: lightningRepo.bumpFeeByRbf(
                    originalTxId = original.txId,
                    satsPerVByte = attempt.quote.feeRate,
                ).getOrThrow().also { attempt.replacementTxId = it }
                requireCurrent(expectedGeneration)
                publish(attempt, replacement, expectedGeneration)
                session.commitCurrentState {
                    lightningRepo.syncAsync()
                    attempt.update(Status.Published)
                }
            }
        } catch (error: CancellationException) {
            finishFailure(key, attempt, expectedGeneration, cancelled = true)
            throw error
        } catch (error: Exception) {
            Logger.warn("RBF attempt failed", error, context = TAG)
            finishFailure(key, attempt, expectedGeneration, cancelled = false)
        }
    }

    private suspend fun publish(attempt: Attempt, replacement: String, expectedGeneration: Long) = coroutineScope {
        val started = timeSource.markNow()
        attempt.update(Status.Publishing())
        val pendingFeedback = launch {
            delay(PENDING_FEEDBACK_DELAY)
            requireCurrent(expectedGeneration)
            attempt.update(Status.Publishing(slow = true))
            Logger.debug("RBF replacement publication still pending after ${started.elapsedNow()}", context = TAG)
        }
        try {
            activityRepo.recordRbfBoost(attempt.original, replacement, attempt.quote.feeRate).onFailure {
                Logger.warn("Failed to record RBF fee rate for '$replacement'", it, context = TAG)
            }
            requireCurrent(expectedGeneration)
            activityRepo.publishRbfBoost(attempt.original.txId, replacement).getOrThrow()
            requireCurrent(expectedGeneration)
            Logger.debug("RBF replacement published in ${started.elapsedNow()}", context = TAG)
        } finally {
            pendingFeedback.cancel()
        }
    }

    private suspend fun requireCurrent(expectedGeneration: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentWallet(expectedGeneration)) throw CancellationException("RBF wallet changed")
    }

    private fun finishFailure(key: String, attempt: Attempt, expectedGeneration: Long, cancelled: Boolean) {
        synchronized(lock) {
            if (attempts[key] !== attempt) return
            if (!isCurrentWallet(expectedGeneration) || attempt.replacementTxId == null) {
                attempts.remove(key)
                attempt.update(if (cancelled) Status.Cancelled else Status.Failed)
            } else {
                attempt.update(Status.Pending(cancelled))
            }
        }
        if (!cancelled && attempt.replacementTxId != null && isCurrentWallet(expectedGeneration)) {
            lightningRepo.syncAsync()
        }
    }

    data class Quote(val feeRate: ULong, val totalFeeSats: ULong)

    class Attempt internal constructor(internal val original: OnchainActivity, val quote: Quote) {
        private val _state = MutableStateFlow<Status>(Status.Broadcasting)
        val state = _state.asStateFlow()
        internal var replacementTxId: String? = null

        internal fun update(status: Status) {
            _state.value = status
        }
    }

    sealed interface Status {
        data object Broadcasting : Status
        data class Publishing(val slow: Boolean = false) : Status
        data class Pending(val cancelled: Boolean) : Status
        data object Published : Status
        data object Failed : Status
        data object Cancelled : Status
    }

    companion object {
        private const val TAG = "RbfBoostRepo"
        internal val PENDING_FEEDBACK_DELAY = 2.seconds
    }
}
