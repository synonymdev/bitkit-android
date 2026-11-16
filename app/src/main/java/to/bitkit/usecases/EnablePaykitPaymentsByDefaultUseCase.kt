package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns private and public payments on once for a profile that existed before payments were on by default. Profiles
 * set up later go through profile setup instead, so installs without a wallet at first launch never run this.
 */
@Singleton
class EnablePaykitPaymentsByDefaultUseCase @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val contactPaymentSettingsRepo: ContactPaymentSettingsRepo,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val backupRepo: BackupRepo,
) {
    companion object {
        private const val TAG = "EnablePaykitPaymentsByDefaultUseCase"
    }

    /** Must run before onboarding can create a wallet, so a new install isn't taken for an existing one. */
    suspend fun recordInstallState() {
        withContext(ioDispatcher) {
            runSuspendCatching {
                settingsStore.recordPaykitPaymentsDefaultPending(hadWallet = walletRepo.walletExists())
            }
                .onFailure { Logger.warn("Failed to record Paykit payments default state", it, context = TAG) }
        }
    }

    /** Waits for the node and the Pubky session, then runs once. A failure stays pending for the next foreground. */
    suspend operator fun invoke(): Unit = withContext(ioDispatcher) {
        recordInstallState()
        if (settingsStore.isPaykitPaymentsDefaultPending.first() != true) return@withContext
        lightningRepo.lightningState.first { it.nodeLifecycleState.isRunning() }
        pubkyRepo.awaitInitialization()
        pubkyRepo.isRestoringSession.first { !it }

        val state = PaykitPaymentsDefaultState(
            isPending = settingsStore.isPaykitPaymentsDefaultPending.first() == true,
            walletExists = walletRepo.walletExists(),
            isWiping = backupRepo.isWiping.value,
            isDeletingProfile = privatePaykitRepo.isProfileDeletionInProgress.value,
            isPaykitUiEnabled = PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first()),
            hasIdentity = pubkyRepo.hasIdentity(),
            isProfileSetupPending = settingsStore.isPubkyProfileSetupPending.first(),
            isSignedIn = pubkyRepo.currentSignIn() != null,
            arePaymentsOn = settingsStore.data.first().let {
                it.sharesPrivatePaykitEndpoints && it.sharesPublicPaykitEndpoints
            },
        )
        when (decidePaykitPaymentsDefault(state)) {
            PaykitPaymentsDefaultDecision.NONE, PaykitPaymentsDefaultDecision.WAIT -> Unit
            PaykitPaymentsDefaultDecision.SETTLE -> settingsStore.settlePaykitPaymentsDefault()
            PaykitPaymentsDefaultDecision.ENABLE -> {
                Logger.info("Enabling Paykit payments for existing profile", context = TAG)
                contactPaymentSettingsRepo.enablePaymentsByDefault()
                    .onFailure { Logger.warn("Failed to enable Paykit payments by default", it, context = TAG) }
            }
        }
    }
}

internal data class PaykitPaymentsDefaultState(
    val isPending: Boolean,
    val walletExists: Boolean,
    val isWiping: Boolean,
    val isDeletingProfile: Boolean,
    val isPaykitUiEnabled: Boolean,
    val hasIdentity: Boolean,
    val isProfileSetupPending: Boolean,
    val isSignedIn: Boolean,
    val arePaymentsOn: Boolean,
)

internal enum class PaykitPaymentsDefaultDecision { NONE, WAIT, SETTLE, ENABLE }

internal fun decidePaykitPaymentsDefault(state: PaykitPaymentsDefaultState): PaykitPaymentsDefaultDecision = when {
    !state.isPending -> PaykitPaymentsDefaultDecision.NONE
    !state.walletExists || state.isWiping || state.isDeletingProfile || !state.isPaykitUiEnabled ->
        PaykitPaymentsDefaultDecision.WAIT
    !state.hasIdentity -> PaykitPaymentsDefaultDecision.SETTLE
    state.isProfileSetupPending || !state.isSignedIn -> PaykitPaymentsDefaultDecision.WAIT
    state.arePaymentsOn -> PaykitPaymentsDefaultDecision.SETTLE
    else -> PaykitPaymentsDefaultDecision.ENABLE
}
