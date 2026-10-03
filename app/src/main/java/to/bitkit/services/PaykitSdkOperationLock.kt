package to.bitkit.services

import com.synonym.paykit.PaykitException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class PaykitSdkOperationLock {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var isWiping = false

    suspend fun <T> withLock(operation: suspend () -> T): T {
        if (ownsWipe()) return operation()
        val admittedGeneration = admit()
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkAdmitted(admittedGeneration)
            operation()
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
            mutex.withLock {
                withContext(WipeContext(this, generation)) { operation() }
            }
        } finally {
            synchronized(stateLock) { isWiping = false }
        }
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

    private class WipeContext(
        val owner: PaykitSdkOperationLock,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WipeContext>
    }
}
