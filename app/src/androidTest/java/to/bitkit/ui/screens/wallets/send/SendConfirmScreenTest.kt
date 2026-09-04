package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.FeeRate
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
        composeTestRule.onNodeWithText("Swipe To Subscribe & Pay").performScrollTo().assertIsDisplayed()
    }
}
