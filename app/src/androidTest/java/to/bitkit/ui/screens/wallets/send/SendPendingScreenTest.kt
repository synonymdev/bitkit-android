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
                            onClose = { closeCount++; visible = false },
                            onViewDetails = { error("Unresolved send has no activity") },
                        )
                    }
                }
            }
        }
        assertUnresolved()
        saveScreenshot("ln112-onchain-pending.png")
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.runOnIdle { assertEquals(1, closeCount); visible = true }
        assertUnresolved()
        saveScreenshot("ln112-onchain-pending-reopened.png")
    }

    private fun assertUnresolved() {
        composeTestRule.onNodeWithText("Payment Pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bitkit will block another send", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Details").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Payment Sent").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
