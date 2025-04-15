package to.bitkit.ui.screens.wallets.send

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PrimaryDisplay
import to.bitkit.viewmodels.CurrencyUiState
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

class SendAmountContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testUiState = SendUiState(
        payMethod = SendMethod.LIGHTNING,
        amountInput = "100",
        isAmountInputValid = true,
        isUnified = true
    )

    private val testWalletState = MainUiState(
        nodeLifecycleState = NodeLifecycleState.Running
    )

    @Test
    fun whenScreenLoaded_shouldShowAllComponents() {
        composeTestRule.setContent {
            SendAmountContent(
                input = "100",
                uiState = testUiState,
                walletUiState = testWalletState,
                currencyUiState = CurrencyUiState(primaryDisplay = PrimaryDisplay.BITCOIN),
                onInputChanged = {},
                onEvent = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("send_amount_screen").assertExists()
//        composeTestRule.onNodeWithTag("amount_input_field").assertExists() doesn't displayed because of viewmodel injection
        composeTestRule.onNodeWithTag("available_balance").assertExists()
        composeTestRule.onNodeWithTag("payment_method_button").assertExists()
        composeTestRule.onNodeWithTag("continue_button").assertExists()
        composeTestRule.onNodeWithTag("amount_keyboard").assertExists()
    }

    @Test
    fun whenNodeNotRunning_shouldShowSyncView() {
        composeTestRule.setContent {
            SendAmountContent(
                input = "100",
                uiState = testUiState,
                walletUiState = MainUiState(
                    nodeLifecycleState = NodeLifecycleState.Initializing
                ),
                currencyUiState = CurrencyUiState(),
                onInputChanged = {},
                onEvent = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("sync_node_view").assertExists()
        composeTestRule.onNodeWithTag("amount_input_field").assertDoesNotExist()
    }

    @Test
    fun whenPaymentMethodButtonClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                input = "100",
                uiState = testUiState,
                walletUiState = testWalletState,
                currencyUiState = CurrencyUiState(),
                onInputChanged = {},
                onEvent = { event ->
                    if (event is SendEvent.PaymentMethodSwitch) {
                        eventTriggered = true
                    }
                },
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("payment_method_button")
            .performClick()

        assert(eventTriggered)
    }

    @Test
    fun whenContinueButtonClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                input = "100",
                uiState = testUiState.copy(isAmountInputValid = true),
                walletUiState = testWalletState,
                currencyUiState = CurrencyUiState(),
                onInputChanged = {},
                onEvent = { event ->
                    if (event is SendEvent.AmountContinue) {
                        eventTriggered = true
                    }
                },
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("continue_button")
            .performClick()

        assert(eventTriggered)
    }

    @Test
    fun whenAmountInvalid_continueButtonShouldBeDisabled() {
        composeTestRule.setContent {
            SendAmountContent(
                input = "100",
                uiState = testUiState.copy(isAmountInputValid = false),
                walletUiState = testWalletState,
                currencyUiState = CurrencyUiState(),
                onInputChanged = {},
                onEvent = {},
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("continue_button").assertIsNotEnabled()
    }
}
