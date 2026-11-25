package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

@RunWith(RobolectricTestRunner::class)
class SendConfirmUpdateFeeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `a payment that needs an update shows its fee without opening the details`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    SendConfirmContent(
                        uiState = SendUiState(
                            amount = 50_000u,
                            isAmountInputValid = true,
                            payMethod = SendMethod.LIGHTNING,
                            decodedInvoice = INVOICE,
                            lightningFeeSats = 12,
                            sparkUpdateQuote = MoveToSparkQuote("lnbcrt1spark", amountSats = 29_967uL, feeSats = 2uL),
                        ),
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                        initialShowDetails = false,
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }

        composeRule.onNodeWithTag("ReviewUpdateFee").assertIsDisplayed()
    }

    private companion object {
        val INVOICE = LightningInvoice(
            bolt11 = "lnbcrt1p5frwyedq2gf5hg6mfwsnp4qdkkcte90vc7c5z6z72uu5p2schwkmlx9j704tuwm2z59wfgku46xpp56yfmwmfxtl",
            paymentHash = ByteArray(0),
            amountSatoshis = 50_000u,
            timestampSeconds = 0u,
            expirySeconds = 3600u,
            isExpired = false,
            networkType = NetworkType.REGTEST,
            payeeNodeId = null,
            description = "Coffee",
        )
    }
}
