package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ComposeUi
class HwIntroSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pairedActionsStackAtLargeTextSizes() {
        val fontScale = mutableStateOf(1f)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale.value)
            ) {
                AppThemeSurface {
                    HwIntroSheet(modifier = Modifier.width(375.dp))
                }
            }
        }
        val cancel = composeTestRule.onNodeWithTag("HardwareWalletIntroCancel")
        val proceed = composeTestRule.onNodeWithTag("HardwareWalletIntroContinue")
        cancel.assertHeightIsEqualTo(56.dp)
        proceed.assertHeightIsEqualTo(56.dp)
        assertEquals(cancel.getUnclippedBoundsInRoot().top, proceed.getUnclippedBoundsInRoot().top)

        composeTestRule.runOnIdle { fontScale.value = 2f }

        val cancelBounds = cancel.getUnclippedBoundsInRoot()
        val continueBounds = proceed.getUnclippedBoundsInRoot()
        assertEquals(cancelBounds.left, continueBounds.left)
        assertEquals(cancelBounds.right, continueBounds.right)
        assertTrue(cancelBounds.bottom < continueBounds.top)
        assertTrue(continueBounds.bottom <= composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom)
        listOf("Cancel", "Continue").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(label)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(1, layout.lineCount, "Wrapped $label")
            assertFalse(layout.didOverflowHeight, "Clipped height for $label")
            repeat(layout.lineCount) { line ->
                assertFalse(layout.isLineEllipsized(line), "Ellipsized $label")
                val left = layout.getLineLeft(line)
                val right = layout.getLineRight(line)
                assertTrue(
                    left >= -1f && right <= layout.multiParagraph.width + 1f && right - left <= layout.size.width + 1f,
                    "Clipped width for $label"
                )
            }
        }
    }
}
