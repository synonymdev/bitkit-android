package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

class WeatherCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testGoodWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__good__title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "15 sat/vB",
        nextBlockFee = "12 sat/vB",
        icon = FeeCondition.GOOD.icon
    )

    private val testAverageWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__average__title,
        description = R.string.widgets__weather__condition__average__description,
        currentFee = "45 sat/vB",
        nextBlockFee = "50 sat/vB",
        icon = FeeCondition.AVERAGE.icon
    )

    private val testAllEnabledPreferences = WeatherPreferences(
        showTitle = true,
        showDescription = true,
        showCurrentFee = true,
        showNextBlockFee = true
    )

    @Test
    fun testWeatherCardWithAllElementsAndWidgetTitle() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testGoodWeatherModel,
                    preferences = testAllEnabledPreferences
                )
            }
        }

        // Assert all elements exist
        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_condition_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_row", useUnmergedTree = true).assertExists()

        // Verify text content
        composeTestRule.onNodeWithTag("weather_card_current_fee_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.currentFee)
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_value", useUnmergedTree = true)
            .assertTextEquals(testGoodWeatherModel.nextBlockFee)
    }

    @Test
    fun testWeatherCardWithoutWidgetTitle() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = false,
                    weatherModel = testGoodWeatherModel,
                    preferences = testAllEnabledPreferences
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description_text", useUnmergedTree = true).assertExists()

        // Assert widget title elements do not exist
        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_condition_icon", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_widget_title_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testWeatherCardWithoutNextBlockFee() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testGoodWeatherModel,
                    preferences = testAllEnabledPreferences.copy(showNextBlockFee = false)
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_row", useUnmergedTree = true).assertExists()

        // Assert next block fee elements do not exist
        composeTestRule.onNodeWithTag("weather_card_next_block_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_label", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_value", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testWeatherCardMinimalConfiguration() {
        // Arrange & Act - Only title shown
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = false,
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(
                        showTitle = true,
                        showDescription = false,
                        showCurrentFee = false,
                        showNextBlockFee = false
                    )
                )
            }
        }

        // Assert only title row exists
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()

        // Assert other elements do not exist
        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_description_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_current_fee_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testWeatherCardWithDifferentConditions() {
        // Arrange & Act - Test with average condition
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testAverageWeatherModel,
                    preferences = testAllEnabledPreferences
                )
            }
        }

        // Assert elements exist with average condition data
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_value", useUnmergedTree = true)
            .assertTextEquals(testAverageWeatherModel.currentFee)
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_value", useUnmergedTree = true)
            .assertTextEquals(testAverageWeatherModel.nextBlockFee)
    }

    @Test
    fun testAllElementsExistInFullConfiguration() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = true,
                    weatherModel = testGoodWeatherModel,
                    preferences = testAllEnabledPreferences
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("weather_card_widget_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_condition_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_widget_title_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description_text", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_current_fee_value", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_label", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_next_block_fee_value", useUnmergedTree = true).assertExists()
    }

    @Test
    fun testWeatherCardWithTitleAndDescriptionOnly() {
        // Arrange & Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherCard(
                    showWidgetTitle = false,
                    weatherModel = testGoodWeatherModel,
                    preferences = WeatherPreferences(
                        showTitle = true,
                        showDescription = true,
                        showCurrentFee = false,
                        showNextBlockFee = false
                    )
                )
            }
        }

        // Assert title and description exist
        composeTestRule.onNodeWithTag("weather_card_title_row", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("weather_card_description_text", useUnmergedTree = true).assertExists()

        // Assert fee elements do not exist
        composeTestRule.onNodeWithTag("weather_card_current_fee_row", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("weather_card_next_block_row", useUnmergedTree = true).assertDoesNotExist()
    }
}
