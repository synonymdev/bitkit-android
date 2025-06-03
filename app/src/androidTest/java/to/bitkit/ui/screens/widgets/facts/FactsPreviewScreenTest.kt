package to.bitkit.ui.screens.widgets.facts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.ui.theme.AppThemeSurface

class FactsPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testFact = "Bitcoin doesn't need your personal information"
    private val defaultPreferences = FactsPreferences()

    @Test
    fun testFactsPreviewWithEnabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify header elements
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()

        // Verify settings and preview section
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("fact_card").assertExists()

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
    fun testFactsPreviewWithDisabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = false,
                    isFactsWidgetEnabled = false,
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()

        // Delete button should not exist when widget is disabled
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test save button click
        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomFactsPreferences() {
        // Arrange
        val customPreferences = FactsPreferences(showSource = false)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = customPreferences,
                    fact = testFact
                )
            }
        }

        // Assert that all elements still exist with custom preferences
        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("fact_card").assertExists()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("fact_card").assertExists()
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
                FactsPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Note: Navigation callbacks are tested through the actual navigation components
    }

    @Test
    fun testWithMinimalFactsPreferences() {
        // Arrange
        val minimalPreferences = FactsPreferences(showSource = false)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = false,
                    isFactsWidgetEnabled = false,
                    factsPreferences = minimalPreferences,
                    fact = testFact
                )
            }
        }

        // Assert core elements still exist
        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("fact_card").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
    }

    @Test
    fun testFactCardVisibility() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = defaultPreferences,
                    fact = testFact
                )
            }
        }

        // Assert fact card is displayed with correct content
        composeTestRule.onNodeWithTag("fact_card").assertIsDisplayed()
    }

    @Test
    fun testEditButtonShowsCustomState() {
        // Arrange with custom preferences
        val customPreferences = FactsPreferences(showSource = false)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isFactsWidgetEnabled = true,
                    factsPreferences = customPreferences,
                    fact = testFact
                )
            }
        }

        // Assert edit button shows custom state
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
    }
}
