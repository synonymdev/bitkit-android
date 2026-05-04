package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

class WeatherEditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__good__title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "$ 0.52",
        currentFeeSats = 520L,
        nextBlockFee = "6 ₿/vByte",
        icon = FeeCondition.GOOD.icon,
    )

    private val rowPrefixes = listOf("current_fee_fiat", "current_fee_sats", "next_block")

    @Test
    fun testWeatherEditScreenWithDefaultPreferences() {
        var previewClicked = false
        var selectedOption: WeatherDataOption? = null

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onBack = {},
                    onSelectOption = { selectedOption = it },
                    onClickReset = {},
                    onClickPreview = { previewClicked = true },
                    weatherPreferences = WeatherPreferences(),
                    weather = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("WidgetEditScrollView").assertExists()
        composeTestRule.onNodeWithTag("display_section_header").assertExists()

        rowPrefixes.forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_setting_row").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_label").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_value").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_button").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_icon", useUnmergedTree = true).assertExists()
            composeTestRule.onNodeWithTag("${prefix}_divider").assertExists()
        }

        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetEditReset").assertExists()
        composeTestRule.onNodeWithTag("WidgetEditPreview").assertExists()

        composeTestRule.onNodeWithTag("current_fee_sats_toggle_button").performClick()
        assert(selectedOption == WeatherDataOption.CURRENT_FEE_SATS)

        composeTestRule.onNodeWithTag("WidgetEditPreview").performClick()
        assert(previewClicked)

        composeTestRule.onNodeWithTag("WidgetEditReset").assertIsNotEnabled()
    }

    @Test
    fun testResetButtonEnabledWhenPreferencesDifferFromDefault() {
        var resetClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onBack = {},
                    onSelectOption = {},
                    onClickReset = { resetClicked = true },
                    onClickPreview = {},
                    weatherPreferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
                    weather = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetEditReset").assertIsEnabled()
        composeTestRule.onNodeWithTag("WidgetEditReset").performClick()
        assert(resetClicked)
    }

    @Test
    fun testPreviewButtonEnabledWhenAnyOptionSelected() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onBack = {},
                    onSelectOption = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                    weather = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetEditPreview").assertIsEnabled()
    }

    @Test
    fun testPreviewButtonDisabledWhenNothingSelected() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onBack = {},
                    onSelectOption = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = WeatherPreferences(selectedOption = null),
                    weather = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetEditPreview").assertIsNotEnabled()
    }

    @Test
    fun testEachRowTriggersSelectionCallback() {
        val captured = mutableListOf<WeatherDataOption>()

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onBack = {},
                    onSelectOption = { captured.add(it) },
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = WeatherPreferences(selectedOption = null),
                    weather = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("current_fee_fiat_toggle_button").performClick()
        composeTestRule.onNodeWithTag("current_fee_sats_toggle_button").performClick()
        composeTestRule.onNodeWithTag("next_block_toggle_button").performClick()

        assert(captured == listOf(
            WeatherDataOption.CURRENT_FEE_FIAT,
            WeatherDataOption.CURRENT_FEE_SATS,
            WeatherDataOption.NEXT_BLOCK_INCLUSION,
        ))
    }
}
