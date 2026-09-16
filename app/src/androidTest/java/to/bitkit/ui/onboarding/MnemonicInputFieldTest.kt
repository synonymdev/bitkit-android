package to.bitkit.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

/**
 * Regression pin for #896: a pasted fragment must not stay in the field it was pasted into, and
 * Backspace must edit a field that shows text even when the parent has not stored that text.
 */
@OptIn(ExperimentalTestApi::class)
@ComposeUi
class MnemonicInputFieldTest {

    private companion object {
        const val FIELD_TAG = "Word-0"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private val changes = mutableListOf<String>()
    private var backspaceInEmptyCount = 0

    @Before
    fun setup() {
        changes.clear()
        backspaceInEmptyCount = 0
    }

    private fun setContent(onValueChange: (String) -> String? = { null }) {
        composeTestRule.setContent {
            var value by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }
            AppThemeSurface {
                MnemonicInputField(
                    label = "1.",
                    value = value,
                    onValueChange = {
                        changes += it
                        onValueChange(it)?.let { newValue -> value = newValue }
                    },
                    onFocusChange = {},
                    onPositionChange = {},
                    onBackspaceInEmpty = { backspaceInEmptyCount++ },
                    focusRequester = focusRequester,
                    index = 0,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun fieldText() = composeTestRule.onNodeWithTag(FIELD_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.EditableText]
        .text

    @Test
    fun whenTextWithWhitespaceEntered_shouldNotKeepIt() {
        setContent()

        composeTestRule.onNodeWithTag(FIELD_TAG).performTextInput("abandon ability able")
        composeTestRule.waitForIdle()

        assertEquals(listOf("abandon ability able"), changes)
        assertEquals("", fieldText())
    }

    @Test
    fun whenFragmentPastedAndParentStoresFirstWord_shouldShowOnlyThatWord() {
        setContent(onValueChange = { it.trim().split(Regex("\\s+")).first() })

        composeTestRule.onNodeWithTag(FIELD_TAG).performTextInput("abandon ability able")
        composeTestRule.waitForIdle()

        assertEquals("abandon", fieldText())
    }

    @Test
    fun whenBackspacePressedInFieldWithText_shouldDeleteAndNotCallBackspaceInEmpty() {
        setContent()
        composeTestRule.onNodeWithTag(FIELD_TAG).performTextInput("abandon")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()

        assertEquals(0, backspaceInEmptyCount)
        assertEquals("abando", fieldText())
    }

    @Test
    fun whenBackspacePressedInEmptyField_shouldCallBackspaceInEmpty() {
        setContent()
        composeTestRule.onNodeWithTag(FIELD_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()

        assertEquals(1, backspaceInEmptyCount)
    }
}
