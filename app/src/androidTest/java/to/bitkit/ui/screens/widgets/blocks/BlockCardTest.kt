package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class BlockCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBlock = "761,405"
    private val testTime = "01:31:42 UTC"
    private val testDate = "11/2/2022"
    private val testTransactions = "2,175"
    private val testSize = "1,606Kb"
    private val testFees = "25 059 357"

    private val fullBlock = BlockModel(
        height = testBlock,
        time = testTime,
        date = testDate,
        transactionCount = testTransactions,
        size = testSize,
        fees = testFees,
    )

    @Test
    fun testBlockCardWithDefaultFields() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    preferences = BlocksPreferences(),
                    block = fullBlock,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("block_text", useUnmergedTree = true).assertTextEquals(testBlock)
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertTextEquals(testTime)
        composeTestRule.onNodeWithTag("date_text", useUnmergedTree = true).assertTextEquals(testDate)
        composeTestRule.onNodeWithTag("transactions_text", useUnmergedTree = true).assertTextEquals(testTransactions)
    }

    @Test
    fun testBlockCardWithSizeAndFees() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    preferences = BlocksPreferences(
                        showBlock = true,
                        showTime = true,
                        showDate = false,
                        showTransactions = false,
                        showSize = true,
                        showFees = true,
                    ),
                    block = fullBlock,
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("size_text", useUnmergedTree = true).assertTextEquals(testSize)
        composeTestRule.onNodeWithTag("fees_text", useUnmergedTree = true).assertTextEquals(testFees)
    }

    @Test
    fun testBlockCardWithoutFees() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    preferences = BlocksPreferences(
                        showBlock = true,
                        showTime = true,
                        showDate = true,
                        showTransactions = false,
                        showSize = false,
                        showFees = false,
                    ),
                    block = fullBlock,
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
                    preferences = BlocksPreferences(
                        showBlock = true,
                        showTime = false,
                        showDate = false,
                        showTransactions = false,
                        showSize = false,
                        showFees = false,
                    ),
                    block = BlockModel(
                        height = testBlock,
                        time = "",
                        date = "",
                        transactionCount = "",
                        size = "",
                        fees = "",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardWithEmptyValues() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCard(
                    preferences = BlocksPreferences(),
                    block = BlockModel(
                        height = "",
                        time = "",
                        date = "",
                        transactionCount = "",
                        size = "",
                        fees = "",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("block_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("time_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("date_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("transactions_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("size_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("fees_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testBlockCardSmallWithDefaultFields() {
        composeTestRule.setContent {
            AppThemeSurface {
                BlockCardSmall(
                    preferences = BlocksPreferences(),
                    block = fullBlock,
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
