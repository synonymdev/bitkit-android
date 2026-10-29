package to.bitkit.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.ConnectionStartupFailure
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.canProceedWithoutRestore
import kotlin.test.assertEquals

@ComposeUi
class WalletRestoreErrorViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun ordinaryStartupFailureUsesProductionGateAndDismissesOnlyAfterConfirmation() {
        var failure by mutableStateOf<ConnectionStartupFailure?>(ConnectionStartupFailure("storage unavailable", true))
        var restore by mutableStateOf<RestoreState>(RestoreState.Retry(0))
        var dismissed = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    if (failure != null || restore is RestoreState.BackupFailed) {
                        WalletRestoreErrorView(
                            retryCount = restore.retryCount(),
                            allowProceedWithoutRestore = canProceedWithoutRestore(restore, failure?.canProceedWithoutRestore),
                            onRetry = {},
                            onProceedWithoutRestore = {
                                failure = null
                                restore = RestoreState.Settled
                                dismissed++
                            },
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.runOnIdle { restore = RestoreState.Retry(1) }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.runOnIdle { restore = RestoreState.Retry(2) }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("ProceedWithoutBackupDialog").assertIsDisplayed()
        composeTestRule.runOnIdle { failure = failure?.copy(canProceedWithoutRestore = false) }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupDialog").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.runOnIdle { failure = failure?.copy(canProceedWithoutRestore = true) }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").performClick()
        val confirm = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common__yes_proceed)
        composeTestRule.onNodeWithText(confirm).performClick()
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.onNodeWithTag("TryAgainButton").assertDoesNotExist()
        composeTestRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun pendingRecoveryOnlyOffersRetryAfterRepeatedFailures() {
        var retries = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    WalletRestoreErrorView(
                        retryCount = 3,
                        allowProceedWithoutRestore = false,
                        onRetry = { retries++ },
                        onProceedWithoutRestore = { error("Pending recovery must not be dismissed") },
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.onNodeWithTag("TryAgainButton").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun pendingRecoveryRemovesAnAlreadyOpenProceedDialog() {
        var allowed by mutableStateOf(true)
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    WalletRestoreErrorView(
                        retryCount = 3,
                        allowProceedWithoutRestore = allowed,
                        onRetry = {},
                        onProceedWithoutRestore = { error("Pending recovery must not be dismissed") },
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").performClick()
        composeTestRule.onNodeWithTag("ProceedWithoutBackupDialog").assertIsDisplayed()
        composeTestRule.runOnIdle { allowed = false }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupDialog").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
    }
}
