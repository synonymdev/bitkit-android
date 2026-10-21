@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
