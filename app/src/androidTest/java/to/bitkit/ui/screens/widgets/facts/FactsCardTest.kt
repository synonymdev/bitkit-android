package to.bitkit.ui.screens.widgets.facts

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUiAndroidTest
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUiAndroidTest
class FactsCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testHeadline = "Priced in Bitcoin, products can become cheaper over time."

    @Test
    fun testFactsCardShowsHeadlineAndBitcoinBadge() {
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(headline = testHeadline)
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("bitcoin_badge", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }

    @Test
    fun testFactsCardWithLongHeadline() {
        val longHeadline = "This is a very long fact that should be truncated because it exceeds " +
            "the maximum number of lines allowed in the facts card component and should show ellipsis"

        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(headline = longHeadline)
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("bitcoin_badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testFactsCardSmallShowsHeadlineAndBitcoinBadge() {
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCardSmall(headline = testHeadline)
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("bitcoin_badge", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }
}
