package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class BalanceHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsLoaderInEyeSlotWhileLoading() {
        setContent(isLoading = true, hideBalance = false)

        composeTestRule.onNodeWithTag("TotalBalanceUpdating").assertExists()
        composeTestRule.onNodeWithTag("ShowBalance").assertDoesNotExist()
    }

    @Test
    fun hidesLoaderWhenNotLoading() {
        setContent(isLoading = false, hideBalance = false)

        composeTestRule.onNodeWithTag("TotalBalanceUpdating").assertDoesNotExist()
    }

    @Test
    fun keepsEyeIconWhileBalanceIsHidden() {
        setContent(isLoading = true, hideBalance = true)

        composeTestRule.onNodeWithTag("ShowBalance").assertExists()
        composeTestRule.onNodeWithTag("TotalBalanceUpdating").assertDoesNotExist()
    }

    private fun setContent(isLoading: Boolean, hideBalance: Boolean) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
