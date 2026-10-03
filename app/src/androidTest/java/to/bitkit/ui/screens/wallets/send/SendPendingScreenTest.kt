package to.bitkit.ui.screens.wallets.send

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.io.File
import kotlin.test.assertEquals

@ComposeUi
class SendPendingScreenTest {
    companion object {
        private const val SCREENSHOT_FRAME_WAIT_MS = 500L
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun unresolvedOnchainContentStaysPendingWhenVisibilityIsToggled() {
        // Component rendering only; this fixture does not reload the durable attempt or attempt another send.
        var visible by mutableStateOf(true)
        var closeCount = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    if (visible) {
                        SendPendingContent(
                            amount = 1_000L,
                            isOnchain = true,
                            activityId = null,
                            txid = "ab".repeat(32),
                            refusalReason = "Test backend refusal",
                            onClose = { closeCount++; visible = false },
                            onViewDetails = { error("Unresolved send has no activity") },
                        )
                    }
                }
            }
        }
        assertUnresolved()
        saveScreenshot("ln112-babysit-pending-refused-component.png")
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.runOnIdle { assertEquals(1, closeCount); visible = true }
        assertUnresolved()
        saveScreenshot("ln112-babysit-pending-refused-visible-component.png")
    }

    @Test
    fun exactCandidateWithLocalActivityOffersDetailsWithoutClaimingAcceptance() {
        var detailsId: String? = null
        val txid = "cd".repeat(32)
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendPendingContent(amount = 1_000L, isOnchain = true, activityId = "queued-local-activity",
                        txid = txid, onClose = {}, onViewDetails = { detailsId = it })
                }
            }
        }
        composeTestRule.onNodeWithText(txid, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Payment Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Payment Sent").assertDoesNotExist()
        saveScreenshot("ln112-babysit-pending-details-component.png")
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.runOnIdle { assertEquals("queued-local-activity", detailsId) }
    }

    private fun assertUnresolved() {
        composeTestRule.onNodeWithText("Test backend refusal", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("ab".repeat(32), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Payment Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bitkit will block another send", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Details").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Payment Sent").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeTestRule.waitForIdle()
        instrumentation.waitForIdleSync()
        // Semantics may be committed before the emulator compositor presents that frame.
        android.os.SystemClock.sleep(SCREENSHOT_FRAME_WAIT_MS)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
