package to.bitkit.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import to.bitkit.security.WipeState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.utils.AppError

@ComposeUi
class RootDestinationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        wipeState: WipeState = WipeState.Idle,
        isCriticalUpdateRequired: Boolean = false,
        isShowingMigrationLoading: Boolean = false,
        walletExists: Boolean = true,
        isRecoveryMode: Boolean = false,
    ) {
        composeTestRule.setContent {
            RootDestination(
                wipeState = wipeState,
                isCriticalUpdateRequired = isCriticalUpdateRequired,
                isShowingMigrationLoading = isShowingMigrationLoading,
                walletExists = walletExists,
                isRecoveryMode = isRecoveryMode,
                wipeGate = { Text(text = "wipe gate", modifier = Modifier.testTag(TAG_WIPE_GATE)) },
                criticalUpdate = { Text(text = "critical", modifier = Modifier.testTag(TAG_CRITICAL)) },
                migrationLoading = { Text(text = "migration", modifier = Modifier.testTag(TAG_MIGRATION)) },
                onboarding = { Text(text = "onboarding", modifier = Modifier.testTag(TAG_ONBOARDING)) },
                wallet = { Text(text = "wallet", modifier = Modifier.testTag(TAG_WALLET)) },
            )
        }
    }

    private fun assertOnly(tag: String) {
        composeTestRule.onNodeWithTag(tag).assertExists()
        listOf(TAG_WIPE_GATE, TAG_CRITICAL, TAG_MIGRATION, TAG_ONBOARDING, TAG_WALLET)
            .filterNot { it == tag }
            .forEach { composeTestRule.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun whenWipePending_shouldShowWipeGateOnly() {
        setContent(
            wipeState = WipeState.Running,
            isCriticalUpdateRequired = true,
            isShowingMigrationLoading = true,
            isRecoveryMode = true,
        )

        assertOnly(TAG_WIPE_GATE)
    }

    @Test
    fun whenWipeFailedWithoutWallet_shouldShowWipeGateInsteadOfOnboarding() {
        setContent(wipeState = WipeState.Failed(step = null, error = AppError("failed")), walletExists = false)

        assertOnly(TAG_WIPE_GATE)
    }

    @Test
    fun whenWipeStateUnknown_shouldShowBlockedGateWithoutWipe() {
        setContent(
            wipeState = WipeState.Blocked(AppError("unreadable")),
            isCriticalUpdateRequired = true,
            walletExists = false,
            isRecoveryMode = true,
        )

        assertOnly(TAG_WIPE_GATE)
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

private const val TAG_WIPE_GATE = "TestWipeGate"
private const val TAG_CRITICAL = "TestCriticalUpdate"
private const val TAG_MIGRATION = "TestMigrationLoading"
private const val TAG_ONBOARDING = "TestOnboarding"
private const val TAG_WALLET = "TestWallet"
