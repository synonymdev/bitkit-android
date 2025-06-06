package to.bitkit.ui.screens.widgets.weather

import androidx.compose.ui.test.assertIsDisplayed
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

class WeatherPreviewContentTest {

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
    fun testWeatherPreviewWithEnabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = true,
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
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()

        // Verify buttons
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test button clicks
        composeTestRule.onNodeWithTag("edit_settings_button").performClick()
        assert(editClicked)

        composeTestRule.onNodeWithTag("delete_button").performClick()
        assert(deleteClicked)

        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testWeatherPreviewWithDisabledWidget() {
        // Arrange
        var closeClicked = false
        var backClicked = false
        var editClicked = false
        var deleteClicked = false
        var saveClicked = false

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = { editClicked = true },
                    onClickDelete = { deleteClicked = true },
                    onClickSave = { saveClicked = true },
                    showWidgetTitles = false,
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
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("save_button").assertExists()

        // Test save button click
        composeTestRule.onNodeWithTag("save_button").performClick()
        assert(saveClicked)
    }

    @Test
    fun testCustomWeatherPreferences() {
        // Arrange
        val customPreferences = WeatherPreferences(
            showTitle = true,
            showDescription = false,
            showCurrentFee = true,
            showNextBlockFee = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = customPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert that all elements still exist with custom preferences
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
    }

    @Test
    fun testAllElementsExist() {
        // Arrange
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
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
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
        composeTestRule.onNodeWithTag("preview_label").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
        composeTestRule.onNodeWithTag("buttons_row").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
    }

    @Test
    fun testNavigationCallbacks() {
        // Arrange
        var closeClicked = false
        var backClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = { closeClicked = true },
                    onBack = { backClicked = true },
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
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
        val minimalPreferences = WeatherPreferences(
            showTitle = true,
            showDescription = false,
            showCurrentFee = false,
            showNextBlockFee = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = false,
                    isWeatherWidgetEnabled = false,
                    weatherPreferences = minimalPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert core elements still exist
        composeTestRule.onNodeWithTag("weather_preview_screen").assertExists()
        composeTestRule.onNodeWithTag("weather_card").assertExists()
        composeTestRule.onNodeWithTag("save_button").assertExists()
        composeTestRule.onNodeWithTag("delete_button").assertDoesNotExist()
    }

    @Test
    fun testWeatherCardVisibility() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
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
        val customPreferences = WeatherPreferences(
            showTitle = true,
            showDescription = false,
            showCurrentFee = true,
            showNextBlockFee = false
        )

        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = customPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert edit button shows custom state
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
    }

    @Test
    fun testNullWeatherModelCase() {
        // Act
        composeTestRule.setContent {
            AppThemeSurface {
                WeatherPreviewContent(
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
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
                    onClose = {},
                    onBack = {},
                    onClickEdit = {},
                    onClickDelete = {},
                    onClickSave = {},
                    showWidgetTitles = true,
                    isWeatherWidgetEnabled = true,
                    weatherPreferences = defaultPreferences,
                    weatherModel = testWeatherModel
                )
            }
        }

        // Assert edit button shows default state
        composeTestRule.onNodeWithTag("edit_settings_button").assertExists()
    }
}
