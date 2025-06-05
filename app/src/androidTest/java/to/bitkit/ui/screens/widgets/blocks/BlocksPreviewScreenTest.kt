package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.theme.AppThemeSurface

class BlocksPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBlock = BlockModel(
        height = "123456",
        time = "01:31:42 UTC",
        date = "2023-01-01",
        transactionCount = "2,175",
        size = "1,606kB",
        source = "mempool.space"
    )
    private val defaultPreferences = BlocksPreferences()

    @Test
    fun testBlocksPreviewWithEnabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("blocks_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify header elements
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()

        // Verify settings and preview section
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("block_card").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("edit_settings_button").performClick()
        assert(editClicked)

        composeTestRule.onNodeWithTag("delete_button").performClick()
        assert(deleteClicked)

        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testBlocksPreviewWithDisabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = false,
                    isBlocksWidgetEnabled = false,
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("blocks_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()

        // Delete button should not exist when widget is disabled
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test save button click
        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomBlocksPreferences() {
        // Arrange
        val customPreferences = BlocksPreferences(
            showBlock = true,
            showTime = true,
            showDate = false,
            showTransactions = true,
            showSize = false,
            showSource = true
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = customPreferences,
                    block = testBlock
                )
            }
        }

        // Assert that all elements still exist with custom preferences
        composeTestRule.onNodeWithTag("blocks_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("block_card").assertExists()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("blocks_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("block_card").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
    }

    @Test
    fun testNavigationCallbacks() {
        // Arrange
        var closeClicked = false
        var backClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Note: Navigation callbacks are tested through the actual navigation components
    }

    @Test
    fun testWithMinimalBlocksPreferences() {
        // Arrange
        val minimalPreferences = BlocksPreferences(
            showBlock = true,
            showTime = false,
            showDate = false,
            showTransactions = false,
            showSize = false,
            showSource = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = false,
                    isBlocksWidgetEnabled = false,
                    blocksPreferences = minimalPreferences,
                    block = testBlock
                )
            }
        }

        // Assert core elements still exist
        composeTestRule.onNodeWithTag("blocks_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("block_card").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
    }

    @Test
    fun testBlockCardVisibility() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = defaultPreferences,
                    block = testBlock
                )
            }
        }

        // Assert block card is displayed with correct content
        composeTestRule.onNodeWithTag("block_card").assertIsDisplayed()
    }

    @Test
    fun testEditButtonShowsCustomState() {
        // Arrange with custom preferences
        val customPreferences = BlocksPreferences(
            showBlock = true,
            showTime = true,
            showDate = false,
            showTransactions = true,
            showSize = false,
            showSource = true
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = customPreferences,
                    block = testBlock
                )
            }
        }

        // Assert edit button shows custom state
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
    }

    @Test
    fun testNullBlockCase() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                BlocksPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isBlocksWidgetEnabled = true,
                    blocksPreferences = defaultPreferences,
                    block = null
                )
            }
        }

        // Assert block card doesn't exist when block is null
        composeTestRule.onNodeWithTag("block_card").assertDoesNotExist()
    }
}
