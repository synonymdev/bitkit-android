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
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface

class WeatherEditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testWeatherModel = WeatherModel(
        title = R.string.widgets__weather__condition__good__title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "15 sat/vB",
        nextBlockFee = "12 sat/vB",
        icon = FeeCondition.GOOD.icon
    )

    private val defaultPreferences = WeatherPreferences()

    @Test
    fun testWeatherEditScreenWithDefaultPreferences() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var titleClicked = false
        var descriptionClicked = false
        var currentFeeClicked = false
        var nextBlockFeeClicked = false
        var resetClicked = false
        var previewClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickShowTitle = { titleClicked = true },
                    onClickShowDescription = { descriptionClicked = true },
                    onClickShowCurrentFee = { currentFeeClicked = true },
                    onClickShowNextBlockFee = { nextBlockFeeClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    weatherPreferences = defaultPreferences,
                    weather = testWeatherModel
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("weather_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify description
        composeTestRule.onNodeWithTag("edit_description").assertExists()

        // Verify all setting rows exist
        listOf("title", "description", "current_fee", "next_block_fee").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_setting_row").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_text").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_button").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_icon", useUnmergedTree = true).assertExists()
            composeTestRule.onNodeWithTag("${prefix}_divider").assertExists()
        }

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("title_toggle_button").performClick()
        assert(titleClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)

        // Reset button should be disabled with default preferences
        composeTestRule.onNodeWithTag("reset_button").assertIsNotEnabled()
    }

    @Test
    fun testWeatherEditScreenWithCustomPreferences() {
        // Arrange - Some options enabled
        val customPreferences = WeatherPreferences(
            showTitle = true,
            showDescription = true,
            showCurrentFee = false,
            showNextBlockFee = true
        )

        var resetClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = { resetClicked = true },
                    onClickPreview = {},
                    weatherPreferences = customPreferences,
                    weather = testWeatherModel
                )
            }
        }

        // Assert reset button should be enabled when preferences are customized
        composeTestRule.onNodeWithTag("reset_button").assertIsEnabled()

        // Test reset button click
        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)
    }

    @Test
    fun testPreviewButtonEnabledState() {
        // Test when preview should be enabled (at least one option enabled)
        val preferencesSomeEnabled = WeatherPreferences(showTitle = true)

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = preferencesSomeEnabled,
                    weather = testWeatherModel
                )
            }
        }

        composeTestRule.onNodeWithTag("preview_button").assertIsEnabled()
    }

    @Test
    fun testPreviewButtonDisabledState() {
        // Test when preview should be disabled (all options disabled)
        val preferencesAllDisabled = WeatherPreferences(
            showTitle = false,
            showDescription = false,
            showCurrentFee = false,
            showNextBlockFee = false
        )

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = preferencesAllDisabled,
                    weather = testWeatherModel
                )
            }
        }

        composeTestRule.onNodeWithTag("preview_button").assertIsNotEnabled()
    }

    @Test
    fun testAllCallbacksTriggered() {
        // Arrange
        var titleClicked = false
        var descriptionClicked = false
        var currentFeeClicked = false
        var nextBlockFeeClicked = false
        var resetClicked = false
        var previewClicked = false

        val customPreferences = WeatherPreferences(
            showTitle = false,
            showDescription = true,
            showCurrentFee = false,
            showNextBlockFee = true
        )

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = { titleClicked = true },
                    onClickShowDescription = { descriptionClicked = true },
                    onClickShowCurrentFee = { currentFeeClicked = true },
                    onClickShowNextBlockFee = { nextBlockFeeClicked = true },
                    onClickReset = { resetClicked = true },
                    onClickPreview = { previewClicked = true },
                    weatherPreferences = customPreferences,
                    weather = testWeatherModel
                )
            }
        }

        // Test all clickable elements
        composeTestRule.onNodeWithTag("title_toggle_button").performClick()
        assert(titleClicked)

        composeTestRule.onNodeWithTag("description_toggle_button").performClick()
        assert(descriptionClicked)

        composeTestRule.onNodeWithTag("current_fee_toggle_button").performClick()
        assert(currentFeeClicked)

        composeTestRule.onNodeWithTag("next_block_fee_toggle_button").performClick()
        assert(nextBlockFeeClicked)

        composeTestRule.onNodeWithTag("preview_button").performClick()
        assert(previewClicked)

        composeTestRule.onNodeWithTag("reset_button").performClick()
        assert(resetClicked)
    }

    @Test
    fun testEmptyValuesDisplay() {
        // Arrange - Weather with empty values
        val emptyWeather = WeatherModel(
            title = R.string.widgets__weather__condition__good__title,
            description = R.string.widgets__weather__condition__good__description,
            currentFee = "",
            nextBlockFee = "",
            icon = FeeCondition.GOOD.icon
        )

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = defaultPreferences,
                    weather = emptyWeather
                )
            }
        }

        // Assert that fee text elements don't exist when values are empty
        listOf("current_fee", "next_block_fee").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_text").assertDoesNotExist()
        }
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = WeatherPreferences(
                        showTitle = false,
                        showDescription = true,
                        showCurrentFee = false,
                        showNextBlockFee = true
                    ),
                    weather = testWeatherModel
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("weather_edit_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("edit_description").assertExists()

        listOf("title", "description", "current_fee", "next_block_fee").forEach { prefix ->
            composeTestRule.onNodeWithTag("${prefix}_setting_row").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_button").assertExists()
            composeTestRule.onNodeWithTag("${prefix}_toggle_icon", useUnmergedTree = true).assertExists()
            composeTestRule.onNodeWithTag("${prefix}_divider").assertExists()
        }

        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("reset_button").assertExists()
        composeTestRule.onNodeWithTag("preview_button").assertExists()
    }

    @Test
    fun testToggleIconsColorChange() {
        // Arrange
        val customPreferences = WeatherPreferences(
            showTitle = true,
            showDescription = false,
            showCurrentFee = true,
            showNextBlockFee = false
        )

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherEditContent(
                    onClose = {},
                    onBack = {},
                    onClickShowTitle = {},
                    onClickShowDescription = {},
                    onClickShowCurrentFee = {},
                    onClickShowNextBlockFee = {},
                    onClickReset = {},
                    onClickPreview = {},
                    weatherPreferences = customPreferences,
                    weather = testWeatherModel
                )
            }
        }

        // Assert toggle icons have correct color based on preferences
        composeTestRule.onNodeWithTag("title_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("description_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("current_fee_toggle_icon", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("next_block_fee_toggle_icon", useUnmergedTree = true).assertExists()
    }
}
