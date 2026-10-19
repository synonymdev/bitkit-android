@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class PaymentRequestAmountLocaleTest {
    private val originalLocale = Locale.getDefault()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun teardown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun commaDecimalEntryPreservesExactCentsAndTrailingZeros() {
        var submitted: PaykitPaymentRequestDraft? = null
        val amountInput = AmountInputViewModel(AmountInputHandler.stub())
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    PaymentRequestAmountContent(
                        amountInputViewModel = amountInput,
                        initialDraft = PaykitPaymentRequestDraft(
                            amount = PaykitAmount(PaykitAsset.USD, 0uL),
                            note = "",
                            expiresAt = Instant.fromEpochSeconds(2_000_000_000),
                        ),
                        contact = null,
                        onBack = {},
                        onContinue = { submitted = it },
                        allowedAssets = persistentListOf(PaykitAsset.USD),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(",", useUnmergedTree = true).assertIsDisplayed()
        listOf("N1", "N5", "N0", "NDecimal").forEach {
            composeTestRule.onNodeWithTag(it).performClick()
        }
        composeTestRule.onNodeWithText("150,", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").assertIsEnabled()
        composeTestRule.onNodeWithTag("N5").performClick()
        composeTestRule.onNodeWithTag("N0").performClick()
        composeTestRule.onNodeWithText("150,50", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").performScrollTo().performClick()

        assertEquals(PaykitAmount(PaykitAsset.USD, 15_050uL), submitted?.amount)
    }

    @Test
    fun amountActionsStayAlignedAndReachableOnCompactScreens() {
        val configuration = mutableStateOf(Triple(320, 2f, PaykitAsset.USD))
        val height = mutableStateOf(468)
        val amountInput = AmountInputViewModel(AmountInputHandler.stub())
        composeTestRule.setContent {
            val (width, scale, asset) = configuration.value
            CompositionLocalProvider(LocalDensity provides Density(2f, scale), LocalInspectionMode provides true) {
                AppThemeSurface {
                    PaymentRequestAmountContent(
                        amountInputViewModel = amountInput,
                        initialDraft = PaykitPaymentRequestDraft(
                            amount = PaykitAmount(asset, 150uL),
                            note = "",
                            expiresAt = Instant.fromEpochSeconds(2_000_000_000),
                        ),
                        contact = null,
                        onBack = {},
                        onContinue = {},
                        modifier = Modifier.requiredSize(width.dp, height.value.dp)
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("PaymentRequestAmountField").assertIsDisplayed()
        composeTestRule.onRoot().performKeyInput {
            pressKey(Key.Backspace)
            pressKey(Key.Five)
            pressKey(Key.Five)
        }
        composeTestRule.onNodeWithText("1,55", useUnmergedTree = true).assertIsDisplayed()
        for ((width, screenHeight) in listOf(320 to 468, 320 to 520, 360 to 568, 375 to 640)) {
            for (scale in listOf(1f, 1.6f, 2f)) {
                var bitcoinPositions = emptyList<Float>()
                for (asset in listOf(PaykitAsset.BTC, PaykitAsset.USD)) {
                    composeTestRule.runOnIdle {
                        height.value = screenHeight
                        configuration.value = Triple(width, scale, asset)
                    }
                    composeTestRule.waitForIdle()
                    composeTestRule.onNode(
                        hasScrollAction() and hasAnyDescendant(hasTestTag("PaymentRequestAmountField"))
                    ).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
                    val amount = composeTestRule.onNodeWithTag("PaymentRequestAmountField").getUnclippedBoundsInRoot()
                    val actions = composeTestRule.onNodeWithTag("PaymentRequestAmountActions").getUnclippedBoundsInRoot()
                    val divider = composeTestRule.onNodeWithTag("PaymentRequestAmountDivider").getUnclippedBoundsInRoot()
                    val lastControl = if (asset == PaykitAsset.BTC) "PaymentRequestAmountUnit" else "PaymentRequestAssetUSD"
                    val control = composeTestRule.onNodeWithTag(lastControl).getUnclippedBoundsInRoot()
                    assertEquals(divider.left.value, amount.left.value, 0.5f)
                    assertEquals(divider.right.value, control.right.value, 0.5f)
                    assertTrue(actions.top >= amount.bottom && actions.top - amount.bottom <= 17.dp)
                    assertTrue(divider.top >= actions.bottom)
                    val positions = listOf(
                        actions.top.value,
                        divider.top.value,
                        composeTestRule.onNodeWithTag("N1").getUnclippedBoundsInRoot().top.value,
                        composeTestRule.onNodeWithTag("N0").getUnclippedBoundsInRoot().top.value,
                    )
                    if (asset == PaykitAsset.BTC) {
                        bitcoinPositions = positions
                    } else {
                        composeTestRule.onNodeWithTag("PaymentRequestAmountUnit").assertDoesNotExist()
                        bitcoinPositions.zip(positions).forEach { (bitcoin, usd) ->
                            assertEquals(bitcoin, usd, 1f, "Controls must not move when denomination changes")
                        }
                    }
                    val extraKey = if (asset == PaykitAsset.BTC) "N000" else "NDecimal"
                    for (tag in (0..9).map { "N$it" } + extraKey + "NRemove") {
                        composeTestRule.onNodeWithTag(tag)
                            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(50.dp)
                        if (tag != "NRemove") {
                            val layouts = mutableListOf<TextLayoutResult>()
                            val text = when (tag) {
                                "NDecimal" -> ","
                                "N000" -> "000"
                                else -> tag.removePrefix("N")
                            }
                            composeTestRule.onNode(
                                hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true
                            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertTrue(layouts.isNotEmpty())
                            assertFalse(layouts.single().didOverflowHeight, "$tag must fit vertically at $scale font scale")
                        }
                    }
                    composeTestRule.onNodeWithTag("PaymentRequestAmountContinue")
                        .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
                }
            }
        }
    }

}
