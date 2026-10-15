package to.bitkit.ui.screens.wallets.usdt

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtOrchestraTransfer
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.io.File
import java.util.Locale

@HiltAndroidTest
@ComposeUi
class UsdtBridgeDetailsTest {
    private val originalLocale = Locale.getDefault()
    private val address = "0x1111111111111111111111111111111111111111"
    private val sourceTx = "0x" + "ab".repeat(32)
    private val deposit = UsdtDeposit(
        "xit_4cfc646b-3657-4144-a644-b7880f184686", "bsc", "USDT", 50_000_001u,
        sourceTx, "held", null, null,
    )
    private val transfer = UsdtTransfer(
        id = "transfer", sender = address, txHash = sourceTx, userOperationHash = null,
        bridgeGuid = null, orchestra = null, recipient = "0x2222222222222222222222222222222222222222",
        destination = UsdtDestination.ARBITRUM, amount = 50_000_000u, receivedAmount = 50_000_000u,
        fee = 50_000u, isIncoming = true, status = UsdtTransferStatus.PENDING, timestamp = 1_752_500_220u,
    )

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
        hiltRule.inject()
    }

    @After
    fun teardown() { Locale.setDefault(originalLocale) }

    @Test
    fun incomingReceiptDoesNotInventProviderDepositOrRefund() {
        composeTestRule.setContent { Frame { UsdtActivityDetail(transfer, false, address) {} } }
        composeTestRule.onNodeWithText("Receiving USDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("RECEIVING NETWORK").assertIsDisplayed()
        assertSummaryDimensions()
        screenshot("receiving")
        composeTestRule.onNodeWithTag("UsdtActivityDetails").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(sourceTx).assertIsDisplayed()
        composeTestRule.onNodeWithText("DEPOSIT REFERENCE").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRefundAddress").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtActivityExplorer").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtDepositHistory").assertIsDisplayed()
    }

    @Test
    fun deliveringTransferKeepsRealNetworksAndBridgeReferences() {
        val outgoing = transfer.copy(
            isIncoming = false, status = UsdtTransferStatus.BRIDGING, destination = UsdtDestination.TRON,
            amount = 10_000_000u, receivedAmount = 9_990_000u, fee = 40_000u,
            recipient = "TVeZbACe9p6gqKQbQJHG5XKjJG5BBR5rXG",
            orchestra = UsdtOrchestraTransfer("q_01a1319f-3db4-7457-94cf-7cffa86a466f", address, null, null, null),
        )
        composeTestRule.setContent { Frame { UsdtActivityDetail(outgoing, false, address) {} } }
        composeTestRule.onNodeWithText("Delivering USDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivering").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tron").assertIsDisplayed()
        screenshot("delivering")
        composeTestRule.onNodeWithTag("UsdtActivityDetails").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SENDER NETWORK").assertIsDisplayed()
        composeTestRule.onNodeWithText("RECIPIENT NETWORK").assertIsDisplayed()
        composeTestRule.onNodeWithText("Orchestra").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(requireNotNull(outgoing.orchestra).quoteId).performScrollTo().assertIsDisplayed()
        screenshot("delivering-details")
        composeTestRule.onNodeWithTag("UsdtActivityExplorer").assertIsDisplayed()
        waitForClickDebounce()
        composeTestRule.onNodeWithTag("UsdtActivityDetails").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delivering").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun providerRefundShowsSourceNetworkAndLocalizedAmountAndHonorsEligibility() {
        Locale.setDefault(Locale.GERMANY)
        val state = mutableStateOf(DepositUiState(deposits = persistentListOf(deposit)))
        val refundAddress = mutableStateOf("")
        val showConfirmation = mutableStateOf(false)
        composeTestRule.setContent {
            Frame {
                UsdtDepositsContent(
                    state = state.value, address = refundAddress.value, hideBalance = false,
                    onBack = { state.value = state.value.copy(detail = null) },
                    onAddress = { refundAddress.value = it },
                    onDetail = { state.value = state.value.copy(detail = UsdtDepositDetail(deposit, null)) },
                    onHistory = { state.value = state.value.copy(detail = null) }, onMore = {}, onRefresh = {},
                    onRefund = { showConfirmation.value = true },
                )
                if (showConfirmation.value) {
                    UsdtRefundDialog(
                        amount = deposit.amount, hideBalance = false, network = UsdtDepositNetwork.BSC,
                        address = refundAddress.value, onCancel = { showConfirmation.value = false }, onConfirm = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeTestRule.onNodeWithText("BNB Smart Chain").assertIsDisplayed()
        assertHeight("UsdtDepositRow-" + deposit.id, 72f)
        screenshot("deposits")
        composeTestRule.onNodeWithTag("UsdtDepositRow-" + deposit.id).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("RECEIVING NETWORK").assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtRefundAddress").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtDepositDetails").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("USDT address on BSC").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtDepositRefund").assertIsNotEnabled()
        assertHeight("UsdtRefundAddress", 52f)
        screenshot("receiving-details")
        val refundField = composeTestRule.onNodeWithTag("UsdtRefundAddress")
        refundField.performTextInput(address.take(20))
        composeTestRule.runOnIdle {
            state.value = state.value.copy(detail = UsdtDepositDetail(deposit.copy(status = "below_minimum"), null))
        }
        refundField.assertIsFocused().performTextInput(address.drop(20))
        refundField.assertTextContains(address)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("UsdtDepositRefund").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Confirm Refund").assertIsDisplayed()
        composeTestRule.onNodeWithText("50,00 USDT", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Refund address:\n" + address, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("BNB Smart Chain (BSC)", substring = true).assertIsDisplayed()
        screenshot("refund")
        composeTestRule.onNodeWithTag("UsdtRefundCancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("UsdtDepositDetails").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Needs attention").assertIsDisplayed()
        waitForClickDebounce()
        composeTestRule.onNodeWithTag("UsdtDepositDetails").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtDepositHistory").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("UsdtDepositRow-" + deposit.id).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("NavigationBack").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("UsdtDepositRow-" + deposit.id).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("UsdtDepositDetails").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        for (status in listOf("completed", "refunded", "refunding", "refund_requested")) {
            composeTestRule.runOnIdle {
                state.value = state.value.copy(detail = UsdtDepositDetail(deposit.copy(status = status), null))
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("UsdtDepositRefund").assertDoesNotExist()
            composeTestRule.onNodeWithTag("UsdtRefundAddress").assertDoesNotExist()
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        AppThemeSurface {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun assertSummaryDimensions() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val fiat = composeTestRule.onNodeWithText("$ 50.00").fetchSemanticsNode().boundsInRoot
        val status = composeTestRule.onNodeWithText("STATUS").fetchSemanticsNode().boundsInRoot
        val date = composeTestRule.onNodeWithText("DATE").fetchSemanticsNode().boundsInRoot
        val value = composeTestRule.onNodeWithText("Pending").fetchSemanticsNode().boundsInRoot
        assertEquals("Caption line height", 18f, status.height / density, 0.5f)
        assertEquals("Value line height", 20f, value.height / density, 0.5f)
        assertEquals("Amount header and section gap", 102f, (status.top - fiat.top) / density, 1f)
        assertEquals("Detail row cadence", 78f, (date.top - status.top) / density, 1f)
    }

    private fun assertHeight(tag: String, expectedDp: Float) {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(tag, expectedDp, bounds.height / density, 0.5f)
    }

    // PrimaryButton debounces repeat clicks for 500 ms.
    private fun waitForClickDebounce() = SystemClock.sleep(600)

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("bridgeScreenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeTestRule.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1000)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "bridge-$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
