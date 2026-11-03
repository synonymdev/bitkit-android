package to.bitkit.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaykitBackupStateTrackingTest : BaseUnitTest() {
    @Test
    fun `backup decision uses content and treats unreadable revisions conservatively`() = test {
        val cases = listOf(
            Triple("same", "same", 0),
            Triple("before", "after", 1),
            Triple(null, "after", 1),
            Triple("before", null, 1),
        )
        for ((before, after, expectedChanges) in cases) {
            val revisions = mutableListOf(before, after)
            var changes = 0
            val result = withPaykitBackupStateTracking(
                readRevision = { revisions.removeAt(0) ?: throw AppError("Unreadable revision") },
                onChange = { changes++ },
            ) { "result" }

            assertEquals("result", result)
            assertEquals(expectedChanges, changes)
            assertTrue(revisions.isEmpty())
        }
    }

    @Test
    fun `partial failure marks changed state and preserves operation error`() = test {
        var revision = "before"
        var reads = 0
        var changes = 0
        val failure = AppError("Operation failed")
        val thrown = assertFailsWith<AppError> {
            withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    revision
                },
                onChange = { changes++ },
            ) {
                revision = "after"
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(1, changes)
        assertEquals(1, reads)
    }

    @Test
    fun `cancellation after mutation completes backup tracking`() = test {
        var revision = "before"
        var changes = 0
        var reads = 0
        var snapshot: PaykitBackupStateSnapshot? = PaykitBackupStateSnapshot("state", "before")
        val mutated = CompletableDeferred<Unit>()
        val job = launch {
            withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    yield()
                    revision
                },
                readStateRevision = { "state" },
                cachedSnapshot = snapshot,
                onSnapshot = { snapshot = it },
                onChange = { changes++ },
            ) {
                revision = "after"
                mutated.complete(Unit)
                awaitCancellation()
            }
        }
        mutated.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("after", revision)
        assertEquals(1, changes)
        assertEquals(0, reads)
        assertNull(snapshot)
    }

    @Test
    fun `unchanged operations reuse the backup fingerprint`() = test {
        var snapshot: PaykitBackupStateSnapshot? = null
        var reads = 0
        var changes = 0
        repeat(3) {
            withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    "content"
                },
                readStateRevision = { "state" },
                readObservedSnapshot = { PaykitBackupStateSnapshot("state", "content") },
                cachedSnapshot = snapshot,
                onSnapshot = { snapshot = it },
                onChange = { changes++ },
            ) {}
        }
        assertEquals(1, reads)
        assertEquals(0, changes)
        assertEquals(PaykitBackupStateSnapshot("state", "content"), snapshot)
    }

    @Test
    fun `changed state revisions still compare backup content`() = test {
        for ((cachedState, finalContent, expectedReads) in listOf(
            Triple("before", "content", 1),
            Triple("before", "changed", 1),
            Triple("stale", "changed", 1),
        )) {
            var state = "before"
            var content = "content"
            var reads = 0
            var changes = 0
            var snapshot: PaykitBackupStateSnapshot? = null
            withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    content
                },
                readStateRevision = { state },
                cachedSnapshot = PaykitBackupStateSnapshot(cachedState, "content"),
                onSnapshot = { snapshot = it },
                onChange = { changes++ },
            ) {
                state = "after"
                content = finalContent
            }
            assertEquals(expectedReads, reads)
            assertEquals(if (finalContent == "content") 0 else 1, changes)
            assertEquals(PaykitBackupStateSnapshot("after", finalContent), snapshot)
        }
    }

    @Test
    fun `paired observations avoid fingerprint reads and retain the cached change baseline`() = test {
        val before = PaykitBackupStateSnapshot("before", "content")
        val changed = PaykitBackupStateSnapshot("changed", "changed")
        for ((initial, final, expectedChanges) in listOf(
            Triple(before, before.copy(stateRevision = "leased"), 0),
            Triple(before, changed, 1),
            Triple(changed, changed, 1),
        )) {
            var observed = initial
            var snapshot: PaykitBackupStateSnapshot? = PaykitBackupStateSnapshot("cached", "content")
            var reads = 0
            var changes = 0
            val result = withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    observed.backupRevision
                },
                readStateRevision = { observed.stateRevision },
                readObservedSnapshot = { observed },
                cachedSnapshot = snapshot,
                onSnapshot = { snapshot = it },
                onChange = { changes++ },
            ) {
                observed = final
                "result"
            }

            assertEquals("result", result)
            assertEquals(0, reads)
            assertEquals(expectedChanges, changes)
            assertEquals(observed, snapshot)
        }
    }

    @Test
    fun `unavailable or mismatched observations fall back without losing known backup changes`() = test {
        val observations: List<() -> PaykitBackupStateSnapshot?> = listOf(
            { null },
            { PaykitBackupStateSnapshot("unrelated", "changed") },
            { throw AppError("Unavailable observation") },
        )
        for (readObservedSnapshot in observations) {
            val state = "before"
            var snapshot: PaykitBackupStateSnapshot? = PaykitBackupStateSnapshot("cached", "content")
            var reads = 0
            var changes = 0
            withPaykitBackupStateTracking(
                readRevision = {
                    reads++
                    "changed"
                },
                readStateRevision = { state },
                readObservedSnapshot = readObservedSnapshot,
                cachedSnapshot = snapshot,
                onSnapshot = { snapshot = it },
                onChange = { changes++ },
            ) {}

            assertEquals(1, reads)
            assertEquals(1, changes)
            assertEquals(PaykitBackupStateSnapshot("before", "changed"), snapshot)
        }
    }

    @Test
    fun `cleared snapshots require a fresh baseline despite matching historical observations`() = test {
        var observed = PaykitBackupStateSnapshot("old-key", "old-content")
        var state = observed.stateRevision
        var snapshot: PaykitBackupStateSnapshot? = null
        var reads = 0
        var changes = 0
        withPaykitBackupStateTracking(
            readRevision = {
                reads++
                state = "current"
                "current-content"
            },
            readStateRevision = { state },
            readObservedSnapshot = { observed },
            cachedSnapshot = snapshot,
            onSnapshot = { snapshot = it },
            onChange = { changes++ },
        ) {
            state = "after"
            observed = PaykitBackupStateSnapshot(state, "current-content")
        }

        assertEquals(1, reads)
        assertEquals(0, changes)
        assertEquals(observed, snapshot)
    }

    @Test
    fun `failed operations invalidate unchanged snapshots without reading remote state`() = test {
        for (failure in listOf(AppError("Write outcome unknown"), CancellationException("Cancelled"))) {
            var snapshot: PaykitBackupStateSnapshot? = PaykitBackupStateSnapshot("state", "before")
            var reads = 0
            var observedReads = 0
            var changes = 0
            val thrown = assertFailsWith(failure::class) {
                withPaykitBackupStateTracking(
                    readRevision = {
                        reads++
                        "before"
                    },
                    readStateRevision = { "state" },
                    readObservedSnapshot = {
                        observedReads++
                        PaykitBackupStateSnapshot("state", "before")
                    },
                    cachedSnapshot = snapshot,
                    onSnapshot = { snapshot = it },
                    onChange = { changes++ },
                ) { throw failure }
            }
            assertSame(failure, thrown)
            assertEquals(0, reads)
            assertEquals(0, observedReads)
            assertEquals(1, changes)
            assertNull(snapshot)
        }
    }
}
