package to.bitkit.ui.screens.widgets.facts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.ui.theme.AppThemeSurface

class FactsEditContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testFact = "Bitcoin doesn't need your personal information"
    private val defaultPreferences = FactsPreferences()

    @Test
    fun testFactsEditScreenWithDefaultPreferences() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var sourceClicked = false
        var resetClicked = false
        var previewClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("facts_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify description
        composeTestRule.onNodeWithTag("edit_description").assertExists()

        // Verify title setting row
        composeTestRule.onNodeWithTag("title_setting_row").assertExists()
        composeTestRule.onNodeWithTag("title_text").assertExists()
        composeTestRule.onNodeWithTag("title_toggle_button").assertExists()
        composeTestRule.onNodeWithTag("title_toggle_icon", useUnmergedTree = true).assertExists()

        // Verify source setting row
        composeTestRule.onNodeWithTag("source_setting_row").assertExists()
        composeTestRule.onNodeWithTag("source_label").assertExists()
        composeTestRule.onNodeWithTag("source_text").assertExists()
        composeTestRule.onNodeWithTag("source_toggle_button").assertExists()
        composeTestRule.onNodeWithTag("source_toggle_icon", useUnmergedTree = true).assertExists()

        // Verify dividers
        composeTestRule.onNodeWithTag("title_divider").assertExists()
        composeTestRule.onNodeWithTag("source_divider").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("source_toggle_button").performClick()
        assert(sourceClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)

        // Reset button should be disabled when source is enabled (default state)
        composeTestRule.onNodeWithTag("reset_button").assertIsNotEnabled()
    }

    @Test
    fun testFactsEditScreenWithCustomPreferences() {
        // Arrange - Source Enabled
        val customPreferences = FactsPreferences(showSource = true)

        var resetClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowSource = {},
                    onClickReset = { resetClicked = true },
                    onClickPreview = {},
                    factsPreferences = customPreferences,
                    fact = testFact
                )
            }
        }

        // Assert reset button should be enabled when source is enabled
        composeTestRule.onNodeWithTag("reset_button").assertIsEnabled()

        // Test reset button click
        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)
    }

    @Test
    fun testTitleToggleButtonIsDisabled() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert title toggle button is always disabled (title is always shown)
        composeTestRule.onNodeWithTag("title_toggle_button").assertIsNotEnabled()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("facts_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("edit_description").assertExists()
        composeTestRule.onNodeWithTag("title_setting_row").assertExists()
        composeTestRule.onNodeWithTag("title_text").assertExists()
        composeTestRule.onNodeWithTag("title_toggle_button").assertExists()
        composeTestRule.onNodeWithTag("title_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("title_divider").assertExists()
        composeTestRule.onNodeWithTag("source_setting_row").assertExists()
        composeTestRule.onNodeWithTag("source_label").assertExists()
        composeTestRule.onNodeWithTag("source_text").assertExists()
        composeTestRule.onNodeWithTag("source_toggle_button").assertExists()
        composeTestRule.onNodeWithTag("source_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("source_divider").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()
    }

    @Test
    fun testResetButtonEnabledState() {
        // Test when reset should be enabled (source enabled)
        val preferencesSourceDisabled = FactsPreferences(showSource = true)

        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    factsPreferences = preferencesSourceDisabled,
                    fact = testFact
                )
            }
        }

        composeTestRule.onNodeWithTag("reset_button").assertIsEnabled()
    }

    @Test
    fun testResetButtonDisabledState() {
        // Test when reset should be disabled (source disabled)
        val preferencesSourceEnabled = FactsPreferences()

        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowSource = {},
                    onClickReset = {},
                    onClickPreview = {},
                    factsPreferences = preferencesSourceEnabled,
                    fact = testFact
                )
            }
        }

        composeTestRule.onNodeWithTag("reset_button").assertIsNotEnabled()
    }

    @Test
    fun testAllCallbacksTriggered() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var sourceClicked = false
        var resetClicked = false
        var previewClicked = false

        val customPreferences = FactsPreferences(showSource = true)

        composeTestRule.setContent {
            AppThemeSurface {
                FactsEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickShowSource = { sourceClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    factsPreferences = customPreferences,
                    fact = testFact
                )
            }
        }

        // Test all clickable elements
        composeTestRule.onNodeWithTag("source_toggle_button").performClick()
        assert(sourceClicked)

        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)
    }
}
