package to.bitkit.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.ConnectionRecoveryUiState
import kotlin.test.assertEquals

@ComposeUi
class WalletConnectionRecoveryViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun requiredRestoreCanSaveConnectionsWithoutProceedingOrRetrying() {
        var editing by mutableStateOf(false)
        var state by mutableStateOf(
            ConnectionRecoveryUiState(
                owner = WalletConnectionRecovery.Owner(0, "test-wallet", 0),
                electrum = "ssl://electrum.example:50002",
            )
        )
        var retries = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    if (editing) {
                        WalletConnectionRecoveryView(
                            state = state,
                            onBack = { editing = false },
                            onElectrum = { state = state.copy(electrum = it) },
                            onRgs = { state = state.copy(rgs = it) },
                            onResetElectrum = {},
                            onResetRgs = {},
                            onSave = { state = state.copy(saved = true) },
                            onRetry = { retries++ },
                        )
                    } else {
                        WalletRestoreErrorView(
                            retryCount = 3,
                            allowProceedWithoutRestore = false,
                            onRetry = { retries++ },
                            onConnectionSettings = { editing = true },
                            onProceedWithoutRestore = { error("Required restoration must remain gated") },
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
        composeTestRule.onNodeWithTag("ConnectionRecoveryButton").performClick()
        composeTestRule.onNodeWithTag("WalletConnectionRecovery").assertIsDisplayed()
        composeTestRule.onNodeWithTag("RecoveryElectrumSave").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("RecoverySaved").performScrollTo().assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, retries) }
        composeTestRule.onNodeWithTag("RecoveryRetry").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, retries) }
        composeTestRule.onNodeWithTag("ProceedWithoutBackupButton").assertDoesNotExist()
    }

    @Test
    fun unavailableOwnerDisablesEditsButKeepsExplicitRetryReachable() {
        var retries = 0
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    WalletConnectionRecoveryView(
                        state = ConnectionRecoveryUiState(error = "Stored connection unavailable"),
                        onBack = {},
                        onElectrum = { error("Unavailable owner must not edit") },
                        onRgs = { error("Unavailable owner must not edit") },
                        onResetElectrum = { error("Unavailable owner must not edit") },
                        onResetRgs = { error("Unavailable owner must not edit") },
                        onSave = { error("Unavailable owner must not save") },
                        onRetry = { retries++ },
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("RecoveryElectrumSave").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("RecoveryRgsSave").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("RecoveryError").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("RecoveryRetry").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, retries) }
    }
}
