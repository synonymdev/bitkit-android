package to.bitkit.ui.components.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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
}
