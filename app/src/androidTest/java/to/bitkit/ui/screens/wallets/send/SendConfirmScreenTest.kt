package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.FeeRate
import to.bitkit.models.PubkyProfile
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

@ComposeUi
class SendConfirmScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun initialOnchainSubscriptionShowsFeeBeforeConfirmation() {
        val state = SendUiState(
            amount = 3_000u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            isInitialSubscriptionPayment = true,
            initialSubscriptionPaymentAutoStartPending = true,
            onchainFeeUi = OnchainFeeUi(rate = FeeRate.NORMAL, sats = 422),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendConfirmContent(
                        uiState = state,
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                        initialShowDetails = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("SendConfirmAssetButton").assertIsDisplayed()
        composeTestRule.onNodeWithText("422", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe To Subscribe & Pay").assertIsDisplayed()
    }

    @Test
    fun paymentRequestKeepsAmountAndSwipeVisibleOnCompactScreen() {
        val state = SendUiState(
            amount = 1_753u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            isPaymentRequest = true,
            contactPaymentProfile = PubkyProfile.placeholder("requester").copy(name = "Popup requester"),
            paymentRequestNote = "A long invoice note. ".repeat(13).take(256),
            onchainFeeUi = OnchainFeeUi(rate = FeeRate.NORMAL, sats = 141),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f),
                ) {
                    Box(modifier = Modifier.size(width = 360.dp, height = 400.dp)) {
                        SendConfirmContent(
                            uiState = state,
                            isNodeRunning = true,
                            isLoading = false,
                            showBiometrics = false,
                        )
                    }
                }
            }
        }

        val amount = composeTestRule.onNodeWithTag("ReviewAmount")
        val swipe = composeTestRule.onNodeWithTag("GRAB")
        amount.assertIsDisplayed()
        swipe.assertIsDisplayed()
        val amountBounds = amount.getUnclippedBoundsInRoot()
        val swipeBounds = swipe.getUnclippedBoundsInRoot()

        val contentBounds = composeTestRule.onNodeWithTag("SendConfirmContent").getUnclippedBoundsInRoot()
        composeTestRule.onNodeWithTag("PaymentRequestFrom").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestFor").performScrollTo().assertIsDisplayed()
        for (tag in listOf("PaymentRequestFrom", "PaymentRequestFor")) {
            val bounds = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= contentBounds.top)
            assertTrue(bounds.bottom <= contentBounds.bottom)
        }

        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").performClick()
        composeTestRule.onNodeWithTag("PaymentRequestInvoiceNote").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendConfirmContent").performTouchInput { swipeUp() }

        amount.assertIsDisplayed()
        swipe.assertIsDisplayed()
        assertEquals(amountBounds, amount.getUnclippedBoundsInRoot())
        assertEquals(swipeBounds, swipe.getUnclippedBoundsInRoot())
    }
}
