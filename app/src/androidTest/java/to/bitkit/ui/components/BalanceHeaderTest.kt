package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        const val LOADER = "TotalBalanceUpdating"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private var isLoading by mutableStateOf(false)
    private var hideBalance by mutableStateOf(false)

    @Test
    fun showsLoaderWhileLoading() {
        isLoading = true
        setContent()

        composeTestRule.onNodeWithTag(LOADER).assertExists()
        composeTestRule.onNodeWithTag("ShowBalance").assertDoesNotExist()
    }

    @Test
    fun hidesLoaderWhenNotLoading() {
        setContent()

        composeTestRule.onNodeWithTag(LOADER).assertDoesNotExist()
    }

    @Test
    fun keepsEyeIconWhileBalanceIsHidden() {
        isLoading = true
        hideBalance = true
        setContent()

        composeTestRule.onNodeWithTag("ShowBalance").assertExists()
        composeTestRule.onNodeWithTag(LOADER).assertDoesNotExist()
    }

    @Test
    fun loaderClearsWithoutMovingHeader() {
        setContent()
        val idleBounds = composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot()

        isLoading = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LOADER).assertExists()
        assertEquals(idleBounds, composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot())

        isLoading = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LOADER).assertDoesNotExist()
        assertEquals(idleBounds, composeTestRule.onNodeWithTag(HEADER).getUnclippedBoundsInRoot())
    }

    @Test
    fun loaderClearsWhenBalanceIsHiddenMidLoad() {
        isLoading = true
        setContent()
        composeTestRule.onNodeWithTag(LOADER).assertExists()

        hideBalance = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LOADER).assertDoesNotExist()
        composeTestRule.onNodeWithTag("ShowBalance").assertExists()
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
