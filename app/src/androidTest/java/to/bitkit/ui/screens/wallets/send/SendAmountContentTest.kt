package to.bitkit.ui.screens.wallets.send

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NodeLifecycleState
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.previewAmountInputViewModel

class SendAmountContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val uiState = SendUiState(
        payMethod = SendMethod.LIGHTNING,
        amount = 100u,
        isUnified = true
    )

    private val nodeLifecycleState = NodeLifecycleState.Running

    @Test
    fun whenScreenLoaded_shouldShowAllComponents() {
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState,
                amountInputViewModel = previewAmountInputViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("send_amount_screen").assertExists()
        composeTestRule.onNodeWithTag("SendNumberField").assertExists()
        composeTestRule.onNodeWithTag("available_balance", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("AssetButton-switch").assertExists()
        composeTestRule.onNodeWithTag("ContinueAmount").assertExists()
        composeTestRule.onNodeWithTag("SendAmountNumberPad").assertExists()
    }

    @Test
    fun whenNodeNotRunning_shouldShowSyncView() {
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = NodeLifecycleState.Initializing,
                uiState = uiState,
                amountInputViewModel = previewAmountInputViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("sync_node_view").assertExists()
        composeTestRule.onNodeWithTag("SendNumberField").assertDoesNotExist()
    }

    @Test
    fun whenPaymentMethodButtonClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState,
                amountInputViewModel = previewAmountInputViewModel(),
                onClickPayMethod = { eventTriggered = true }
            )
        }

        composeTestRule.onNodeWithTag("AssetButton-switch").performClick()

        assert(eventTriggered)
    }

    @Test
    fun whenContinueButtonClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState,
                amountInputViewModel = previewAmountInputViewModel(),
                onContinue = { eventTriggered = true }
            )
        }

        composeTestRule.onNodeWithTag("ContinueAmount")
            .performClick()

        assert(eventTriggered)
    }

    @Test
    fun whenAmountInvalid_continueButtonShouldBeDisabled() {
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState.copy(amount = 0u),
                amountInputViewModel = previewAmountInputViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("ContinueAmount").assertIsNotEnabled()
    }
}
