package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.theme.AppThemeSurface
@HiltAndroidTest
class HeadlinesEditContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val mockArticle = ArticleModel(
        title = "Test Article Title for Testing Very Long Headlines That Might Wrap",
        timeAgo = "3 hours ago",
        publisher = "Test News Publisher",
        link = "https://example.com/test-article"
    )

    private val defaultPreferences = HeadlinePreferences(
        showTime = true,
        showSource = true
    )

    @Test
    fun testHeadlinesEditScreenWithDefaultPreferences() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var timeClicked = false
        var resetClicked = false
        var previewClicked = false
        var sourceClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickTime = { timeClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    headlinePreferences = defaultPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("headlines_edit_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("main_content", useUnmergedTree = true).assertExists()

        // Verify description
        composeTestRule.onNodeWithTag("edit_description", useUnmergedTree = true).assertExists()

        // Verify time setting row
        composeTestRule.onNodeWithTag("time_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_toggle_icon", useUnmergedTree = true).assertExists()

        // Verify title setting row
        composeTestRule.onNodeWithTag("title_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_toggle_icon", useUnmergedTree = true).assertExists()

        // Verify source setting row
        composeTestRule.onNodeWithTag("source_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_toggle_icon", useUnmergedTree = true).assertExists()

        // Verify dividers
        composeTestRule.onNodeWithTag("time_divider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_divider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_divider", useUnmergedTree = true).assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("preview_button", useUnmergedTree = true).assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("time_toggle_button", useUnmergedTree = true).performClick()
        assert(timeClicked)

        composeTestRule.onNodeWithTag("source_toggle_button", useUnmergedTree = true).performClick()
        assert(sourceClicked)

        composeTestRule.onNodeWithTag("preview_button", useUnmergedTree = true).performClick()
        assert(previewClicked)

        // Reset button should be disabled when both options are enabled (default state)
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun testHeadlinesEditScreenWithCustomPreferences() {
        // Arrange - Only time enabled, source disabled
        val customPreferences = HeadlinePreferences(
            showTime = true,
            showSource = false
        )

        var resetClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = { resetClicked = true },
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = customPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert reset button should be enabled when not all options are enabled
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertIsEnabled()

        // Test reset button click
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).performClick()
        assert(resetClicked)
    }

    @Test
    fun testHeadlinesEditScreenWithMinimalPreferences() {
        // Arrange - Both options disabled
        val minimalPreferences = HeadlinePreferences(
            showTime = false,
            showSource = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = {},
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = minimalPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert reset button should be enabled when not all options are enabled
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertIsEnabled()

        // Verify all elements still exist
        composeTestRule.onNodeWithTag("headlines_edit_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_toggle_button", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testTitleToggleButtonIsDisabled() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = {},
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = defaultPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert title toggle button is always disabled (title is always shown)
        composeTestRule.onNodeWithTag("title_toggle_button", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = {},
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = defaultPreferences,
                    article = mockArticle
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("headlines_edit_screen", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("main_content", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("edit_description", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("time_divider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_divider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_setting_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_toggle_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_divider", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("buttons_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("preview_button", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testResetButtonEnabledState() {
        // Test when reset should be enabled (source disabled)
        val preferencesSourceDisabled = HeadlinePreferences(
            showTime = true,
            showSource = false
        )

        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = {},
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = preferencesSourceDisabled,
                    article = mockArticle
                )
            }
        }

        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun testResetButtonDisabledState() {
        // Test when reset should be disabled (both options enabled)
        val preferencesAllEnabled = HeadlinePreferences(
            showTime = true,
            showSource = true
        )

        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = {},
                    onBack = {},
                    onClickTime = {},
                    onClickReset = {},
                    onClickPreview = {},
                    onClickShowSource = {},
                    headlinePreferences = preferencesAllEnabled,
                    article = mockArticle
                )
            }
        }

        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun testAllCallbacksTriggered() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var timeClicked = false
        var resetClicked = false
        var previewClicked = false
        var sourceClicked = false

        val customPreferences = HeadlinePreferences(
            showTime = false,
            showSource = true
        )

        composeTestRule.setContent {
            AppThemeSurface {
                HeadlinesEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickTime = { timeClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    headlinePreferences = customPreferences,
                    article = mockArticle
                )
            }
        }

        // Test all clickable elements
        composeTestRule.onNodeWithTag("time_toggle_button", useUnmergedTree = true).performClick()
        assert(timeClicked)

        composeTestRule.onNodeWithTag("source_toggle_button", useUnmergedTree = true).performClick()
        assert(sourceClicked)

        composeTestRule.onNodeWithTag("reset_button", useUnmergedTree = true).performClick()
        assert(resetClicked)

        composeTestRule.onNodeWithTag("preview_button", useUnmergedTree = true).performClick()
        assert(previewClicked)
    }
}
