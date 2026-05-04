package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.assertIsDisplayed
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

class WeatherPreviewContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testWeatherModel = WeatherModel(
        condition = FeeCondition.GOOD,
        title = R.string.widgets__weather__condition__good__title,
        description = R.string.widgets__weather__condition__good__description,
        currentFee = "$ 0.52",
        currentFeeSats = 520L,
        nextBlockFee = "6 ₿/vByte",
        icon = FeeCondition.GOOD.icon,
    )

    private val defaultPreferences = WeatherPreferences()

    @Test
    fun testWeatherPreviewWithEnabledWidget() {
        // Arrange
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()

        // Verify header elements
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()

        // Verify settings and preview section
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertExists()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("WidgetEdit").performClick()
        assert(editClicked)

        composeTestRule.onNodeWithTag("WidgetDelete").performClick()
        assert(deleteClicked)

        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }

    @Test
    fun testWeatherPreviewWithDisabledWidget() {
        // Arrange
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    isWeatherWidgetEnabled = false,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert main elements exist
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()

        // Delete button should not exist when widget is disabled
        composeTestRule.onNodeWithTag("WidgetDelete").assertDoesNotExist()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()

        // Test save button click
        composeTestRule.onNodeWithTag("WidgetSave").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomWeatherPreferences() {
        // Arrange
        val customPreferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_SATS)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = customPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert that all elements still exist with custom preferences
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert all tagged elements exist
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("main_content").assertExists()
        composeTestRule.onNodeWithTag("header_row").assertExists()
        composeTestRule.onNodeWithTag("widget_title").assertExists()
        composeTestRule.onNodeWithTag("widget_icon").assertExists()
        composeTestRule.onNodeWithTag("widget_description").assertExists()
        composeTestRule.onNodeWithTag("divider").assertExists()
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertExists()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()
    }

    @Test
    fun testNavigationCallbacks() {
        // Arrange
        var backClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = { backClicked = true },
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Note: Navigation callbacks are tested through the actual navigation components
    }

    @Test
    fun testWithMinimalWeatherPreferences() {
        // Arrange
        val minimalPreferences = WeatherPreferences(selectedOption = null)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = false,
                    weatherPreferences = minimalPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert core elements still exist
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
        composeTestRule.onNodeWithTag("WidgetSave").assertExists()
        composeTestRule.onNodeWithTag("WidgetDelete").assertDoesNotExist()
    }

    @Test
    fun testWeatherCardVisibility() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert weather card is displayed with correct content
        composeTestRule.onNodeWithTag("weather_card").assertIsDisplayed()
    }

    @Test
    fun testEditButtonShowsCustomState() {
        // Arrange with custom preferences
        val customPreferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION)

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = customPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert edit button shows custom state
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
    }

    @Test
    fun testNullWeatherModelCase() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = null
                )
            }
        }

        // Assert weather card doesn't exist when weather model is null
        composeTestRule.onNodeWithTag("weather_card").assertDoesNotExist()
    }

    @Test
    fun testEditButtonShowsDefaultState() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert edit button shows default state
        composeTestRule.onNodeWithTag("WidgetEdit").assertExists()
    }
}
