package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUiAndroidTest
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUiAndroidTest
class BlockCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBlock = "761,405"
    private val testTime = "01:31:42 UTC"
    private val testDate = "11/2/2022"
    private val testTransactions = "2,175"
    private val testSize = "1,606Kb"
    private val testFees = "25 059 357"
    private val testSource = "mempool.io"

    @Test
    fun testBlockCardWithAllElements() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showFees = true,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    fees = testFees,
                    source = testSource,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("block_text", useUnmergedTree = true).assertTextEquals(testBlock)
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("date_text", useUnmergedTree = true).assertTextEquals(testDate)
        composeTestRule.onNodeWithTag("transactions_text", useUnmergedTree = true).assertTextEquals(testTransactions)
        composeTestRule.onNodeWithTag("size_text", useUnmergedTree = true).assertTextEquals(testSize)
        composeTestRule.onNodeWithTag("fees_text", useUnmergedTree = true).assertTextEquals(testFees)
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertTextEquals(testSource)
    }

    @Test
    fun testBlockCardWithoutSource() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showFees = true,
                    showSource = false,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    fees = testFees,
                    source = testSource,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardWithoutFees() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showFees = false,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    fees = testFees,
                    source = testSource,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardMinimal() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showBlock = true,
                    showTime = false,
                    showDate = false,
                    showTransactions = false,
                    showSize = false,
                    showFees = false,
                    showSource = false,
                    block = testBlock,
                    time = "",
                    date = "",
                    transactions = "",
                    size = "",
                    fees = "",
                    source = "",
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardWithEmptyValues() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showFees = true,
                    showSource = true,
                    block = "",
                    time = "",
                    date = "",
                    transactions = "",
                    size = "",
                    fees = "",
                    source = "",
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("source_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardSmallWithAllElements() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCardSmall(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                    showSize = true,
                    showFees = true,
                    showSource = true,
                    block = testBlock,
                    time = testTime,
                    date = testDate,
                    transactions = testTransactions,
                    size = testSize,
                    fees = testFees,
                    source = testSource,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("block_text", useUnmergedTree = true).assertTextEquals(testBlock)
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
    }
}
