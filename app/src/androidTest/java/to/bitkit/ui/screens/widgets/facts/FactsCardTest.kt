package to.bitkit.ui.screens.widgets.facts

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.ui.theme.AppThemeSurface

class FactsCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testHeadline = "Priced in Bitcoin, products can become cheaper over time."

    @Test
    fun testFactsCardWithAllElements() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = true,
                    showSource = true,
                    headline = testHeadline
                )
            }
        }

        // Assert all elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()

        // Verify text content
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertTextEquals("synonym.to")
    }

    @Test
    fun testFactsCardWithoutWidgetTitle() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = false,
                    showSource = true,
                    headline = testHeadline
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()

        // Assert widget title elements do not exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testFactsCardWithoutSource() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = true,
                    showSource = false,
                    headline = testHeadline
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        // Assert source elements do not exist
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testFactsCardMinimal() {
        // Arrange & Act - Only headline shown
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = false,
                    showSource = false,
                    headline = testHeadline
                )
            }
        }

        // Assert only essential elements exist
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        // Assert optional elements do not exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()

        // Verify headline text
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }

    @Test
    fun testFactsCardWithLongHeadline() {
        // Arrange
        val longHeadline = "This is a very long fact that should be truncated because it exceeds " +
            "the maximum number of lines allowed in the facts card component and should show ellipsis"

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = true,
                    showSource = true,
                    headline = longHeadline
                )
            }
        }

        // Assert headline exists and contains the text (may be truncated)
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testAllElementsExistInFullConfiguration() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsCard(
                    showWidgetTitle = true,
                    showSource = true,
                    headline = testHeadline
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()
    }
}
