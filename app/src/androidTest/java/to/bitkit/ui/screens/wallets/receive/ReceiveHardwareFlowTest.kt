package to.bitkit.ui.screens.wallets.receive

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwReceiveAddress
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class ReceiveHardwareFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hardwareEditorReturnsToTrezorTab() {
        var addressLoadCount = 0

        composeTestRule.setContent {
            val editState = remember { ReceiveInvoiceEditState() }
            var isEditing by remember { mutableStateOf(false) }

            AppThemeSurface {
                if (isEditing) {
                    Button(
                        onClick = { isEditing = false },
                        modifier = Modifier.testTag("ReturnFromHardwareEdit"),
                    ) {
                        Text("Return")
                    }
                } else {
                    ReceiveQrScreen(
                        cjitInvoice = null,
                        walletState = WALLET_STATE,
                        lightningState = LightningState(),
                        onClickEditInvoice = {},
                        onClickReceiveCjit = {},
                        onClickHardwareEditInvoice = {
                            editState.beginHardwareEdit()
                            isEditing = true
                        },
                        initialTab = editState.initialTab(hardwareWalletId = null),
                        hardwareWalletId = WALLET_ID,
                        hardwareReceiveState = HW_RECEIVE_STATE,
                        onLoadHardwareAddress = { addressLoadCount++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("Tab-trezor").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, addressLoadCount)

        composeTestRule.onNodeWithTag("SpecifyInvoiceButton").performClick()
        composeTestRule.onNodeWithTag("ReturnFromHardwareEdit").performClick()
        composeTestRule.waitForIdle()

        assertEquals(2, addressLoadCount)
    }

    @Test
    fun receivePassphrasePromptUsesAddressVerificationCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeTestRule.setContent {
            AppThemeSurface {
                ReceivePassphrasePrompt(
                    state = HwReceiveUiState(isPassphraseRequired = true),
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.hardware__passphrase_verify_address_text))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.hardware__passphrase_sign_text))
            .assertDoesNotExist()
    }

    private companion object {
        const val WALLET_ID = "trezor:wallet"
        val WALLET_STATE = WalletState(
            onchainAddress = "bcrt1qsoftwarewalletaddress",
            bip21 = "bitcoin:bcrt1qsoftwarewalletaddress",
        )
        val HW_RECEIVE_STATE = HwReceiveUiState(
            walletId = WALLET_ID,
            address = HwReceiveAddress(
                address = "bcrt1qhardwarewalletaddress",
                path = "m/84'/1'/0'/0/0",
                addressType = HwFundingAddressType.NATIVE_SEGWIT,
            ),
        )
    }
}
