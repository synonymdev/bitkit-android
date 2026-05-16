package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.test.annotations.ComposeUiTest
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUiTest
class WeatherPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testWeatherModel = WeatherModel(
        condition = FeeCondition.GOOD,
        title = R.string.widgets__weather__condition__good__title,
        shortTitle = R.string.widgets__weather__condition__good__short_title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "$ 0.52",
        currentFeeSats = 520L,
        currentFeeSatsFormatted = "520 ₿",
        nextBlockFee = "6 ₿/vByte",
        icon = FeeCondition.GOOD.icon,
    )

    private val defaultPreferences = WeatherPreferences()

    @Test
    fun testWeatherPreviewWithEnabledWidget() {
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
        composeTestRule.onNodeWithTag("weather_preview_carousel").assertExists()

        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertExists()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        composeTestRule.onNodeWithTag("WidgetEdit").performClick()
        assert(editClicked)

        composeTestRule.onNodeWithTag("WidgetDelete").performClick()
        assert(deleteClicked)

        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }

    @Test
    fun testWeatherPreviewWithDisabledWidget() {
        var saveClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = { saveClicked = true },
                    isWeatherWidgetEnabled = false,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()

        composeTestRule.onNodeWithTag("WidgetDelete").assertDoesNotExist()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomWeatherPreferences() {
        val customPreferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_SATS)

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = customPreferences,
                    weatherModel = testWeatherModel,
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
        composeTestRule.onNodeWithTag("weather_preview_carousel").assertExists()
    }

    @Test
    fun testCarouselNotShownWhenWeatherModelIsNull() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = null,
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("weather_preview_carousel").assertDoesNotExist()
    }
}
