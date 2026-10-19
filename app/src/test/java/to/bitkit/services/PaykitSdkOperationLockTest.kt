package to.bitkit.services

import com.synonym.paykit.PaykitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import to.bitkit.services.PaykitSdkOperationLock.Priority
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaykitSdkOperationLockTest {
    @Test
    fun `interactive work overtakes only queued publication with bounded fairness`() = runTest {
        val lock = PaykitSdkOperationLock()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val active = launch {
            lock.withLock(Priority.Background) {
                events.add("active")
                release.await()
            }
        }
        runCurrent()
        val priorities = listOf(
            Priority.Background,
            Priority.Background,
            Priority.Interactive,
            Priority.Interactive,
            Priority.Interactive,
            Priority.Interactive,
        )
        val queued = priorities.mapIndexed { index, priority ->
            launch { lock.withLock(priority) { events.add(index.toString()) } }.also { runCurrent() }
        }
        assertEquals(listOf("active"), events)
        release.complete(Unit)
        active.join()
        queued.forEach { it.join() }
        assertEquals(listOf("active", "2", "3", "4", "0", "5", "1"), events)
        lock.withLock {}
    }

    @Test
    fun `interactive work cannot cross an ordered operation`() = runTest {
        val lock = PaykitSdkOperationLock()
        val release = CompletableDeferred<Unit>()
        val active = launch { lock.withLock { release.await() } }
        runCurrent()
        val events = mutableListOf<Int>()
        val priorities = listOf(
            Priority.Background,
            Priority.Interactive,
            Priority.Ordered,
            Priority.Background,
            Priority.Interactive,
        )
        val queued = priorities.mapIndexed { index, priority ->
            launch { lock.withLock(priority) { events.add(index) } }.also { runCurrent() }
        }
        release.complete(Unit)
        active.join()
        queued.forEach { it.join() }
        assertEquals(listOf(1, 0, 2, 4, 3), events)
    }

    @Test
    fun `cancellation after priority handoff releases the lock to publication`() = runTest {
        val lock = PaykitSdkOperationLock()
        val release = CompletableDeferred<Unit>()
        val active = launch(UnconfinedTestDispatcher(testScheduler)) { lock.withLock { release.await() } }
        val background = async { lock.withLock(Priority.Background) { "published" } }
        val interactive = async { lock.withLock(Priority.Interactive) { error("cancelled operation ran") } }
        runCurrent()

        release.complete(Unit)
        assertTrue(active.isCompleted)
        interactive.cancel()
        assertFailsWith<CancellationException> { interactive.await() }
        assertEquals("published", background.await())
        lock.withLock {}
    }

    @Test
    fun `public reads do not block mutation but reject results across wallet wipe`() = runTest {
        val lock = PaykitSdkOperationLock()
        val releaseRead = CompletableDeferred<Unit>()
        val read = async {
            assertFailsWith<PaykitException.Storage> {
                lock.withoutLock { releaseRead.await() }
            }
        }
        runCurrent()
        lock.withLock { }
        lock.withWalletWipe {
            assertEquals("cleanup", lock.withoutLock { "cleanup" })
        }
        releaseRead.complete(Unit)
        assertEquals("wallet_wipe_in_progress", read.await().code)
        assertEquals("fresh", lock.withoutLock { "fresh" })
    }

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
        val queued = listOf(Priority.Background, Priority.Interactive).map { priority ->
            async {
                assertFailsWith<PaykitException.Storage> { lock.withLock(priority) { events.add("stale") } }
            }
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
        queued.forEach { assertEquals("wallet_wipe_in_progress", it.await().code) }
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
        val queued = Priority.entries.map { priority ->
            async { lock.withLock(priority) { error("cancelled operation ran") } }
        }
        runCurrent()
        queued.forEach { it.cancel() }
        release.complete(Unit)
        active.join()
        queued.forEach { assertFailsWith<CancellationException> { it.await() } }
        lock.withLock {}
    }
}
