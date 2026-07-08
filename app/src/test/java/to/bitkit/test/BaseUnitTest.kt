package to.bitkit.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import to.bitkit.models.WalletScope

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseUnitTest(
    testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) {
    @get:Rule(order = 0)
    val coroutinesTestRule = MainDispatcherRule(testDispatcher)

    protected val testDispatcher get() = coroutinesTestRule.testDispatcher

    private var walletScopeRestore: AutoCloseable? = null

    @Before
    fun setUpWalletScope() {
        walletScopeRestore = WalletScope.pushTestOverride("wallet0")
    }

    @After
    fun tearDownWalletScope() {
        walletScopeRestore?.close()
        walletScopeRestore = null
    }

    protected fun test(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }
}
