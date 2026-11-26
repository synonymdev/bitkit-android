package to.bitkit.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyIdentityOperationsTest {
    @Test
    fun `removal excludes identity replacement through local completion`() = runTest {
        val operations = PubkyIdentityOperations()
        val local = CompletableDeferred<Unit>()
        var writes = 0
        val removal = launch {
            operations.remove {
                operations.transition { writes++ }
                local.await()
            }
        }
        runCurrent()
        assertFailsWith<PubkyIdentityBusy> { operations.transition { writes++ } }
        assertFailsWith<PubkyIdentityBusy> { operations.remove { writes++ } }
        assertEquals(1, writes)
        local.complete(Unit)
        removal.join()
        operations.transition { writes++ }
        assertEquals(2, writes)
    }

    @Test
    fun `concurrent transition tails finish before removal can start`() = runTest {
        val operations = PubkyIdentityOperations()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = launch { operations.transition { first.await() } }
        val b = launch { operations.transition { second.await() } }
        runCurrent()
        first.complete(Unit)
        a.join()
        assertFailsWith<PubkyIdentityBusy> { operations.remove {} }
        second.complete(Unit)
        b.join()
        operations.remove {}
    }

    @Test
    fun `wipe revokes removal and drains noncancellable local cleanup before reset`() = runTest {
        val operations = PubkyIdentityOperations()
        val local = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val removal = launch {
            operations.remove {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        local.await()
                        operations.transition { events += "local" }
                    }
                }
            }
        }
        runCurrent()
        val wipe = launch { operations.wipe { operations.transition { events += "wipe" } } }
        try {
            runCurrent()
            assertFalse(wipe.isCompleted)
            assertFailsWith<PubkyIdentityBusy> { operations.transition {} }
            assertFailsWith<PubkyIdentityBusy> { operations.remove {} }
            local.complete(Unit)
            removal.join()
            wipe.join()
            assertEquals(listOf("local", "wipe"), events)
            operations.transition {}
        } finally {
            local.complete(Unit)
            removal.cancel()
            wipe.cancel()
        }
    }

    @Test
    fun `wipe drains an admitted transition rollback and rejects new identity work`() = runTest {
        val operations = PubkyIdentityOperations()
        val rollback = CompletableDeferred<Unit>()
        var restored = false
        val transition = launch {
            operations.transition {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        rollback.await()
                        operations.transition { restored = true }
                    }
                }
            }
        }
        runCurrent()
        transition.cancel()
        val wipe = launch { operations.wipe { assertTrue(restored) } }
        try {
            runCurrent()
            assertFalse(wipe.isCompleted)
            assertFailsWith<PubkyIdentityBusy> { operations.remove {} }
            rollback.complete(Unit)
            transition.join()
            wipe.join()
        } finally {
            rollback.complete(Unit)
            transition.cancel()
            wipe.cancel()
        }
    }

    @Test
    fun `ended context cannot authorize later work and nested wipe cannot self-deadlock`() = runTest {
        val operations = PubkyIdentityOperations()
        lateinit var ended: CoroutineContext
        operations.remove {
            ended = currentCoroutineContext()
            assertFailsWith<IllegalStateException> { operations.wipe {} }
        }
        // Preserve the context element but use the current active Job for this deliberately stale continuation.
        withContext(ended.minusKey(kotlinx.coroutines.Job)) {
            assertFailsWith<IllegalStateException> { operations.transition {} }
        }
        operations.remove {}
    }
}
