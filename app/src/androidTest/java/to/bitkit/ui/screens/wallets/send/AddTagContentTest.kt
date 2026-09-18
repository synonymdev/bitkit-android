package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import to.bitkit.env.Defaults
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AddTagUiState
import kotlin.test.assertEquals

private const val TAG_INPUT = "TagInput"
private const val ADD_BUTTON = "ActivityTagsSubmit"

@ComposeUi
class AddTagContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tagInputStopsAtTheTagMaxLength() {
        setContentWithUnsanitizedState()

        composeTestRule.onNodeWithTag(TAG_INPUT)
            .performTextInput("a".repeat(Defaults.TAG_MAX_LENGTH + 5))

        assertEquals(
            "a".repeat(Defaults.TAG_MAX_LENGTH),
            composeTestRule.onNodeWithTag(TAG_INPUT).editableText(),
        )
    }

    @Test
    fun tagInputStripsLineBreaksWhileTyping() {
        setContentWithUnsanitizedState()

        composeTestRule.onNodeWithTag(TAG_INPUT).performTextInput("coffee\nshop")

        assertEquals("coffee shop", composeTestRule.onNodeWithTag(TAG_INPUT).editableText())
    }

    @Test
    fun addButtonConfirmsTheTrimmedTag() {
        var confirmed: String? = null
        composeTestRule.setContent {
            AppThemeSurface {
                AddTagContent(
                    uiState = AddTagUiState(tagInput = "coffee shop "),
                    onTagSelected = {},
                    onTagConfirmed = { confirmed = it },
                    onInputUpdated = {},
                    onBack = {},
                    tagInputTestTag = TAG_INPUT,
                    addButtonTestTag = ADD_BUTTON,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeTestRule.onNodeWithTag(ADD_BUTTON).performClick()

        assertEquals("coffee shop", confirmed)
    }

    private fun setContentWithUnsanitizedState() {
        composeTestRule.setContent {
            AppThemeSurface {
                var uiState by remember { mutableStateOf(AddTagUiState()) }
                AddTagContent(
                    uiState = uiState,
                    onTagSelected = {},
                    onTagConfirmed = {},
                    // Stores the raw text, so only the field's inputTransform can cap or sanitize it
                    onInputUpdated = { uiState = uiState.copy(tagInput = it) },
                    onBack = {},
                    tagInputTestTag = TAG_INPUT,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun SemanticsNodeInteraction.editableText(): String =
    fetchSemanticsNode().config[SemanticsProperties.EditableText].text
