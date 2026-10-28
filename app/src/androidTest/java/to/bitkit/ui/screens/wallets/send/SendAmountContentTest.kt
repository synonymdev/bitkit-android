package to.bitkit.ui.screens.wallets.send

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NodeLifecycleState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.viewmodels.SendFundingOption
import to.bitkit.viewmodels.SendFundingSource
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ComposeUi
class SendAmountContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val uiState = SendUiState(
        payMethod = SendMethod.LIGHTNING,
        amount = 100u,
        isAmountInputValid = true,
        isUnified = true,
        canSwitchFundingSource = true,
        fundingOptions = persistentListOf(
            SendFundingOption(SendFundingSource.Hardware("device"), name = "Trezor", enabled = true),
            SendFundingOption(SendFundingSource.Spending, enabled = true),
        ),
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
    fun whenPaymentMethodSelected_shouldTriggerEvent() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var selectedSource: SendFundingSource? = null
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState,
                amountInputViewModel = previewAmountInputViewModel(),
                onClickPayMethod = { selectedSource = it }
            )
        }

        composeTestRule.onNodeWithTag("AssetButton-switch").performClick()
        composeTestRule.onNodeWithTag("SendFundingSources").assertExists()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            automation.windows.any { window ->
                window.root?.containsResourceId("SendFundingSources") == true
            }
        }
        composeTestRule.runOnIdle { assertNull(selectedSource) }

        composeTestRule.onAllNodesWithTag("SendFundingSourceBitcoin").assertCountEquals(1)
        composeTestRule.onNodeWithTag("SendFundingSourceHardware-device").performClick()

        composeTestRule.runOnIdle { assertEquals(SendFundingSource.Hardware("device"), selectedSource) }
        composeTestRule.onNodeWithTag("SendFundingSources").assertDoesNotExist()
    }

    @Test
    fun whenContinueButtonClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState.copy(isAmountInputValid = true),
                amountInputViewModel = previewAmountInputViewModel(),
                onContinue = { eventTriggered = true }
            )
        }

        composeTestRule.onNodeWithTag("ContinueAmount")
            .performClick()

        assertTrue(eventTriggered)
    }

    @Test
    fun whenAmountInvalid_continueButtonShouldBeDisabled() {
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState.copy(amount = 0u, isAmountInputValid = false),
                amountInputViewModel = previewAmountInputViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("ContinueAmount").assertIsNotEnabled()
    }

    @Test
    fun whenFundingSourceLoading_sourceIgnoresClicksAndContinueIsDisabled() {
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState.copy(isFundingSourceLoading = true),
                amountInputViewModel = previewAmountInputViewModel(),
            )
        }

        composeTestRule.onNodeWithTag("AssetButton-switch").assertHasNoClickAction()
        composeTestRule.onNodeWithTag("ContinueAmount").assertIsNotEnabled()
    }

    @Test
    fun whenContinueLoading_sourceButtonKeepsStyleAndIgnoresClicks() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendAmountContent(
                nodeLifecycleState = nodeLifecycleState,
                uiState = uiState.copy(isLoading = true),
                amountInputViewModel = previewAmountInputViewModel(),
                onClickPayMethod = { eventTriggered = true },
            )
        }

        composeTestRule.onNodeWithTag("AssetButton-switch").assertHasNoClickAction()
        composeTestRule.onNodeWithTag("ContinueAmount").assertIsNotEnabled()
        assertFalse(eventTriggered)
    }

    private fun AccessibilityNodeInfo.containsResourceId(resourceId: String): Boolean =
        viewIdResourceName == resourceId || (0 until childCount).any {
            getChild(it)?.containsResourceId(resourceId) == true
        }
}
