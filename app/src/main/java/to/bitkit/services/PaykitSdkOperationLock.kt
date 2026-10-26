package to.bitkit.services

import com.synonym.paykit.PaykitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

internal class PaykitSdkOperationLock(
    private val checkRecoveryAccess: suspend () -> Unit = {},
) {
    companion object {
        /** Maximum interactive overtakes before the oldest background operation gets a turn. */
        private const val MAX_INTERACTIVE_BYPASSES = 3
    }

    enum class Priority {
        /** FIFO barrier for identity changes, cleanup, and operations not classified for reordering. */
        Ordered,
        Interactive,
        Background,
    }

    private val stateLock = Any()
    private var isLocked = false
    private val waiters = ArrayDeque<Waiter>()
    private var interactiveBypasses = 0
    private var generation = 0L
    private var isWiping = false
    private var profileRemoval: ProfileRemoval? = null
    private var profileRemovalGeneration = 0L

    suspend fun <T> withLock(priority: Priority = Priority.Ordered, operation: suspend () -> T): T {
        return withLock(priority, allowPublicReadInitialization = false, operation)
    }

    /** Only for constructing the unauthenticated handle used by publicRead. */
    suspend fun <T> withPublicReadInitialization(operation: suspend () -> T): T =
        withLock(Priority.Ordered, allowPublicReadInitialization = true, operation)

    private suspend fun <T> withLock(
        priority: Priority,
        allowPublicReadInitialization: Boolean,
        operation: suspend () -> T,
    ): T {
        if (ownsWipe()) return operation()
        val removal = currentCoroutineContext()[ProfileRemoval]
        val removalGeneration = synchronized(stateLock) { profileRemovalGeneration }
        val admittedGeneration = admit()
        if (!allowPublicReadInitialization) {
            checkRecoveryAccess()
            checkProfileRemoval(removal)
        }
        acquire(priority, admittedGeneration)
        return try {
            currentCoroutineContext().ensureActive()
            checkAdmitted(admittedGeneration)
            if (!allowPublicReadInitialization) {
                checkRecoveryAccess()
                checkProfileRemoval(removal)
                if (synchronized(stateLock) { removalGeneration != profileRemovalGeneration }) {
                    throw removalError()
                }
            }
            operation()
        } finally {
            release()
        }
    }

    /** Reserves admission, without holding the SDK lock across caller-owned cleanup. */
    suspend fun <T> withProfileRemoval(
        isOwnerCurrent: () -> Boolean,
        operation: suspend (ProfileRemoval) -> T,
    ): T {
        val removal = ProfileRemoval(isOwnerCurrent)
        withLock {
            removal.requireCurrent()
            synchronized(stateLock) {
                checkAvailable()
                check(profileRemoval == null) { "Profile removal is already in progress" }
                profileRemoval = removal
                profileRemovalGeneration++
            }
        }
        try {
            return withContext(removal) {
                removal.bind(requireNotNull(currentCoroutineContext()[Job]))
                removal.requireCurrent()
                operation(removal)
            }
        } finally {
            synchronized(stateLock) {
                if (profileRemoval === removal) {
                    profileRemoval = null
                    profileRemovalGeneration++
                }
            }
            removal.drained.complete(Unit)
        }
    }

    /** Independent publication must never run inside teardown, including its owning context. */
    fun requireNoProfileRemoval() = synchronized(stateLock) {
        checkAvailable()
        if (profileRemoval != null) throw removalError()
    }

    suspend fun requireWalletWipe() {
        check(ownsWipe()) { "Wallet wipe admission is required" }
    }

    suspend fun requireProfileRemoval() {
        val removal = currentCoroutineContext()[ProfileRemoval]
        check(removal != null) { "Profile removal admission is required" }
        checkRecoveryAccess()
        checkProfileRemoval(removal)
    }

    suspend fun checkProfileRemoval() {
        if (!ownsWipe()) {
            checkRecoveryAccess()
            checkProfileRemoval(currentCoroutineContext()[ProfileRemoval])
        }
    }

    private fun checkProfileRemoval(removal: ProfileRemoval?) {
        if (synchronized(stateLock) { profileRemoval !== removal }) {
            throw removalError()
        }
        removal?.requireCurrent()
    }

    /**
     * Runs [operation] without the lock but under the wallet wipe admission of [withLock]: it is rejected while a wipe
     * is in progress. A wipe cannot drain work that does not hold the lock, so a result that a wipe overtakes is
     * discarded and the caller gets the wipe error instead.
     */
    suspend fun <T> withoutLock(operation: suspend () -> T): T {
        if (ownsWipe()) return operation()
        val admittedGeneration = admit()
        currentCoroutineContext().ensureActive()
        return operation().also {
            currentCoroutineContext().ensureActive()
            checkAdmitted(admittedGeneration)
        }
    }

    suspend fun <T> withWalletWipe(operation: suspend () -> T): T {
        check(currentCoroutineContext()[ProfileRemoval] == null) { "Profile removal cannot start a wallet wipe" }
        val removal = synchronized(stateLock) {
            checkAvailable()
            isWiping = true
            generation++
            profileRemoval
        }
        removal?.revoke()
        return try {
            // No SDK lock is held while draining the caller's private/local cleanup.
            removal?.drained?.await()
            acquire(Priority.Ordered)
            try {
                withContext(WipeContext(this, generation)) { operation() }
            } finally {
                release()
            }
        } finally {
            synchronized(stateLock) { isWiping = false }
        }
    }

    private suspend fun acquire(priority: Priority, admittedGeneration: Long? = null) {
        val waiter = synchronized(stateLock) {
            // An ordinary caller may have suspended after admission while holding an outer repository mutex.
            // Never enqueue it behind the wipe that could now be draining that mutex.
            admittedGeneration?.let {
                checkAvailable()
                if (it != generation) throw wipeError()
            }
            if (!isLocked) {
                isLocked = true
                return
            }
            Waiter(priority, CompletableDeferred()).also(waiters::addLast)
        }
        try {
            waiter.ready.await()
        } catch (error: CancellationException) {
            if (synchronized(stateLock) { !waiters.remove(waiter) }) release()
            throw error
        }
    }

    private fun release() {
        val next = synchronized(stateLock) {
            if (waiters.isEmpty()) {
                isLocked = false
                interactiveBypasses = 0
                return
            }
            val interactiveIndex = waiters.takeWhile { it.priority != Priority.Ordered }
                .indexOfFirst { it.priority == Priority.Interactive }
            val index = if (interactiveBypasses < MAX_INTERACTIVE_BYPASSES && interactiveIndex > 0) {
                interactiveIndex
            } else {
                0
            }
            interactiveBypasses = if (index == 0) 0 else interactiveBypasses + 1
            waiters.removeAt(index)
        }
        next.ready.complete(Unit)
    }

    private suspend fun ownsWipe(): Boolean {
        val wipeContext = currentCoroutineContext()[WipeContext]
        return synchronized(stateLock) {
            isWiping && wipeContext?.owner === this && wipeContext.generation == generation
        }
    }

    private fun admit(): Long = synchronized(stateLock) {
        checkAvailable()
        generation
    }

    private fun checkAdmitted(admittedGeneration: Long) = synchronized(stateLock) {
        checkAvailable()
        if (admittedGeneration != generation) throw wipeError()
    }

    private fun checkAvailable() {
        if (isWiping) throw wipeError()
    }

    private fun wipeError() = PaykitException.Storage(
        code = "wallet_wipe_in_progress",
        context = "Paykit operation interrupted by wallet wipe",
    )

    private fun removalError() = PaykitException.Storage(
        code = "profile_removal_in_progress",
        context = "Paykit operation was interrupted by profile removal",
    )

    private class Waiter(val priority: Priority, val ready: CompletableDeferred<Unit>)

    class ProfileRemoval internal constructor(
        private val isOwnerCurrent: () -> Boolean,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ProfileRemoval>

        private val lock = Any()
        private var revoked = false
        private var job: Job? = null
        internal val drained = CompletableDeferred<Unit>()

        internal fun bind(job: Job) {
            val shouldCancel = synchronized(lock) {
                check(this.job == null)
                this.job = job
                revoked
            }
            if (shouldCancel) job.cancel()
        }

        internal fun revoke() {
            val activeJob = synchronized(lock) {
                revoked = true
                job
            }
            activeJob?.cancel()
        }

        fun requireCurrent() {
            val (activeJob, isRevoked) = synchronized(lock) { job to revoked }
            activeJob?.ensureActive()
            if (isRevoked || !isOwnerCurrent()) {
                throw PaykitException.Storage(
                    code = "profile_removal_obsolete",
                    context = "The profile removal no longer owns this session",
                )
            }
        }
    }

    private class WipeContext(
        val owner: PaykitSdkOperationLock,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WipeContext>
    }
}
