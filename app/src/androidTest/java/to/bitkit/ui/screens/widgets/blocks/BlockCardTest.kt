package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.ui.theme.AppThemeSurface

class BlockCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBlock = "761,405"
    private val testTime = "01:31:42 UTC"
    private val testDate = "11/2/2022"
    private val testTransactions = "2,175"
    private val testSize = "1,606Kb"
    private val testSource = "mempool.io"

    @Test
    fun testBlockCardWithAllElements() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = true,
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    source = testSource
                )
            }
        }

        // Assert all elements exist
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_date_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_transactions_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_size_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_source_row", useUnmergedTree = true).assertExists()

        // Verify text content
        composeTestRule.onNodeWithTag("block_card_block_text", useUnmergedTree = true).assertTextEquals(testBlock)
        composeTestRule.onNodeWithTag("block_card_time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("block_card_date_text", useUnmergedTree = true).assertTextEquals(testDate)
        composeTestRule.onNodeWithTag("block_card_transactions_text", useUnmergedTree = true).assertTextEquals(testTransactions)
        composeTestRule.onNodeWithTag("block_card_size_text", useUnmergedTree = true).assertTextEquals(testSize)
        composeTestRule.onNodeWithTag("block_card_source_text", useUnmergedTree = true).assertTextEquals(testSource)
    }

    @Test
    fun testBlockCardWithoutWidgetTitle() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = false,
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    source = testSource
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("block_card_block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_source_row", useUnmergedTree = true).assertExists()

        // Assert widget title elements do not exist
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_widget_title_icon", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_widget_title_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardWithoutSource() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = true,
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showSource = false,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    source = testSource
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_block_row", useUnmergedTree = true).assertExists()

        // Assert source elements do not exist
        composeTestRule.onNodeWithTag("block_card_source_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_source_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardMinimal() {
        // Arrange & Act - Only block number shown
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = false,
                    showBlock = true,
                    showTime = false,
                    showDate = false,
                    showTransactions = false,
                    showSize = false,
                    showSource = false,
                    block = testBlock,
                    time = "",
                    date = "",
                    transactions = "",
                    size = "",
                    source = ""
                )
            }
        }

        // Assert only block row exists
        composeTestRule.onNodeWithTag("block_card_block_row", useUnmergedTree = true).assertExists()

        // Assert other elements do not exist
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_time_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_source_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardWithEmptyValues() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = true,
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showSource = true,
                    block = "",
                    time = "",
                    date = "",
                    transactions = "",
                    size = "",
                    source = ""
                )
            }
        }

        // Assert only widget title and labels exist (since values are empty)
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertExists()

        // Assert text elements don't exist (since values are empty)

        composeTestRule.onNodeWithTag("block_card_time_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_date_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_transactions_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_size_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_source_label", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("block_card_block_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_block_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_time_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_date_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_transactions_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_size_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("block_card_source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testAllElementsExistInFullConfiguration() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showWidgetTitle = true,
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    source = testSource
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("block_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_widget_title_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_block_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_block_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_time_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_date_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_date_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_date_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_transactions_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_transactions_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_transactions_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_size_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_size_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_size_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_source_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("block_card_source_text", useUnmergedTree = true).assertExists()
    }
}
