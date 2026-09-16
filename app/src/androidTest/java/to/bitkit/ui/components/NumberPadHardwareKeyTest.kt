package to.bitkit.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

/**
 * Regression pin for #719 — hardware/host keystrokes actually reach the custom number pad.
 *
 * `NumberPadKeyMappingTest` pins the pure key map; this pins the path around it — the pad taking
 * focus, `onPreviewKeyEvent` firing, and the mapped key reaching `onPress`. A pad that loses focus
 * or stops consuming key events passes the unit test and fails here.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@HiltAndroidTest
@ComposeUi
class NumberPadHardwareKeyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val pressed = mutableListOf<String>()

    @Before
    fun setup() {
        hiltRule.inject()
        pressed.clear()
    }

    private fun setContent(type: NumberPadType = NumberPadType.SIMPLE) {
        composeTestRule.setContent {
            AppThemeSurface {
                NumberPad(
                    onPress = { pressed += it },
                    type = type,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun whenDigitKeysPressed_shouldReachOnPressInOrder() {
        setContent()

        composeTestRule.onRoot().performKeyInput {
            pressKey(Key.Seven)
            pressKey(Key.Nine)
            pressKey(Key.Zero)
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("7", "9", "0"), pressed)
    }

    @Test
    fun whenNumPadDigitPressed_shouldReachOnPress() {
        setContent()

        composeTestRule.onRoot().performKeyInput { pressKey(Key.NumPad4) }
        composeTestRule.waitForIdle()

        assertEquals(listOf("4"), pressed)
    }

    @Test
    fun whenBackspacePressed_shouldReachOnPressAsDelete() {
        setContent()

        composeTestRule.onRoot().performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(KEY_DELETE), pressed)
    }

    @Test
    fun whenPeriodPressedOnDecimalPad_shouldReachOnPress() {
        setContent(type = NumberPadType.DECIMAL)

        composeTestRule.onRoot().performKeyInput { pressKey(Key.Period) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(KEY_DECIMAL), pressed)
    }

    @Test
    fun whenPeriodPressedOnSimplePad_shouldBeIgnored() {
        setContent(type = NumberPadType.SIMPLE)

        composeTestRule.onRoot().performKeyInput { pressKey(Key.Period) }
        composeTestRule.waitForIdle()

        assertEquals(emptyList(), pressed)
    }

    @Test
    fun whenUnmappedKeyPressed_shouldBeIgnoredSoTheEventFallsThrough() {
        setContent(type = NumberPadType.DECIMAL)

        composeTestRule.onRoot().performKeyInput {
            pressKey(Key.A)
            pressKey(Key.Spacebar)
        }
        composeTestRule.waitForIdle()

        assertEquals(emptyList(), pressed)
    }

    @Test
    fun whenPadDisabled_shouldSwallowHardwareKeys() {
        composeTestRule.setContent {
            AppThemeSurface {
                NumberPad(
                    onPress = { pressed += it },
                    enabled = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performKeyInput { pressKey(Key.Five) }
        composeTestRule.waitForIdle()

        assertEquals(emptyList(), pressed)
    }
}
