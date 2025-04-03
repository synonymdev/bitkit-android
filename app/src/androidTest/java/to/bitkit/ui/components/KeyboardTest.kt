package to.bitkit.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class KeyboardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun keyboard_displaysAllButtons() {
        composeTestRule.setContent {
            Keyboard(onClick = {}, onClickBackspace = {})
        }

        composeTestRule.onNodeWithTag("KeyboardButton_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_3").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_4").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_5").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_6").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_7").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_8").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_9").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("KeyboardButton_backspace").assertIsDisplayed()
    }

    @Test
    fun keyboard_tripleZero_when_not_decimal() {
        composeTestRule.setContent {
            Keyboard(onClick = {}, isDecimal = false, onClickBackspace = {})
        }
        composeTestRule.onNodeWithTag("KeyboardButton_000").assertIsDisplayed()
    }

    @Test
    fun keyboard_decimal_when_decimal() {
        composeTestRule.setContent {
            Keyboard(onClick = {}, isDecimal = true, onClickBackspace = {})
        }
        composeTestRule.onNodeWithTag("KeyboardButton_.").assertIsDisplayed()
    }

    @Test
    fun keyboard_button_click_triggers_callback() {
        var clickedValue = ""
        composeTestRule.setContent {
            Keyboard(onClick = { clickedValue = it }, onClickBackspace = {})
        }

        composeTestRule.onNodeWithTag("KeyboardButton_5").performClick()
        assert(clickedValue == "5")

        composeTestRule.onNodeWithTag("KeyboardButton_.").performClick()
        assert(clickedValue == ".")

        composeTestRule.onNodeWithTag("KeyboardButton_0").performClick()
        assert(clickedValue == "0")

    }

    @Test
    fun keyboard_button_click_tripleZero() {
        var clickedValue = ""
        composeTestRule.setContent {
            Keyboard(onClick = { clickedValue = it }, onClickBackspace = {}, isDecimal = false)
        }

        composeTestRule.onNodeWithTag("KeyboardButton_000").performClick()
        assert(clickedValue == "000")
    }

}
