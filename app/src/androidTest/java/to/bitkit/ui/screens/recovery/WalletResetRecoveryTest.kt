package to.bitkit.ui.screens.recovery

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.data.WalletResetRecovery
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class WalletResetRecoveryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun resetRequiresFreshConfirmationAndCancelDoesNothing() {
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface {
                WalletResetRecoveryContent(WalletResetRecovery.Pending("original"), false, {}, finished::add)
            }
        }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
        rule.onNodeWithTag("DialogCancel").performClick()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
        val dismissedAt = SystemClock.uptimeMillis()
        rule.waitUntil(1_000) { SystemClock.uptimeMillis() - dismissedAt >= 600 }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.onNodeWithTag("DialogConfirm").performClick()
        rule.runOnIdle { assertEquals(listOf("original"), finished) }
    }

    @Test
    fun changedAttemptDiscardsTheOldConfirmation() {
        val recovery = mutableStateOf<WalletResetRecovery>(WalletResetRecovery.Pending("old"))
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface { WalletResetRecoveryContent(recovery.value, false, {}, finished::add) }
        }
        rule.onNodeWithTag("WalletResetFinish").performClick()
        rule.runOnIdle { recovery.value = WalletResetRecovery.Pending("new") }
        rule.onNodeWithTag("DialogConfirm").assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
    }

    @Test
    fun unreadableStateOffersOnlyReadRetryAndBusyResetCannotRepeat() {
        val recovery = mutableStateOf<WalletResetRecovery>(WalletResetRecovery.Unavailable)
        var reads = 0
        val finished = mutableListOf<String>()
        rule.setContent {
            AppThemeSurface { WalletResetRecoveryContent(recovery.value, true, { reads++ }, finished::add) }
        }
        rule.onNodeWithTag("WalletResetFinish").assertDoesNotExist()
        rule.onNodeWithTag("WalletResetReadRetry").performClick()
        rule.runOnIdle {
            assertEquals(1, reads)
            assertEquals(emptyList(), finished)
            recovery.value = WalletResetRecovery.Pending("pending")
        }
        rule.onNodeWithTag("WalletResetFinish").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(emptyList(), finished) }
    }
}
