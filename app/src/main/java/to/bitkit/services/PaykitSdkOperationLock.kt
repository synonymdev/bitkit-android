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
        val wipeContext = currentCoroutineContext()[WipeContext]
        if (synchronized(stateLock) {
                isWiping && wipeContext?.owner === this && wipeContext.generation == generation
            }
        ) {
            return operation()
        }
        val admittedGeneration = synchronized(stateLock) {
            checkAvailable()
            generation
        }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                checkAvailable()
                if (admittedGeneration != generation) throw wipeError()
            }
            operation()
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
