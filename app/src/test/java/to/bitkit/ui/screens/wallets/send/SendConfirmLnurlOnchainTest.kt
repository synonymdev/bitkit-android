package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.ServiceRouteLeg
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.PaykitRouteUi
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

@RunWith(RobolectricTestRunner::class)
class SendConfirmLnurlOnchainTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `an onchain payment to a contact with an lnurl confirms with the onchain details`() {
        render(
            SendUiState(
                amount = 6_000u,
                address = ADDRESS,
                isAmountInputValid = true,
                payMethod = SendMethod.ONCHAIN,
                lnurl = LnurlParams.LnurlPay(LNURL),
            ),
        )
        val lnurlTitle = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.wallet__lnurl_p_title)

        composeRule.onNodeWithText(lnurlTitle).assertDoesNotExist()
        composeRule.onNodeWithTag("SendConfirmToggleDetails").assertExists()
        composeRule.onNodeWithTag("ReviewUri").assertTextEquals(ADDRESS)
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `a spark leg to a contact with an lnurl confirms to the leg's onchain address`() {
        val endpoint = PaykitEndpoint(methodId = "btc-regtest-p2wpkh", value = ADDRESS)
        val leg = ServiceRouteLeg(
            service = "spark",
            endpoint = endpoint,
            deliveredSat = 2_000uL,
            debitSat = 2_300uL,
            atomicity = "trusted",
            validUntilUnix = 0uL,
        )
        render(
            SendUiState(
                amount = 2_000u,
                isAmountInputValid = true,
                payMethod = SendMethod.LIGHTNING,
                lnurl = LnurlParams.LnurlPay(LNURL),
                lightningFeeSats = 300,
                paykitRoute = PaykitRouteUi(persistentListOf(endpoint), offersSparkLeg = true, serviceLeg = leg),
            ),
        )

        composeRule.onNodeWithTag("ReviewSwapRoute").assertExists()
        composeRule.onNodeWithTag("ReviewUri").assertTextEquals(ADDRESS)
    }

    private fun render(uiState: SendUiState) {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    SendConfirmContent(
                        uiState = uiState,
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                        initialShowDetails = true,
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }
    }

    private companion object {
        const val ADDRESS = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r"
        val LNURL = LnurlPayData(
            uri = "lnurl1mixedcontact",
            callback = "https://example.com/pay",
            minSendable = 5_000_000u,
            maxSendable = 100_000_000u,
            metadataStr = "[]",
            commentAllowed = null,
            allowsNostr = false,
            nostrPubkey = null,
        )
    }
}
