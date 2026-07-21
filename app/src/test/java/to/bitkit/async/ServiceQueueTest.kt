package to.bitkit.async

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceQueueTest : BaseUnitTest() {

    @Test
    fun `background rethrows CancellationException without wrapping it`() = test {
        assertFailsWith<CancellationException> {
            ServiceQueue.CORE.background(testDispatcher) {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `background wraps non-cancellation failures in AppError`() = test {
        assertFailsWith<AppError> {
            ServiceQueue.CORE.background(testDispatcher) {
                throw IllegalStateException("boom")
            }
        }
    }
}
