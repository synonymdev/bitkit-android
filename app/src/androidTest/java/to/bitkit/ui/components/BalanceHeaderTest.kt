package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class BalanceHeaderTest {
    private companion object {
        const val HEADER = "TotalBalance"
        const val UPDATING = "TotalBalanceUpdating"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private var isLoading by mutableStateOf(false)
    private var hideBalance by mutableStateOf(false)

    @Test
    fun marksBalanceAsUpdatingWhileLoading() {
        isLoading = true
        setContent()

        composeTestRule.onNodeWithTag(UPDATING).assertExists()
        composeTestRule.onNodeWithTag(UPDATING)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Updating balance…"))
    }

    @Test
    fun doesNotMarkBalanceWhenNotLoading() {
        setContent()

        composeTestRule.onNodeWithTag(UPDATING).assertDoesNotExist()
    }

    @Test
    fun shimmersHiddenBalanceAndKeepsEyeIcon() {
        isLoading = true
        hideBalance = true
        setContent()

        composeTestRule.onNodeWithTag(UPDATING).assertExists()
        composeTestRule.onNodeWithTag("ShowBalance").assertExists()
    }

    @Test
    fun updatingClearsWithoutMovingHeader() {
        setContent()
        val idleBounds = composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot()

        isLoading = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(UPDATING).assertExists()
        assertEquals(idleBounds, composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot())

        isLoading = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(UPDATING).assertDoesNotExist()
        assertEquals(idleBounds, composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot())
    }

    private fun setContent() {
        composeTestRule.setContent {
            AppThemeSurface {
                BalanceHeader(
                    isBitcoinPrimary = true,
                    smallRowSymbol = "$",
                    smallRowText = "27.36",
                    largeRowText = "136 825",
                    largeRowSymbol = "₿",
                    showSymbol = true,
                    hideBalance = hideBalance,
                    showEyeIcon = true,
                    isLoading = isLoading,
                    onClick = {},
                    testTag = HEADER,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
