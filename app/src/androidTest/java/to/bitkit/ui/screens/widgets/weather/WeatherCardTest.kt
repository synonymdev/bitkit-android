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
import to.bitkit.test.annotations.ComposeUiAndroidTest
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUiAndroidTest
class WeatherCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testGoodWeatherModel = WeatherModel(
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

    private val testAverageWeatherModel = WeatherModel(
        condition = FeeCondition.AVERAGE,
        title = R.string.widgets__weather__condition__average__title,
        shortTitle = R.string.widgets__weather__condition__average__short_title,
        description = R.string.widgets__weather__condition__average__description,
        currentFee = "$ 1.27",
        currentFeeSats = 1270L,
        currentFeeSatsFormatted = "1,270 ₿",
        nextBlockFee = "12 ₿/vByte",
        icon = FeeCondition.AVERAGE.icon,
    )

    @Test
    fun testWeatherCardShowsTitleDescriptionAndCurrentFeeFiat() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card").assertExists()
        composeTestRule.onNodeWithTag("weather_card_title", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_icon", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_block", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.currentFee)

        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_block", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_block", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardShowsNextBlockOnly() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_next_block_block", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.nextBlockFee)

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_block", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_block", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardWithNothingSelectedHidesFeeBlock() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = null),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_title", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description", useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_block", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_current_fee_sats_block", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_block", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testWeatherCardSwitchesContentBasedOnConditionAndSelection() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    weatherModel = testAverageWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_value", useUnmergedTree = true)
            .assertTextEquals(testAverageWeatherModel.currentFee)
    }

    @Test
    fun testWeatherCardSmallShowsTitleAndFee() {
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCardSmall(
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                )
            }
        }

        composeTestRule.onNodeWithTag("weather_card_small").assertExists()
        composeTestRule.onNodeWithTag("weather_card_small_title", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_fiat_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.currentFee)
    }
}
