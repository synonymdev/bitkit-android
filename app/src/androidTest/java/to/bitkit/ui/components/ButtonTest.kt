package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class ButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun buttonHeightsRemainStableAcrossStates() {
        composeTestRule.setContent {
            AppThemeSurface {
                Column {
                    PrimaryButton(
                        text = "Primary",
                        onClick = {},
                        size = ButtonSize.Small,
                        fullWidth = false,
                        modifier = Modifier.testTag("PrimarySmallEnabled")
                    )
                    PrimaryButton(
                        text = "Primary",
                        onClick = {},
                        size = ButtonSize.Small,
                        enabled = false,
                        fullWidth = false,
                        modifier = Modifier.testTag("PrimarySmallDisabled")
                    )
                    PrimaryButton(
                        text = "Primary",
                        onClick = {},
                        size = ButtonSize.Small,
                        isLoading = true,
                        fullWidth = false,
                        modifier = Modifier.testTag("PrimarySmallLoading")
                    )
                    SecondaryButton(
                        text = "Secondary",
                        onClick = {},
                        size = ButtonSize.Small,
                        fullWidth = false,
                        modifier = Modifier.testTag("SecondarySmallEnabled")
                    )
                    SecondaryButton(
                        text = "Secondary",
                        onClick = {},
                        size = ButtonSize.Small,
                        enabled = false,
                        fullWidth = false,
                        modifier = Modifier.testTag("SecondarySmallDisabled")
                    )
                    SecondaryButton(
                        text = "Secondary",
                        onClick = {},
                        size = ButtonSize.Small,
                        isLoading = true,
                        fullWidth = false,
                        modifier = Modifier.testTag("SecondarySmallLoading")
                    )
                    PrimaryButton(
                        text = "Primary",
                        onClick = {},
                        fullWidth = false,
                        modifier = Modifier.testTag("PrimaryLarge")
                    )
                    SecondaryButton(
                        text = "Secondary",
                        onClick = {},
                        fullWidth = false,
                        modifier = Modifier.testTag("SecondaryLarge")
                    )
                }
            }
        }

        listOf(
            "PrimarySmallEnabled",
            "PrimarySmallDisabled",
            "PrimarySmallLoading",
            "SecondarySmallEnabled",
            "SecondarySmallDisabled",
            "SecondarySmallLoading",
        ).forEach {
            composeTestRule.onNodeWithTag(it).assertHeightIsEqualTo(40.dp)
        }
        composeTestRule.onNodeWithTag("PrimaryLarge").assertHeightIsEqualTo(56.dp)
        composeTestRule.onNodeWithTag("SecondaryLarge").assertHeightIsEqualTo(56.dp)
    }
}
