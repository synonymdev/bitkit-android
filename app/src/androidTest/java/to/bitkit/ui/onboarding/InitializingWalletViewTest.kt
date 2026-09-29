package to.bitkit.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NodeLifecycleState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.shouldFinishWalletInitialization
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.utils.AppError
import to.bitkit.viewmodels.RestoreState
import kotlin.test.assertEquals

@ComposeUi
class InitializingWalletViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun slowRetryCompletesOnlyAfterNodeRuns() {
        var nodeState: NodeLifecycleState by mutableStateOf(NodeLifecycleState.ErrorStarting(AppError("offline")))
        var completionCount = 0
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AppThemeSurface {
                if (nodeState !is NodeLifecycleState.ErrorStarting) {
                    InitializingWalletView(
                        shouldFinish = shouldFinishWalletInitialization(nodeState, RestoreState.Retry(1), false),
                        onComplete = { completionCount++ },
                    )
                }
            }
        }

        composeTestRule.runOnIdle { nodeState = NodeLifecycleState.Initializing }
        composeTestRule.mainClock.advanceTimeBy(LOADING_MS.toLong() + 1000)
        composeTestRule.runOnIdle { assertEquals(0, completionCount) }

        composeTestRule.runOnIdle { nodeState = NodeLifecycleState.Running }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.runOnIdle { assertEquals(1, completionCount) }
    }
}
