package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.theme.AppThemeSurface

class HeadlinesPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockArticle = ArticleModel(
        title = "Test Article Title",
        timeAgo = "2 hours ago",
        publisher = "Test Publisher",
        link = "https://example.com"
    )

    private val mockHeadlinePreferences = HeadlinePreferences(
        showTime = true,
        showSource = true
    )

    @Test
    fun testHeadlinesPreviewWithImplementedHeadlines() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = true,
                    isHeadlinesImplemented = true,
                    headlinePreferences = mockHeadlinePreferences,
                    article = mockArticle
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("headlines_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify header elements
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()

        // Verify settings and preview section
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("headline_card").assertExists()

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
    fun testHeadlinesPreviewWithoutImplementedHeadlines() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = false,
                    isHeadlinesImplemented = false,
                    headlinePreferences = mockHeadlinePreferences,
                    article = mockArticle
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("headlines_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()

        // Delete button should not exist when headlines are not implemented
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test save button click
        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomHeadlinePreferences() {
        // Arrange
        val customPreferences = HeadlinePreferences(
            showTime = false,
            showSource = true
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isHeadlinesImplemented = true,
                    headlinePreferences = customPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert that all elements still exist with custom preferences
        composeTestRule.onNodeWithTag("headlines_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("headline_card").assertExists()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isHeadlinesImplemented = true,
                    headlinePreferences = mockHeadlinePreferences,
                    article = mockArticle
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("headlines_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("headline_card").assertExists()
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
                HeadlinesPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isHeadlinesImplemented = true,
                    headlinePreferences = mockHeadlinePreferences,
                    article = mockArticle
                )
            }
        }
    }

    @Test
    fun testWithMinimalHeadlinePreferences() {
        // Arrange
        val minimalPreferences = HeadlinePreferences(
            showTime = false,
            showSource = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = false,
                    isHeadlinesImplemented = false,
                    headlinePreferences = minimalPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert core elements still exist
        composeTestRule.onNodeWithTag("headlines_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("headline_card").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
    }
}
