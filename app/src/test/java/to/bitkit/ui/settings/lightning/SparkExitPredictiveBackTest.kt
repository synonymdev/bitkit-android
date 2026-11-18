package to.bitkit.ui.settings.lightning

import android.content.Context
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import to.bitkit.repositories.SparkExitFixtures
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitSavingsFunding
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.ui.theme.AppThemeSurface
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A back swipe on Pay to confirm composes Funding underneath; cancelling it must leave Pay watching. */
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SparkExitPredictiveBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val exitState = MutableStateFlow(SparkExitState(fundingAddress = SparkExitFixtures.FUNDING_ADDRESS))
    private val repo = mock<SparkExitRepo> { on { state } doReturn exitState }
    private val sparkRepo = mock<SparkRepo> { on { state } doReturn MutableStateFlow(SparkState()) }

    @Volatile
    private var isPaid = false
    private var starts = 0

    private lateinit var nav: NavHostController
    private lateinit var dispatcher: OnBackPressedDispatcher

    private fun showPay() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))
        whenever { repo.loadQuote() } doSuspendableAnswer { Result.success(quote) }
        whenever { repo.savingsFundingFee(anyOrNull()) } doSuspendableAnswer {
            Result.success(SparkExitSavingsFunding(feeSats = 300uL, canPay = false))
        }
        whenever { repo.start(any()) } doSuspendableAnswer {
            starts++
            Result.success(Unit)
        }
        whenever { repo.refreshFundingAddress() } doSuspendableAnswer {
            val seen = if (isPaid) 25_000uL else 0uL
            exitState.value = exitState.value.copy(fundingSeenSats = seen, fundingSeenCount = if (isPaid) 1 else 0)
            Result.success(seen)
        }
        val viewModel = SparkExitViewModel(mock<Context>(), repo, sparkRepo)
        viewModel.loadQuote()
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            nav = rememberNavController()
            AppThemeSurface {
                NavHost(nav, startDestination = "funding") {
                    composable("funding") {
                        SparkExitFundingScreen(
                            viewModel = viewModel,
                            onBack = {},
                            onPayFromSavings = {},
                            onPayExternally = { nav.navigate("pay") },
                        )
                    }
                    composable("pay") { SparkExitPayEffects(viewModel, onAutoStart = {}) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            viewModel.onPayExternally()
            nav.navigate("pay")
        }
        composeRule.waitForIdle()
        assertEquals("pay", nav.currentDestination?.route)
    }

    private fun payFromAnotherWalletAndWait() {
        isPaid = true
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `a payment from another wallet starts the exit`() {
        showPay()

        payFromAnotherWalletAndWait()

        assertTrue(starts > 0)
    }

    @Test
    fun `a payment after a cancelled back swipe still starts the exit`() {
        showPay()
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        assertEquals("pay", nav.currentDestination?.route)

        payFromAnotherWalletAndWait()

        assertTrue(starts > 0)
    }
}
