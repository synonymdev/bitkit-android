package to.bitkit.services

import com.synonym.paykit.PaykitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaykitSdkOperationLockTest {
    @Test
    fun `wipe drains active work rejects queued work and permits cleanup and fresh work`() = runTest {
        val lock = PaykitSdkOperationLock()
        val releaseActive = CompletableDeferred<Unit>()
        val releaseWipe = CompletableDeferred<Unit>()
        val wipeStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val active = launch {
            lock.withLock {
                events.add("active")
                releaseActive.await()
            }
        }
        runCurrent()
        val queued = async {
            assertFailsWith<PaykitException.Storage> { lock.withLock { events.add("stale") } }
        }
        runCurrent()
        val wipe = launch {
            lock.withWalletWipe {
                lock.withLock { events.add("cleanup") }
                wipeStarted.complete(Unit)
                releaseWipe.await()
            }
        }
        runCurrent()
        assertFalse(wipeStarted.isCompleted)
        assertFailsWith<PaykitException.Storage> { lock.withLock { events.add("poll") } }
        releaseActive.complete(Unit)
        runCurrent()
        assertTrue(wipeStarted.isCompleted)
        assertEquals("wallet_wipe_in_progress", queued.await().code)
        releaseWipe.complete(Unit)
        active.join()
        wipe.join()
        lock.withLock { events.add("fresh") }
        assertEquals(listOf("active", "cleanup", "fresh"), events)
    }

    @Test
    fun `failed or cancelled wipe releases admission without admitting old queued work`() = runTest {
        val lock = PaykitSdkOperationLock()
        assertFailsWith<IllegalStateException> {
            lock.withWalletWipe { error("cleanup failed") }
        }
        val releaseActive = CompletableDeferred<Unit>()
        val active = launch { lock.withLock { releaseActive.await() } }
        runCurrent()
        val queued = async { assertFailsWith<PaykitException.Storage> { lock.withLock {} } }
        runCurrent()
        val wipe = launch { lock.withWalletWipe { error("cancelled wipe ran") } }
        runCurrent()
        wipe.cancel()
        wipe.join()
        releaseActive.complete(Unit)
        active.join()
        queued.await()
        var ran = false
        lock.withLock { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `unlocked work skips the lock but not wipe admission`() = runTest {
        val lock = PaykitSdkOperationLock()
        val releaseActive = CompletableDeferred<Unit>()
        val active = launch { lock.withLock { releaseActive.await() } }
        runCurrent()
        assertEquals("unlocked", lock.withoutLock { "unlocked" })

        val releaseOvertaken = CompletableDeferred<Unit>()
        val overtaken = async {
            assertFailsWith<PaykitException.Storage> { lock.withoutLock { releaseOvertaken.await() } }
        }
        runCurrent()
        val releaseWipe = CompletableDeferred<Unit>()
        val wipe = launch {
            lock.withWalletWipe {
                assertEquals("owner", lock.withoutLock { "owner" })
                releaseWipe.await()
            }
        }
        runCurrent()
        assertFailsWith<PaykitException.Storage> { lock.withoutLock { error("unlocked work ran during wipe") } }
        releaseOvertaken.complete(Unit)
        assertEquals("wallet_wipe_in_progress", overtaken.await().code)

        releaseActive.complete(Unit)
        releaseWipe.complete(Unit)
        active.join()
        wipe.join()
        assertEquals("fresh", lock.withoutLock { "fresh" })
    }

    @Test
    fun `cancelled queued operation never executes`() = runTest {
        val lock = PaykitSdkOperationLock()
        val release = CompletableDeferred<Unit>()
        val active = launch { lock.withLock { release.await() } }
        runCurrent()
        val queued = async { lock.withLock { error("cancelled operation ran") } }
        runCurrent()
        queued.cancel()
        release.complete(Unit)
        active.join()
        assertFailsWith<CancellationException> { queued.await() }
        lock.withLock {}
    }
}
