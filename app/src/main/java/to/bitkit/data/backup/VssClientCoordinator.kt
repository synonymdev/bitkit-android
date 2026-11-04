package to.bitkit.data.backup

import com.synonym.vssclient.vssShutdownClient
import com.synonym.vssclient.vssShutdownLdkClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Owns the two standalone native slots; the running Lightning node has a separate lifetime. */
@Singleton
class VssClientCoordinator @Inject constructor() {
    enum class Domain { APP, LDK }

    class Owner internal constructor(internal val generation: Long)

    private class Admission(val coordinator: VssClientCoordinator, val owner: Owner) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Admission>
    }

    private class Client {
        val setupMutex = Mutex()
        var ready = CompletableDeferred<Unit>()
        var attempted = false
    }

    private val lock = Any()
    private val transitionMutex = Mutex()
    private var generation = 0L
    private var suspended = false
    private val jobs = mutableSetOf<Job>()
    private val clients = Domain.entries.associateWith { Client() }
    private val _owners = MutableStateFlow<Owner?>(Owner(generation))
    val owners = _owners.asStateFlow()

    fun captureOwner(): Owner = synchronized(lock) {
        if (suspended) throw CancellationException("VSS clients are suspended")
        Owner(generation)
    }

    private fun checkOwner(owner: Owner) = synchronized(lock) {
        if (suspended || owner.generation != generation) {
            throw CancellationException("VSS wallet owner changed")
        }
    }

    /** Retain admission across payload capture, retries, compound requests and their local tails. */
    suspend fun <T> withWallet(owner: Owner? = null, block: suspend () -> T): T = coroutineScope {
        val inherited = currentCoroutineContext()[Admission]?.takeIf { it.coordinator === this@VssClientCoordinator }
        val original = owner ?: inherited?.owner ?: captureOwner()
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            checkOwner(original)
            jobs.add(job)
        }
        try {
            withContext(Admission(this@VssClientCoordinator, original)) {
                val result = runCatching { block() }
                currentCoroutineContext().ensureActive()
                checkOwner(original)
                result.getOrThrow()
            }
        } finally {
            synchronized(lock) { jobs.remove(job) }
        }
    }

    suspend fun setup(domain: Domain, block: suspend () -> Unit) = withWallet {
        val client = clients.getValue(domain)
        client.setupMutex.withLock {
            currentCoroutineContext().ensureActive()
            val gate = synchronized(lock) { client.ready }
            if (gate.isCompleted && !gate.isCancelled) return@withLock
            try {
                synchronized(lock) { client.attempted = true }
                block()
                currentCoroutineContext().ensureActive()
                gate.complete(Unit)
            } catch (error: Throwable) {
                gate.completeExceptionally(error)
                synchronized(lock) {
                    if (client.ready === gate) client.ready = CompletableDeferred()
                }
                throw error
            }
        }
    }

    suspend fun <T> withClient(domain: Domain, block: suspend () -> T): T = withWallet {
        val gate = synchronized(lock) { clients.getValue(domain).ready }
        gate.await()
        currentCoroutineContext().ensureActive()
        block()
    }

    /** Close admission before credentials change, including cleanup when the caller is cancelled. */
    suspend fun <T> withSuspendedClients(
        shutdown: (Domain) -> Unit = {
            when (it) {
                Domain.APP -> vssShutdownClient()
                Domain.LDK -> vssShutdownLdkClient()
            }
        },
        block: suspend () -> T,
    ): T {
        check(currentCoroutineContext()[Admission]?.coordinator !== this) {
            "Cannot replace a wallet inside an admitted VSS operation"
        }
        val original = captureOwner()
        return transitionMutex.withLock {
            val pending = synchronized(lock) {
                checkOwner(original)
                suspended = true
                _owners.update { null }
                generation += 1
                clients.values.forEach { it.ready.cancel() }
                jobs.toList()
            }
            var shutdownComplete = false
            try {
                withContext(NonCancellable) {
                    pending.forEach { it.cancel(CancellationException("VSS wallet retired")) }
                    pending.joinAll()
                    clients.forEach { (domain, client) -> if (client.attempted) shutdown(domain) }
                    shutdownComplete = true
                }
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                synchronized(lock) {
                    if (shutdownComplete) {
                        clients.values.forEach {
                            it.ready = CompletableDeferred()
                            it.attempted = false
                        }
                        suspended = false
                        _owners.update { Owner(generation) }
                    }
                }
            }
        }
    }
}
