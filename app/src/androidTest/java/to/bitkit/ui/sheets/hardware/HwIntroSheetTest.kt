package to.bitkit.ui.sheets.hardware

import android.content.res.Configuration
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
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
        assertReadableLabels(listOf("Cancel", "Continue"))
    }

    @Test
    fun localizedActionsStackOnlyWhenLabelsDoNotFit() {
        val size = mutableStateOf(360 to 1f)
        composeTestRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.GERMANY) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = size.value.second)
            ) {
                AppThemeSurface {
                    HwIntroSheet(modifier = Modifier.requiredWidth(size.value.first.dp))
                }
            }
        }
        for ((width, fontScale, stacked) in listOf(
            Triple(360, 1f, false),
            Triple(360, 1.15f, true),
            Triple(360, 1.25f, true),
            Triple(411, 1.15f, false),
            Triple(411, 1.3f, false),
            Triple(360, 2f, true),
        )) {
            composeTestRule.runOnIdle { size.value = width to fontScale }
            val cancel = composeTestRule.onNodeWithTag("HardwareWalletIntroCancel").getUnclippedBoundsInRoot()
            val proceed = composeTestRule.onNodeWithTag("HardwareWalletIntroContinue").getUnclippedBoundsInRoot()
            assertEquals(stacked, cancel.bottom < proceed.top, "$width dp at $fontScale")
            if (fontScale >= 2f) {
                composeTestRule.onNodeWithTag("HardwareWalletIntroContinue").performScrollTo().assertIsDisplayed()
            } else {
                assertTrue(proceed.bottom <= composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom)
            }
            assertReadableLabels(listOf("Abbrechen", "Weiter"))
        }
    }

    @Test
    fun largeLocalizedSheetActionsRemainReachable() {
        var continued = false
        var cancelled = false
        composeTestRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.GERMANY) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)
            ) {
                AppThemeSurface {
                    HwIntroSheet(
                        onContinue = { continued = true },
                        onCancel = { cancelled = true },
                        modifier = Modifier.width(360.dp).height(680.dp)
                    )
                }
            }
        }
        val content = composeTestRule.onNode(hasScrollAction())
        content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        val proceed = composeTestRule.onNodeWithTag("HardwareWalletIntroContinue")
        proceed.assertIsDisplayed()
        assertEquals(16.dp, content.getUnclippedBoundsInRoot().bottom - proceed.getUnclippedBoundsInRoot().bottom)
        proceed.performClick()
        composeTestRule.onNodeWithTag("HardwareWalletIntroCancel").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.runOnIdle {
            assertTrue(continued)
            assertTrue(cancelled)
        }
        assertReadableLabels(listOf("Abbrechen", "Weiter"))
    }

    private fun assertReadableLabels(labels: List<String>) {
        labels.forEach { label ->
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
