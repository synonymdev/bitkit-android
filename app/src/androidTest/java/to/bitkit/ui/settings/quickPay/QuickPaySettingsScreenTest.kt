package to.bitkit.ui.settings.quickPay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUiTest
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@ComposeUiTest
class QuickPaySettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun whenScreenLoaded_shouldShowAllComponents() {
        composeTestRule.setContent {
            AppThemeSurface {
                QuickPaySettingsScreenContent(
                    isQuickPayEnabled = true,
                    quickPayAmount = 5,
                )
            }
        }

        composeTestRule.onNodeWithTag("QuickpayToggle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quickpay_amount_slider").assertIsDisplayed()
    }

    @Test
    fun whenToggleSwitchClicked_shouldTriggerCallback() {
        var toggleCalled = false
        var toggleValue = false

        composeTestRule.setContent {
            AppThemeSurface {
                QuickPaySettingsScreenContent(
                    isQuickPayEnabled = false,
                    quickPayAmount = 5,
                    onToggleQuickPay = { enabled ->
                        toggleCalled = true
                        toggleValue = enabled
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("QuickpayToggle")
            .performClick()

        assert(toggleCalled)
        assert(toggleValue) // Should be true since we're toggling from false
    }
}
