package to.bitkit.async

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineScopesTest : BaseUnitTest() {

    @Test
    fun `appScope stays active after an uncaught exception in a child`() = test {
        val scope = appScope(testDispatcher, "Test")

        scope.launch { throw IllegalStateException("boom") }
        runCurrent()

        assertTrue(scope.isActive)

        var secondChildRan = false
        scope.launch { secondChildRan = true }
        runCurrent()

        assertTrue(secondChildRan)
        scope.cancel()
    }
}
