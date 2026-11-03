package to.bitkit.services

import com.synonym.paykit.PaykitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

internal class PaykitSdkOperationLock {
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

    suspend fun <T> withLock(priority: Priority = Priority.Ordered, operation: suspend () -> T): T {
        if (ownsWipe()) return operation()
        val admittedGeneration = admit()
        acquire(priority)
        return try {
            currentCoroutineContext().ensureActive()
            checkAdmitted(admittedGeneration)
            operation()
        } finally {
            release()
        }
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
        synchronized(stateLock) {
            checkAvailable()
            isWiping = true
            generation++
        }
        return try {
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

    private suspend fun acquire(priority: Priority) {
        val waiter = synchronized(stateLock) {
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

    private class Waiter(val priority: Priority, val ready: CompletableDeferred<Unit>)

    private class WipeContext(
        val owner: PaykitSdkOperationLock,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WipeContext>
    }
}
