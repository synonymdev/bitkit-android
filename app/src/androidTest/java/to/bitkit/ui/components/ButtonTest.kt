package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class ButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun largeLabelsWrapWithoutClipping() {
        val fontScale = mutableStateOf(1f)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale.value)
            ) {
                AppThemeSurface {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        PrimaryButton(text = "Cross-Network Deposits", onClick = {})
                        SecondaryButton(text = "Contact Support", onClick = {}, size = ButtonSize.Small)
                        TertiaryButton(text = "Enable camera", onClick = {})
                        RectangleButton(
                            label = "Enter Manually",
                            icon = R.drawable.ic_scan,
                            modifier = Modifier.testTag("Rectangle")
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("Rectangle").assertHeightIsEqualTo(80.dp)
        composeTestRule.runOnIdle { fontScale.value = 2f }
        listOf("Cross-Network Deposits", "Contact Support", "Enable camera", "Enter Manually").forEach { label ->
            val node = composeTestRule.onNodeWithText(label).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("Clipped height for $label", layout.didOverflowHeight)
            repeat(layout.lineCount) { line ->
                assertFalse("Ellipsized $label", layout.isLineEllipsized(line))
                assertTrue(
                    "Clipped width for $label",
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f
                )
            }
        }
    }

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
                        testTag = "SecondarySmallEnabled",
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
        composeTestRule.onNodeWithTag("SecondarySmallEnabled").assertHasClickAction()
        composeTestRule.onNodeWithTag("PrimaryLarge").assertHeightIsEqualTo(56.dp)
        composeTestRule.onNodeWithTag("SecondaryLarge").assertHeightIsEqualTo(56.dp)
    }
}
