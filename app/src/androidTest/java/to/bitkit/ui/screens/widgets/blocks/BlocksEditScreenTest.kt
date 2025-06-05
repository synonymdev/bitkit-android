package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.theme.AppThemeSurface

class BlocksEditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBlock = BlockModel(
        height = "761,405",
        time = "01:31:42 UTC",
        date = "11/2/2022",
        transactionCount = "2,175",
        size = "1,606Kb",
        source = "mempool.io"
    )

    private val defaultPreferences = BlocksPreferences()

    @Test
    fun testBlocksEditScreenWithDefaultPreferences() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var blockClicked = false
        var timeClicked = false
        var dateClicked = false
        var transactionsClicked = false
        var sizeClicked = false
        var sourceClicked = false
        var resetClicked = false
        var previewClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickShowBlock = { blockClicked = true },
                    onClickShowTime = { timeClicked = true },
                    onClickShowDate = { dateClicked = true },
                    onClickShowTransactions = { transactionsClicked = true },
                    onClickShowSize = { sizeClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("blocks_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify description
        composeTestRule.onNodeWithTag("edit_description").assertExists()

        // Verify all setting rows exist
        listOf("block", "time", "date", "transactions", "size", "source").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_setting_row").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_label").assertExists()
            if (testBlock.getFieldValue(prefix).isNotEmpty()) {
                composeTestRule.onNodeWithTag("${prefix}_text").assertExists()
            }
            composeTestRule.onNodeWithTag("${prefix}_toggle_button").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_icon", useUnmergedTree = true).assertExists()
            composeTestRule.onNodeWithTag("${prefix}_divider").assertExists()
        }

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("block_toggle_button").performClick()
        assert(blockClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)

        // Reset button should be disabled with default preferences
        composeTestRule.onNodeWithTag("reset_button").assertIsNotEnabled()
    }

    @Test
    fun testBlocksEditScreenWithCustomPreferences() {
        // Arrange - Some options enabled
        val customPreferences = BlocksPreferences(
            showBlock = true,
            showTime = true,
            showSource = true
        )

        var resetClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = {},
                    onClickShowTime = {},
                    onClickShowDate = {},
                    onClickShowTransactions = {},
                    onClickShowSize = {},
                    onClickShowSource = {},
                    onClickReset = { resetClicked = true },
                    onClickPreview = {},
                    blocksPreferences = customPreferences,
                    block = testBlock
                )
            }
        }

        // Assert reset button should be enabled when preferences are customized
        composeTestRule.onNodeWithTag("reset_button").assertIsEnabled()

        // Test reset button click
        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)
    }

    @Test
    fun testPreviewButtonEnabledState() {
        // Test when preview should be enabled (at least one option enabled)
        val preferencesSomeEnabled = BlocksPreferences(showBlock = true)

        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = {},
                    onClickShowTime = {},
                    onClickShowDate = {},
                    onClickShowTransactions = {},
                    onClickShowSize = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    blocksPreferences = preferencesSomeEnabled,
                    block = testBlock
                )
            }
        }

        composeTestRule.onNodeWithTag("preview_button").assertIsEnabled()
    }

    @Test
    fun testPreviewButtonDisabledState() {
        // Test when preview should be disabled (all options disabled)
        val preferencesAllDisabled = BlocksPreferences(
            showBlock = false,
            showTime = false,
            showDate = false,
            showTransactions = false,
            showSize = false,
            showSource = false
        )

        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = {},
                    onClickShowTime = {},
                    onClickShowDate = {},
                    onClickShowTransactions = {},
                    onClickShowSize = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    blocksPreferences = preferencesAllDisabled,
                    block = testBlock
                )
            }
        }

        composeTestRule.onNodeWithTag("preview_button").assertIsNotEnabled()
    }

    @Test
    fun testAllCallbacksTriggered() {
        // Arrange
        var blockClicked = false
        var timeClicked = false
        var dateClicked = false
        var transactionsClicked = false
        var sizeClicked = false
        var sourceClicked = false
        var resetClicked = false
        var previewClicked = false

        val customPreferences = BlocksPreferences(
            showBlock = false,
            showTime = false,
            showDate = false,
            showTransactions = false,
            showSize = true,
            showSource = true
        )

        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = { blockClicked = true },
                    onClickShowTime = { timeClicked = true },
                    onClickShowDate = { dateClicked = true },
                    onClickShowTransactions = { transactionsClicked = true },
                    onClickShowSize = { sizeClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    blocksPreferences = customPreferences,
                    block = testBlock
                )
            }
        }

        // Test all clickable elements
        composeTestRule.onNodeWithTag("block_toggle_button").performClick()
        assert(blockClicked)

        composeTestRule.onNodeWithTag("time_toggle_button").performClick()
        assert(timeClicked)

        composeTestRule.onNodeWithTag("date_toggle_button").performClick()
        assert(dateClicked)

        composeTestRule.onNodeWithTag("transactions_toggle_button").performClick()
        assert(transactionsClicked)

        composeTestRule.onNodeWithTag("size_toggle_button").performClick()
        assert(sizeClicked)

        composeTestRule.onNodeWithTag("source_toggle_button").performClick()
        assert(sourceClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)

        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)
    }

    @Test
    fun testEmptyValuesDisplay() {
        // Arrange - Block with empty values
        val emptyBlock = BlockModel(
            height = "",
            time = "",
            date = "",
            transactionCount = "",
            size = "",
            source = ""
        )

        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = {},
                    onClickShowTime = {},
                    onClickShowDate = {},
                    onClickShowTransactions = {},
                    onClickShowSize = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    blocksPreferences = defaultPreferences,
                    block = emptyBlock
                )
            }
        }

        // Assert that text elements don't exist when values are empty
        listOf("block", "time", "date", "transactions", "size", "source").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_text").assertDoesNotExist()
        }
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowBlock = {},
                    onClickShowTime = {},
                    onClickShowDate = {},
                    onClickShowTransactions = {},
                    onClickShowSize = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    blocksPreferences = BlocksPreferences(
                        showBlock = false,
                        showTime = false,
                        showDate = false,
                        showTransactions = false,
                        showSize = true,
                        showSource = true
                    ),
                    block = testBlock
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("blocks_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("edit_description").assertExists()

        listOf("block", "time", "date", "transactions", "size", "source").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_setting_row").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_label").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_button").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_icon", useUnmergedTree = true).assertExists()
            composeTestRule.onNodeWithTag("${prefix}_divider").assertExists()
        }

        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()
    }
}

// Helper extension function to get field values from BlockModel
private fun BlockModel.getFieldValue(prefix: String): String {
    return when (prefix) {
        "block" -> height
        "time" -> time
        "date" -> date
        "transactions" -> transactionCount
        "size" -> size
        "source" -> source
        else -> ""
    }
}
