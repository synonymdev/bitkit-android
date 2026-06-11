package to.bitkit.ui.screens.widgets.suggestions

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class SuggestionsPreviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDefaultPreviewShowsFourSuggestions() {
        composeTestRule.setContent {
            AppThemeSurface {
                SuggestionsPreviewGrid(
                    onSuggestionClick = {},
                    modifier = Modifier
                )
            }
        }

        composeTestRule.onNodeWithTag("Suggestion-back_up").assertIsDisplayed()
        composeTestRule.onNodeWithTag("Suggestion-secure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("Suggestion-lightning").assertIsDisplayed()
        composeTestRule.onNodeWithTag("Suggestion-support").assertIsDisplayed()
    }
}
