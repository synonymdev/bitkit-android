package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrivatePaykitError
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private and public payments are always on while a Pubky profile exists, and Settings has no toggles for them.
 * Run on each foreground: it turns on whichever is off, private first, through the regular enable paths. A failure is
 * logged and tried again on the next foreground. A profile without private payment access is skipped for private
 * payments until the next launch.
 */
@Singleton
class KeepContactPaymentsOnUseCase @Inject constructor(
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
        private const val TAG = "KeepContactPaymentsOnUseCase"
    }

    private val privateUnavailableIdentities = mutableSetOf<String>()

    /**
     * Waits for the node and, when an identity is saved, for its session, then turns on whichever payment mode is
     * off. Session restoration can be deferred while Paykit is busy, so this waits for a signed-in identity rather
     * than for the first restoration attempt to end.
     */
    suspend operator fun invoke(): Unit = withContext(ioDispatcher) {
        lightningRepo.lightningState.first { it.nodeLifecycleState.isRunning() }
        pubkyRepo.awaitInitialization()
        if (!pubkyRepo.hasIdentity()) return@withContext
        pubkyRepo.publicKey.first { it != null }

        val signIn = pubkyRepo.currentSignIn()
        val settings = settingsStore.data.first()
        val state = ContactPaymentsState(
            walletExists = walletRepo.walletExists(),
            isWiping = backupRepo.isWiping.value,
            isDeletingProfile = privatePaykitRepo.isProfileDeletionInProgress.value,
            isPaykitUiEnabled = PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first()),
            isProfileSetupPending = settingsStore.isPubkyProfileSetupPending.first(),
            isSignedIn = signIn != null,
            isPrivateOn = settings.sharesPrivatePaykitEndpoints,
            isPublicOn = settings.sharesPublicPaykitEndpoints,
            isPrivateUnavailable = signIn?.publicKey in privateUnavailableIdentities,
        )
        val decision = decideContactPayments(state)
        if (decision.enablePrivate) {
            Logger.info("Turning private payments on for the current profile", context = TAG)
            contactPaymentSettingsRepo.setEnabled(true).onFailure {
                if (it is PrivatePaykitError.PrivateUnavailable) {
                    signIn?.publicKey?.let(privateUnavailableIdentities::add)
                    Logger.info("Profile has no private payment access; skipping until next launch", context = TAG)
                } else {
                    Logger.warn("Failed to turn private payments on", it, context = TAG)
                }
            }
        }
        if (decision.enablePublic) {
            Logger.info("Turning public payments on for the current profile", context = TAG)
            contactPaymentSettingsRepo.setPublicEnabled(true)
                .onFailure { Logger.warn("Failed to turn public payments on", it, context = TAG) }
        }
    }
}

internal data class ContactPaymentsState(
    val walletExists: Boolean,
    val isWiping: Boolean,
    val isDeletingProfile: Boolean,
    val isPaykitUiEnabled: Boolean,
    val isProfileSetupPending: Boolean,
    val isSignedIn: Boolean,
    val isPrivateOn: Boolean,
    val isPublicOn: Boolean,
    val isPrivateUnavailable: Boolean,
)

internal data class ContactPaymentsDecision(val enablePrivate: Boolean, val enablePublic: Boolean)

internal fun decideContactPayments(state: ContactPaymentsState): ContactPaymentsDecision {
    val canRun = state.walletExists && !state.isWiping && !state.isDeletingProfile && state.isPaykitUiEnabled &&
        !state.isProfileSetupPending && state.isSignedIn
    if (!canRun) return ContactPaymentsDecision(enablePrivate = false, enablePublic = false)
    return ContactPaymentsDecision(
        enablePrivate = !state.isPrivateOn && !state.isPrivateUnavailable,
        enablePublic = !state.isPublicOn,
    )
}
