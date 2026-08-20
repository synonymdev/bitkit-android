package to.bitkit.ui.screens.wallets.receive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel

@ComposeUi
class EditInvoiceScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun paymentRequestActionAndShowQrAreVisibleWhenEligible() {
        composeTestRule.setContent {
            AppThemeSurface {
                EditInvoiceContent(
                    amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                    noteText = "Dinner",
                    isSoftKeyboardVisible = false,
                    keyboardVisible = false,
                    tags = persistentListOf(),
                    onBack = {},
                    onContinueKeyboard = {},
                    onClickBalance = {},
                    onContinueGeneral = {},
                    onClickAddTag = {},
                    onTextChanged = {},
                    onClickTag = {},
                    showPaymentRequestButton = true,
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestSendButton").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ShowQrReceive").assertIsDisplayed()
    }

    @Test
    fun paymentRequestActionIsHiddenWithoutEligibleContact() {
        composeTestRule.setContent {
            AppThemeSurface {
                EditInvoiceContent(
                    amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                    noteText = "",
                    isSoftKeyboardVisible = false,
                    keyboardVisible = false,
                    tags = persistentListOf(),
                    onBack = {},
                    onContinueKeyboard = {},
                    onClickBalance = {},
                    onContinueGeneral = {},
                    onClickAddTag = {},
                    onTextChanged = {},
                    onClickTag = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestSendButton").assertDoesNotExist()
    }
}
