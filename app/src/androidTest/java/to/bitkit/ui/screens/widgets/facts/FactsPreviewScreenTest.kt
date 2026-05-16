package to.bitkit.ui.screens.widgets.facts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUiTest
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUiTest
class FactsPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testFact = "Bitcoin doesn't need your personal information"

    @Test
    fun testFactsPreviewWithEnabledWidget() {
        var deleteClicked = false
        var saveClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onBack = {},
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    isFactsWidgetEnabled = true,
                    fact = testFact,
                )
            }
        }

        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("facts_preview_carousel").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertExists()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        composeTestRule.onNodeWithTag("WidgetDelete").performClick()
        assert(deleteClicked)

        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }

    @Test
    fun testFactsPreviewWithDisabledWidget() {
        var saveClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                FactsPreviewContent(
                    onBack = {},
                    onClickDelete = {},
                    onClickSave = { saveClicked = true },
                    isFactsWidgetEnabled = false,
                    fact = testFact,
                )
            }
        }

        composeTestRule.onNodeWithTag("facts_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertDoesNotExist()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }
}
