package to.bitkit.wear.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseUnitTest(
    testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) {
    @get:Rule(order = 0)
    val coroutinesTestRule = MainDispatcherRule(testDispatcher)

    protected val testDispatcher get() = coroutinesTestRule.testDispatcher

    protected fun test(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            block()
        } finally {
            onTestEnd()
        }
    }

    /**
     * Runs after each [test] body, before the test drains its scheduler: a test whose subject keeps a timer that
     * repeats forever stops it here, or the drain never ends.
     */
    protected open fun onTestEnd() = Unit
}
