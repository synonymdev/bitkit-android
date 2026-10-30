package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import to.bitkit.utils.AppError
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Admission covers local completion and rollback as well as the native session change. */
internal class PubkyIdentityOperations(private val beforeTransition: suspend () -> Unit = {}) {
    private val lock = Any()
    private val active = mutableSetOf<Operation>()
    private var removal: Operation? = null
    private var wiping = false
    private var wipeOperation: Operation? = null

    suspend fun <T> transition(block: suspend () -> T): T {
        currentCoroutineContext()[Operation]?.takeIf { it.owner === this }?.let {
            check(synchronized(lock) { it in active }) { "Pubky operation has already finished" }
            return block()
        }
        val operation = synchronized(lock) {
            if (wiping || removal != null) throw PubkyIdentityBusy
            Operation(this).also(active::add)
        }
        return run(operation) {
            beforeTransition()
            block()
        }
    }

    suspend fun <T> remove(block: suspend () -> T): T {
        val operation = synchronized(lock) {
            if (wiping || active.isNotEmpty()) throw PubkyIdentityBusy
            Operation(this).also {
                removal = it
                active.add(it)
            }
        }
        return run(operation, block)
    }

    suspend fun requireRemoval() {
        val operation = currentCoroutineContext()[Operation]
        check(synchronized(lock) { operation != null && operation === removal && operation in active }) {
            "Profile removal admission is required"
        }
    }

    suspend fun requireWipe() {
        val operation = currentCoroutineContext()[Operation]
        check(synchronized(lock) { operation != null && operation === wipeOperation && operation in active }) {
            "Wallet wipe admission is required"
        }
    }

    suspend fun <T> wipe(block: suspend () -> T): T {
        check(currentCoroutineContext()[Operation]?.owner !== this) { "Pubky operation cannot start a wallet wipe" }
        val (draining, cancelled) = synchronized(lock) {
            if (wiping) throw PubkyIdentityBusy
            wiping = true
            active.toList() to removal
        }
        // Revoke and drain before the SDK barrier; no monitor or session mutex is held while awaiting cleanup.
        cancelled?.cancel()
        try {
            draining.forEach { it.drained.await() }
            val operation = synchronized(lock) {
                Operation(this).also {
                    active.add(it)
                    wipeOperation = it
                }
            }
            return run(operation, block)
        } finally {
            synchronized(lock) {
                wiping = false
                wipeOperation = null
            }
        }
    }

    private suspend fun <T> run(operation: Operation, block: suspend () -> T): T {
        try {
            return withContext(operation) {
                operation.bind(requireNotNull(currentCoroutineContext()[Job]))
                block()
            }
        } finally {
            synchronized(lock) {
                active.remove(operation)
                if (removal === operation) removal = null
            }
            operation.drained.complete(Unit)
        }
    }

    private class Operation(val owner: PubkyIdentityOperations) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Operation>

        private val lock = Any()
        private var job: Job? = null
        private var cancelled = false
        val drained = CompletableDeferred<Unit>()

        fun bind(job: Job) {
            val cancelNow = synchronized(lock) {
                this.job = job
                cancelled
            }
            if (cancelNow) job.cancel()
        }

        fun cancel() {
            val current = synchronized(lock) {
                cancelled = true
                job
            }
            current?.cancel()
        }
    }
}

internal data object PubkyIdentityBusy : AppError("Pubky identity work is already in progress")
