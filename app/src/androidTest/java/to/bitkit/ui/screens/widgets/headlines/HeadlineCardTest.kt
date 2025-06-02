package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.ui.theme.AppThemeSurface

class HeadlineCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testHeadline = "Bitcoin Price Reaches New All-Time High Amid Institutional Adoption"
    private val testTime = "2 hours ago"
    private val testSource = "bitcoinmagazine.com"
    private val testLink = "https://bitcoinmagazine.com/test-article"

    @Test
    fun testHeadlineCardWithAllElements() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert all elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()

        // Verify text content
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertTextEquals(testSource)
    }

    @Test
    fun testHeadlineCardWithoutWidgetTitle() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = false,
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()

        // Assert widget title elements do not exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardWithoutTime() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = false,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()

        // Assert time element does not exist
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardWithoutSource() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = true,
                    showSource = false,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        // Assert source elements do not exist
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardMinimal() {
        // Arrange & Act - Only headline shown
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = false,
                    showTime = false,
                    showSource = false,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert only essential elements exist
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        // Assert optional elements do not exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()

        // Verify headline text
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }

    @Test
    fun testHeadlineCardWithEmptyTime() {
        // Arrange & Act - Time is empty string
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = true,
                    showSource = true,
                    time = "", // Empty time
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()

        // Assert time element does not exist when time is empty
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardWithLongHeadline() {
        // Arrange
        val longHeadline =
            "This is a very long headline that should be truncated because it exceeds the maximum number of lines allowed in the headline card component and should show ellipsis"

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = longHeadline,
                    source = testSource,
                    link = testLink
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
                HeadlineCard(
                    showWidgetTitle = true,
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()
    }
}
