package to.bitkit.ui.screens.transfer

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SavingsProgressScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a spark failure shows the failed state with an ok button`() {
        setContent(SavingsProgressState.SPARK_FAILED)

        composeRule.onNodeWithTag("SavingsSparkFailed").assertExists()
        composeRule.onNodeWithText("failed", substring = true, ignoreCase = true).assertExists()
        composeRule.onNodeWithText("Your Spark balance could not move to savings", substring = true).assertExists()
        composeRule.onNodeWithTag("SavingsSparkFailed-button").assertExists()
    }

    @Test
    fun `a spark failure while channels move says the channel funds still move`() {
        setContent(SavingsProgressState.SPARK_FAILED_PARTIAL)

        composeRule.onNodeWithText("Your channel funds are moving to savings", substring = true).assertExists()
        composeRule.onNodeWithTag("SavingsSparkFailed-button").assertExists()
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the rings take the share of the screen width ios gives them`() {
        setContent(SavingsProgressState.PROGRESS)

        val rings = composeRule.onNodeWithTag("TransferAnimation").getUnclippedBoundsInRoot()

        assertEquals(448f * 311f / 402f, rings.width.value, 0.5f)
    }

    private fun setContent(state: SavingsProgressState) {
        composeRule.setContent { AppThemeSurface { SavingsProgressContent(progressState = state) } }
    }
}
