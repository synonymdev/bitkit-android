package to.bitkit.ext

import kotlinx.coroutines.CancellationException
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoroutinesTest : BaseUnitTest() {

    @Test
    fun `runSuspendCatching wraps a successful result`() = test {
        val result = runSuspendCatching { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `runSuspendCatching wraps a thrown exception as failure`() = test {
        val error = IllegalStateException("boom")

        val result = runSuspendCatching { throw error }

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `runSuspendCatching re-throws CancellationException`() = test {
        assertFailsWith<CancellationException> {
            runSuspendCatching { throw CancellationException("cancelled") }
        }
    }
}
