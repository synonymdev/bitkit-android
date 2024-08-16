package to.bitkit.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@ExperimentalCoroutinesApi
abstract class BaseTest(
    testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) {
    @get:Rule
    val coroutinesTestRule = MainDispatcherRule(testDispatcher)

    protected val testDispatcher get() = coroutinesTestRule.testDispatcher

    protected fun test(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }
}
