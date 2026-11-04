package to.bitkit.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VssClientCoordinatorTest : BaseUnitTest() {
    private val sut = VssClientCoordinator()

    @Test
    fun `retirement waits for cancelled setup cleanup before shutdown and replacement`() = test {
        val cleanup = CompletableDeferred<Unit>()
        var shutdown = false
        val old = launch {
            sut.setup(VssClientCoordinator.Domain.APP) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        runCurrent()
        val transition = launch { sut.withSuspendedClients({ shutdown = true }) {} }
        runCurrent()
        assertFalse(shutdown)
        assertFalse(transition.isCompleted)
        assertFailsWith<CancellationException> { sut.withWallet {} }
        cleanup.complete(Unit)
        transition.join()
        assertTrue(old.isCancelled)
        assertTrue(shutdown)
        sut.setup(VssClientCoordinator.Domain.APP) {}
        assertEquals("replacement", sut.withClient(VssClientCoordinator.Domain.APP) { "replacement" })
    }

    @Test
    fun `both domains and local result tails finish before credential change`() = test {
        VssClientCoordinator.Domain.entries.forEach { sut.setup(it) {} }
        val release = CompletableDeferred<Unit>()
        val finished = mutableSetOf<VssClientCoordinator.Domain>()
        val shutdown = mutableSetOf<VssClientCoordinator.Domain>()
        VssClientCoordinator.Domain.entries.forEach { domain ->
            launch {
                sut.withClient(domain) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            release.await()
                            finished.add(domain)
                        }
                    }
                }
            }
        }
        runCurrent()
        val transition = launch {
            sut.withSuspendedClients({ shutdown.add(it) }) {
                assertEquals(VssClientCoordinator.Domain.entries.toSet(), finished)
                assertEquals(finished, shutdown)
                assertFailsWith<CancellationException> { sut.captureOwner() }
            }
        }
        runCurrent()
        assertTrue(shutdown.isEmpty())
        release.complete(Unit)
        transition.join()
    }

    @Test
    fun `old payload and compound followup cannot enter replacement client`() = test {
        sut.setup(VssClientCoordinator.Domain.APP) {}
        val owner = sut.captureOwner()
        var calls = 0
        val followup = CompletableDeferred<Unit>()
        val original = launch {
            sut.withWallet(owner) {
                sut.withClient(VssClientCoordinator.Domain.APP) { calls++ }
                followup.await()
                sut.withClient(VssClientCoordinator.Domain.APP) { calls++ }
            }
        }
        runCurrent()
        sut.withSuspendedClients({}) {}
        assertTrue(original.isCancelled)
        sut.setup(VssClientCoordinator.Domain.APP) {}
        assertFailsWith<CancellationException> { sut.withWallet(owner) { calls++ } }
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled retirement still drains and reopens without changing credentials`() = test {
        val cleanup = CompletableDeferred<Unit>()
        var changed = false
        launch {
            sut.withWallet {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
        }
        runCurrent()
        val transition = launch { sut.withSuspendedClients({}) { changed = true } }
        runCurrent()
        transition.cancel()
        assertFailsWith<CancellationException> { sut.captureOwner() }
        cleanup.complete(Unit)
        transition.join()
        assertFalse(changed)
        sut.withWallet {}
    }

    @Test
    fun `credential failure reopens uninitialized slots and invalidates previous owner`() = test {
        val original = sut.captureOwner()
        assertFailsWith<FixtureFailure> { sut.withSuspendedClients({}) { throw FixtureFailure() } }
        assertFailsWith<CancellationException> { sut.withWallet(original) {} }
        var initialized = false
        sut.setup(VssClientCoordinator.Domain.APP) { initialized = true }
        assertTrue(initialized)
    }

    @Test
    fun `setup waiters share successful setup and calls can overlap`() = test {
        val setupRelease = CompletableDeferred<Unit>()
        var setups = 0
        val first = launch {
            sut.setup(VssClientCoordinator.Domain.APP) {
                setups++
                setupRelease.await()
            }
        }
        val second = launch { sut.setup(VssClientCoordinator.Domain.APP) { setups++ } }
        runCurrent()
        setupRelease.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, setups)
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val calls = List(2) {
            async {
                sut.withClient(VssClientCoordinator.Domain.APP) {
                    entered++
                    release.await()
                }
            }
        }
        runCurrent()
        assertEquals(2, entered)
        release.complete(Unit)
        calls.forEach { it.await() }
    }

    @Test
    fun `retirement wakes calls waiting for explicit setup`() = test {
        val waiting = launch { sut.withClient(VssClientCoordinator.Domain.APP) { error("Not initialized") } }
        runCurrent()
        sut.withSuspendedClients({}) {}
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun `shutdown failure leaves admission closed`() = test {
        sut.setup(VssClientCoordinator.Domain.APP) {}
        assertFailsWith<FixtureFailure> { sut.withSuspendedClients({ throw FixtureFailure() }) {} }
        assertFailsWith<CancellationException> { sut.withWallet {} }
    }

    private class FixtureFailure : AppError("fixture failure")
}
