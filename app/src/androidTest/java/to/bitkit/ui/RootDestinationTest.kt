package to.bitkit.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import to.bitkit.data.WalletResetRecovery
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.MigrationRecoveryScreen
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class RootDestinationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        isCriticalUpdateRequired: Boolean = false,
        isShowingMigrationLoading: Boolean = false,
        isCheckingRNMigration: Boolean = false,
        isRNMigrationUnavailable: Boolean = false,
        walletExists: Boolean = true,
        isRecoveryMode: Boolean = false,
        walletResetRecovery: WalletResetRecovery = WalletResetRecovery.Clear,
    ) {
        composeTestRule.setContent {
            AppThemeSurface {
                RootDestination(
                    isCriticalUpdateRequired = isCriticalUpdateRequired,
                    isShowingMigrationLoading = isShowingMigrationLoading,
                    isCheckingRNMigration = isCheckingRNMigration,
                    isRNMigrationUnavailable = isRNMigrationUnavailable,
                    walletExists = walletExists,
                    isRecoveryMode = isRecoveryMode,
                    walletResetRecovery = walletResetRecovery,
                    resetRecovery = { Text(text = "reset", modifier = Modifier.testTag(TAG_RESET)) },
                    criticalUpdate = { Text(text = "critical", modifier = Modifier.testTag(TAG_CRITICAL)) },
                    migrationLoading = { Text(text = "migration", modifier = Modifier.testTag(TAG_MIGRATION)) },
                    migrationRecovery = { MigrationRecoveryScreen(onRetry = {}) },
                    onboarding = { Text(text = "onboarding", modifier = Modifier.testTag(TAG_ONBOARDING)) },
                    wallet = { Text(text = "wallet", modifier = Modifier.testTag(TAG_WALLET)) },
                )
            }
        }
    }

    private fun assertOnly(tag: String) {
        composeTestRule.onNodeWithTag(tag).assertExists()
        listOf(TAG_CRITICAL, TAG_MIGRATION, TAG_MIGRATION_ERROR, TAG_ONBOARDING, TAG_WALLET, TAG_RESET)
            .filterNot { it == tag }
            .forEach { composeTestRule.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun migrationAdmissionPrecedesOnboarding() {
        setContent(walletExists = false, isCheckingRNMigration = true)
        assertOnly(TAG_MIGRATION)
    }

    @Test
    fun recoveryModeCannotBypassMigrationAdmission() {
        setContent(walletExists = false, isRecoveryMode = true, isCheckingRNMigration = true)
        assertOnly(TAG_MIGRATION)
    }

    @Test
    fun unreadableCleanupKeepsItsRetryAheadOfMigrationAdmission() {
        setContent(
            isCheckingRNMigration = true,
            isRNMigrationUnavailable = true,
            walletResetRecovery = WalletResetRecovery.Unavailable,
        )
        assertOnly(TAG_RESET)
    }

    @Test
    fun unavailableMigrationOffersRetryBeforeWalletAndRecoveryMode() {
        val unavailable = mutableStateOf(true)
        val checking = mutableStateOf(true)
        var retries = 0
        composeTestRule.setContent {
            AppThemeSurface {
                RootDestination(
                    isCriticalUpdateRequired = false,
                    isShowingMigrationLoading = false,
                    isCheckingRNMigration = checking.value,
                    isRNMigrationUnavailable = unavailable.value,
                    walletExists = true,
                    isRecoveryMode = true,
                    walletResetRecovery = WalletResetRecovery.Clear,
                    resetRecovery = { Text("reset", Modifier.testTag(TAG_RESET)) },
                    criticalUpdate = { Text("critical", Modifier.testTag(TAG_CRITICAL)) },
                    migrationLoading = { Text("migration", Modifier.testTag(TAG_MIGRATION)) },
                    migrationRecovery = {
                        MigrationRecoveryScreen(onRetry = {
                            retries++
                            unavailable.value = false
                        })
                    },
                    onboarding = { Text("onboarding", Modifier.testTag(TAG_ONBOARDING)) },
                    wallet = { Text("wallet", Modifier.testTag(TAG_WALLET)) },
                )
            }
        }
        assertOnly(TAG_MIGRATION_ERROR)
        composeTestRule.onNodeWithTag("MigrationReadRetry").performClick()
        assertOnly(TAG_MIGRATION)
        composeTestRule.runOnIdle {
            assertEquals(1, retries)
            checking.value = false
        }
        assertOnly(TAG_WALLET)
    }

    @Test
    fun unavailableMigrationPrecedesFreshOnboarding() {
        setContent(walletExists = false, isCheckingRNMigration = true, isRNMigrationUnavailable = true)
        assertOnly(TAG_MIGRATION_ERROR)
    }

    @Test
    fun pendingResetPrecedesOnboardingAndRecoveryMode() {
        setContent(walletExists = false, isRecoveryMode = true, walletResetRecovery = WalletResetRecovery.Pending("test"))
        assertOnly(TAG_RESET)
    }

    @Test
    fun unreadableResetStateBlocksOrdinaryWallet() {
        setContent(walletResetRecovery = WalletResetRecovery.Unavailable)
        assertOnly(TAG_RESET)
    }

    @Test
    fun corruptedResetStateDoesNotOpenWalletOrOnboarding() {
        setContent(walletExists = false, isRecoveryMode = true, walletResetRecovery = WalletResetRecovery.Unreadable("test"))
        assertOnly(TAG_RESET)
    }

    @Test
    fun loadingResetStateBlocksOnboarding() {
        setContent(walletExists = false, walletResetRecovery = WalletResetRecovery.Loading)
        assertOnly(TAG_RESET)
    }

    @Test
    fun whenCriticalUpdateRequiredDuringMigration_shouldShowCriticalUpdateOnly() {
        setContent(isCriticalUpdateRequired = true, isShowingMigrationLoading = true)

        assertOnly(TAG_CRITICAL)
    }

    @Test
    fun whenCriticalUpdateRequiredWithoutWallet_shouldShowCriticalUpdateInsteadOfOnboarding() {
        setContent(isCriticalUpdateRequired = true, walletExists = false)

        assertOnly(TAG_CRITICAL)
    }

    @Test
    fun whenCriticalUpdateRequiredInRecoveryMode_shouldShowCriticalUpdateInsteadOfWallet() {
        setContent(isCriticalUpdateRequired = true, walletExists = false, isRecoveryMode = true)

        assertOnly(TAG_CRITICAL)
    }

    @Test
    fun whenCriticalUpdateRequiredWithWallet_shouldShowCriticalUpdateInsteadOfWallet() {
        setContent(isCriticalUpdateRequired = true)

        assertOnly(TAG_CRITICAL)
    }

    @Test
    fun whenMigrationLoadingWithoutCriticalUpdate_shouldShowMigrationOnly() {
        setContent(isShowingMigrationLoading = true, walletExists = false)

        assertOnly(TAG_MIGRATION)
    }

    @Test
    fun whenMigrationLoadingInRecoveryMode_shouldShowWallet() {
        setContent(isShowingMigrationLoading = true, isRecoveryMode = true)

        assertOnly(TAG_WALLET)
    }

    @Test
    fun whenNoWalletAndNoCriticalUpdate_shouldShowOnboarding() {
        setContent(walletExists = false)

        assertOnly(TAG_ONBOARDING)
    }

    @Test
    fun whenNoWalletInRecoveryMode_shouldShowWallet() {
        setContent(walletExists = false, isRecoveryMode = true)

        assertOnly(TAG_WALLET)
    }

    @Test
    fun whenWalletExistsAndNothingBlocks_shouldShowWallet() {
        setContent()

        assertOnly(TAG_WALLET)
    }
}

private const val TAG_CRITICAL = "TestCriticalUpdate"
private const val TAG_MIGRATION = "TestMigrationLoading"
private const val TAG_MIGRATION_ERROR = "MigrationRecovery"
private const val TAG_ONBOARDING = "TestOnboarding"
private const val TAG_WALLET = "TestWallet"

private const val TAG_RESET = "TestWalletReset"
