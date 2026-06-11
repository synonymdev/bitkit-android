package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class HeadlineCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testHeadline = "Bitcoin Price Reaches New All-Time High Amid Institutional Adoption"
    private val testTime = "2 hours ago"
    private val testSource = "bitcoinmagazine.com"
    private val testLink = "https://bitcoinmagazine.com/test-article"

    @Test
    fun testHeadlineCardWithAllElements() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertTextEquals(testSource)
    }

    @Test
    fun testHeadlineCardWithoutTime() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = false,
                    showSource = true,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardWithoutSource() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = true,
                    showSource = false,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardMinimal() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = false,
                    showSource = false,
                    time = testTime,
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }

    @Test
    fun testHeadlineCardWithEmptyTime() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = true,
                    showSource = true,
                    time = "",
                    headline = testHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testHeadlineCardWithLongHeadline() {
        val longHeadline =
            "This is a very long headline that should be truncated because it exceeds the maximum number of lines allowed in the headline card component and should show ellipsis"

        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCard(
                    showTime = true,
                    showSource = true,
                    time = testTime,
                    headline = longHeadline,
                    source = testSource,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testHeadlineCardSmallWithTime() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCardSmall(
                    showTime = true,
                    time = testTime,
                    headline = testHeadline,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertTextEquals(testHeadline)
    }

    @Test
    fun testHeadlineCardSmallWithoutTime() {
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlineCardSmall(
                    showTime = false,
                    time = testTime,
                    headline = testHeadline,
                    link = testLink
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
    }
}
