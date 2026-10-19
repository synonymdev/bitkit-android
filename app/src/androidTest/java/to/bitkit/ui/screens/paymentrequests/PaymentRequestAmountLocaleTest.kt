@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").performClick()

        assertEquals(PaykitAmount(PaykitAsset.USD, 15_050uL), submitted?.amount)
    }

    @Test
    fun amountActionsStayAlignedAndReachableOnCompactScreens() {
        val configuration = mutableStateOf(Triple(320, 1f, PaykitAsset.USD))
        val height = mutableStateOf(520)
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
        for ((width, screenHeight) in listOf(320 to 520, 375 to 520, 320 to 640, 375 to 640)) {
            for (scale in listOf(1f, 1.6f)) {
                var bitcoinPositions = emptyList<Float>()
                for (asset in listOf(PaykitAsset.BTC, PaykitAsset.USD)) {
                    composeTestRule.runOnIdle {
                        height.value = screenHeight
                        configuration.value = Triple(width, scale, asset)
                    }
                    composeTestRule.waitForIdle()
                    val amount = composeTestRule.onNodeWithTag("PaymentRequestAmountField").fetchSemanticsNode().boundsInRoot
                    val actions = composeTestRule.onNodeWithTag("PaymentRequestAmountActions").fetchSemanticsNode().boundsInRoot
                    val divider = composeTestRule.onNodeWithTag("PaymentRequestAmountDivider").fetchSemanticsNode().boundsInRoot
                    val lastControl = if (asset == PaykitAsset.BTC) "PaymentRequestAmountUnit" else "PaymentRequestAssetUSD"
                    val control = composeTestRule.onNodeWithTag(lastControl).fetchSemanticsNode().boundsInRoot
                    assertEquals(divider.left, amount.left, 1f)
                    assertEquals(divider.right, control.right, 1f)
                    assertTrue(actions.top >= amount.bottom && actions.top - amount.bottom <= 34f)
                    assertTrue(divider.top >= actions.bottom)
                    val positions = listOf(
                        actions.top,
                        divider.top,
                        composeTestRule.onNodeWithTag("N1").fetchSemanticsNode().boundsInRoot.top,
                        composeTestRule.onNodeWithTag("N0").fetchSemanticsNode().boundsInRoot.top,
                    )
                    if (asset == PaykitAsset.BTC) {
                        bitcoinPositions = positions
                    } else {
                        composeTestRule.onNodeWithTag("PaymentRequestAmountUnit").assertDoesNotExist()
                        bitcoinPositions.zip(positions).forEach { (bitcoin, usd) ->
                            assertEquals(bitcoin, usd, 1f, "Controls must not move when denomination changes")
                        }
                    }
                    val continueButton = composeTestRule.onNodeWithTag("PaymentRequestAmountContinue")
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue(continueButton.height >= 112f, "Continue must be fully visible")
                    listOf("N1", "N0", "NRemove", "PaymentRequestAmountContinue").forEach {
                        composeTestRule.onNodeWithTag(it).assertIsDisplayed()
                    }
                }
            }
        }
    }

}
