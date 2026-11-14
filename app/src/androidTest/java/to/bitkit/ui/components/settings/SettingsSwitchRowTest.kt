package to.bitkit.ui.components.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@ComposeUi
class SettingsSwitchRowTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun switchSemanticsReflectCheckedState() {
        composeTestRule.setContent {
            AppThemeSurface {
                SettingsSwitchRow(
                    title = "Contact payments",
                    isChecked = true,
                    onClick = {},
                    switchTestTag = "ContactPaymentsSwitch",
                )
            }
        }

        composeTestRule.onNodeWithTag("ContactPaymentsSwitch")
            .assertIsOn()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun switchSemanticsReflectUncheckedState() {
        composeTestRule.setContent {
            AppThemeSurface {
                SettingsSwitchRow(
                    title = "Contact payments",
                    isChecked = false,
                    onClick = {},
                    switchTestTag = "ContactPaymentsSwitch",
                )
            }
        }

        composeTestRule.onNodeWithTag("ContactPaymentsSwitch").assertIsOff()
    }

    @Test
    fun switchSemanticsReflectDisabledState() {
        composeTestRule.setContent {
            AppThemeSurface {
                SettingsSwitchRow(
                    title = "Contact payments",
                    isChecked = false,
                    onClick = {},
                    enabled = false,
                    switchTestTag = "ContactPaymentsSwitch",
                )
            }
        }

        composeTestRule.onNodeWithTag("ContactPaymentsSwitch")
            .assertIsOff()
            .assertIsNotEnabled()
    }

    @Test
    fun loadingRowReportsIndeterminateProgress() {
        setPaymentsRow(isLoading = true)

        composeTestRule.onNodeWithTag("PublicPaymentsToggle")
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                )
            )
        composeTestRule.onNodeWithTag("PublicPaymentsToggleLoading", useUnmergedTree = true).assertExists()
    }

    @Test
    fun idleRowReportsNoProgress() {
        setPaymentsRow(isLoading = false)

        composeTestRule.onNodeWithTag("PublicPaymentsToggle")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        composeTestRule.onNodeWithTag("PublicPaymentsToggleLoading", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun loadingKeepsTitleHeight() {
        var isLoading by mutableStateOf(false)
        composeTestRule.setContent {
            AppThemeSurface {
                // Width of the Settings list on a 411dp phone (16dp padding each side).
                Box(modifier = Modifier.width(379.dp)) {
                    SettingsSwitchRow(
                        title = LONG_TITLE,
                        isChecked = true,
                        icon = { SettingsIcon(R.drawable.ic_coins) },
                        onClick = {},
                        isLoading = isLoading,
                        switchTestTag = "PrivateContactPaymentsToggle",
                    )
                }
            }
        }
        val idle = composeTestRule.onNodeWithText(LONG_TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()

        isLoading = true

        composeTestRule.onNodeWithTag("PrivateContactPaymentsToggleLoading", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText(LONG_TITLE, useUnmergedTree = true)
            .assertHeightIsEqualTo(idle.height)
            .assertWidthIsEqualTo(idle.width)
    }

    private fun setPaymentsRow(isLoading: Boolean) {
        composeTestRule.setContent {
            AppThemeSurface {
                SettingsSwitchRow(
                    title = "Public payments",
                    isChecked = true,
                    icon = {},
                    onClick = {},
                    isLoading = isLoading,
                    switchTestTag = "PublicPaymentsToggle",
                )
            }
        }
    }

    private companion object {
        const val LONG_TITLE = "Приватные платежи с контактами"
    }
}
