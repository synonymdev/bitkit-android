package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

class WeatherCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testGoodWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__good__title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "$ 0.52",
        currentFeeSats = 520L,
        nextBlockFee = "6 ₿/vByte",
        icon = FeeCondition.GOOD.icon,
    )

    private val testAverageWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__average__title,
        description = R.string.widgets__weather__condition__average__description,
        currentFee = "$ 1.20",
        currentFeeSats = 1200L,
        nextBlockFee = "12 ₿/vByte",
        icon = FeeCondition.AVERAGE.icon,
    )

    @Test
    fun testWeatherCardShowsCurrentFeeFiatWithWidgetTitle() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_condition_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_widget_title_text", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_column", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.currentFee)

        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_column", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_column", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardShowsNextBlockOnly() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = false,
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_column", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.nextBlockFee)

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_column", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_column", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardWithNothingSelectedRendersNoDataRows() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = null),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_column", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_column", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_column", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardSwitchesContentBasedOnSelection() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = false,
                    weatherModel = testAverageWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_value", useUnmergedTree = true)
            .assertTextEquals(testAverageWeatherModel.currentFee)
    }
}
