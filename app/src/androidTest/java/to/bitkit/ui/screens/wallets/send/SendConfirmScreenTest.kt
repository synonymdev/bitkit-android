package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.FeeRate
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun onchainSendFromSavingsUsesBrandAccent() {
        setConfirmContent(hardwareWalletId = null)

        val pixels = accentPixelCounts()

        assertTrue(pixels.brand > 0, "expected brand accent pixels, got ${pixels.brand}")
        assertEquals(0, pixels.blue, "expected no blue accent pixels")
    }

    @Test
    fun onchainSendFromHardwareWalletUsesBlueAccent() {
        setConfirmContent(hardwareWalletId = "hw-wallet-id")

        val pixels = accentPixelCounts()

        assertTrue(pixels.blue > 0, "expected blue accent pixels, got ${pixels.blue}")
        assertEquals(0, pixels.brand, "expected no brand accent pixels")
    }

    private fun setConfirmContent(hardwareWalletId: String?) {
        val state = SendUiState(
            amount = 10_000u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            hardwareWalletId = hardwareWalletId,
            hardwareWalletName = hardwareWalletId?.let { "Trezor Safe 7" },
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
                    )
                }
            }
        }
    }

    private fun accentPixelCounts(): AccentPixelCounts {
        val pixelMap = composeTestRule.onRoot().captureToImage().toPixelMap()
        var brand = 0
        var blue = 0
        for (y in 0 until pixelMap.height) {
            for (x in 0 until pixelMap.width) {
                when (pixelMap[x, y]) {
                    Colors.Brand -> brand++
                    Colors.Blue -> blue++
                    else -> Unit
                }
            }
        }
        return AccentPixelCounts(brand = brand, blue = blue)
    }
}

private data class AccentPixelCounts(
    val brand: Int,
    val blue: Int,
)
