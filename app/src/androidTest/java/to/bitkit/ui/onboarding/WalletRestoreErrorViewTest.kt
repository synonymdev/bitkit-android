package to.bitkit.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class WalletRestoreErrorViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
