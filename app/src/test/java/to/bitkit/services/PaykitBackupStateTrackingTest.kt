package to.bitkit.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        var changes = 0
        val failure = AppError("Operation failed")
        val thrown = assertFailsWith<AppError> {
            withPaykitBackupStateTracking(
                readRevision = { revision },
                onChange = { changes++ },
            ) {
                revision = "after"
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(1, changes)
    }

    @Test
    fun `cancellation after mutation completes backup tracking`() = test {
        var revision = "before"
        var changes = 0
        val mutated = CompletableDeferred<Unit>()
        val job = launch {
            withPaykitBackupStateTracking(
                readRevision = {
                    yield()
                    revision
                },
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
    }
}
