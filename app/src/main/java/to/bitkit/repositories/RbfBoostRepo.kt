package to.bitkit.repositories

import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import to.bitkit.async.appScope
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.WalletScope
import to.bitkit.utils.Logger
import to.bitkit.utils.RbfOriginalConfirmed
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
    private val changes = MutableStateFlow(0L)

    fun walletGeneration(): Long? = connectionRecovery.currentGeneration()

    fun isCurrentWallet(expected: Long?): Boolean = connectionRecovery.isCurrentGeneration(expected)

    fun find(activity: OnchainActivity, expectedGeneration: Long?): Attempt? = synchronized(lock) {
        if (!admit(activity, expectedGeneration)) return@synchronized null
        findAttempt(activity)
    }

    fun observePending(activity: OnchainActivity) = changes.map {
        val attempt = find(activity, walletGeneration())
        attempt != null && attempt.state.value != Status.Published &&
            (!activity.confirmed || activity.txId == attempt.replacementTxId)
    }

    fun confirm(activity: OnchainActivity, expectedGeneration: Long?, quote: Quote): Attempt? {
        val attempt = synchronized(lock) {
            if (!admit(activity, expectedGeneration)) return null
            val existing = findAttempt(activity)
            if (activity.confirmed && activity.txId != existing?.replacementTxId) return null
            if (existing != null && existing.state.value !is Status.Pending) return existing
            if (existing == null && !canStartBoost(activity, quote)) {
                return null
            }
            (existing ?: Attempt(activity, quote).also { attempts[key(activity)] = it }).also {
                it.update(if (it.replacementTxId == null) Status.Broadcasting else Status.Publishing())
                notifyChanged()
            }
        }
        scope.launch { runAttempt(key(attempt.original), attempt, checkNotNull(expectedGeneration)) }
        return attempt
    }

    private fun key(activity: OnchainActivity) = scopedActivityId(activity.walletId, activity.txId)

    private fun canStartBoost(activity: OnchainActivity, quote: Quote) =
        activity.doesExist && !activity.isBoosted && !activity.isTransfer && quote.feeRate > 0uL

    private fun findAttempt(activity: OnchainActivity): Attempt? = attempts[key(activity)]
        ?: attempts.values.firstOrNull {
            it.original.walletId == activity.walletId && it.replacementTxId == activity.txId &&
                it.state.value != Status.Published
        }

    private fun admit(original: OnchainActivity, expectedGeneration: Long?): Boolean {
        val current = walletGeneration()
        if (generation != current) {
            generation = current
            attempts.clear()
        }
        return current != null && current == expectedGeneration && original.walletId == WalletScope.default &&
            original.txType == PaymentType.SENT
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
                ).getOrThrow().also {
                    synchronized(lock) {
                        attempt.replacementTxId = it
                        notifyChanged()
                    }
                }
                requireCurrent(expectedGeneration)
                publish(attempt, replacement, expectedGeneration)
                session.commitCurrentState {
                    lightningRepo.syncAsync()
                    attempt.update(Status.Published)
                }
                synchronized(lock) { notifyChanged() }
            }
        } catch (error: CancellationException) {
            finishFailure(key, attempt, expectedGeneration, cancelled = true)
            throw error
        } catch (error: Exception) {
            Logger.warn("RBF attempt failed", error, context = TAG)
            val originalConfirmed = error is RbfOriginalConfirmed || error.cause is RbfOriginalConfirmed
            finishFailure(key, attempt, expectedGeneration, cancelled = false, originalConfirmed = originalConfirmed)
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
            activityRepo.recordRbfBoost(attempt.original, replacement, attempt.quote.feeRate).getOrThrow()
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

    private fun finishFailure(
        key: String,
        attempt: Attempt,
        expectedGeneration: Long,
        cancelled: Boolean,
        originalConfirmed: Boolean = false,
    ) {
        synchronized(lock) {
            if (attempts[key] !== attempt) return
            if (!isCurrentWallet(expectedGeneration) || attempt.replacementTxId == null || originalConfirmed) {
                attempts.remove(key)
                attempt.update(if (cancelled) Status.Cancelled else Status.Failed)
            } else {
                attempt.update(Status.Pending(cancelled))
            }
            notifyChanged()
        }
        if (!cancelled && !originalConfirmed && attempt.replacementTxId != null && isCurrentWallet(expectedGeneration)) {
            lightningRepo.syncAsync()
        }
    }

    // Call under lock so observers can see the matching attempt and replacement identity.
    private fun notifyChanged() {
        changes.value += 1
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
