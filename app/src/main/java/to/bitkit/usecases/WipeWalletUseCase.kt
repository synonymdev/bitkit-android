package to.bitkit.usecases

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.ProfileRemovalScope
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaykitUsdtPaymentRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class WipeWalletUseCase @Inject constructor(
    private val backupRepo: BackupRepo,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val db: AppDb,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo,
    private val widgetsStore: WidgetsStore,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val hwWalletRepo: HwWalletRepo,
    private val lightningRepo: LightningRepo,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: Provider<PrivatePaykitRepo>,
    private val privatePaykitAddressReservationRepo: PrivatePaykitAddressReservationRepo,
    private val firebaseMessaging: FirebaseMessaging,
    private val migrationService: MigrationService,
    private val paykitSdkService: PaykitSdkService,
    private val usdtRepo: UsdtRepo,
    private val paykitUsdtPaymentRepo: PaykitUsdtPaymentRepo,
    private val profileRemovalStore: ProfileRemovalStore,
    private val contactPaymentSettingsRepo: Provider<ContactPaymentSettingsRepo>,
    private val connectionRecovery: WalletConnectionRecovery,
) {
    private val wipeMutex = Mutex()

    suspend operator fun invoke(
        walletIndex: Int = 0,
        resetWalletState: () -> Unit,
        onSuccess: () -> Unit,
        expectedPendingAttemptId: String? = null,
    ): Result<Unit> {
        if (!wipeMutex.tryLock()) return Result.failure(WipeAlreadyInProgress())
        var wipeAdmitted = false
        var credentialsMayBeErased = false
        val result = try {
            runSuspendCatching {
                connectionRecovery.withIdentityChange(isStillNeeded = {
                    expectedPendingAttemptId?.let { profileRemovalStore.requirePendingWalletReset(it) }
                    backupRepo.setWiping(true)
                    lightningRepo.setWiping(true)
                    wipeAdmitted = true
                    true
                }) {
                    expectedPendingAttemptId?.let { profileRemovalStore.requirePendingWalletReset(it) }
                    contactPaymentSettingsRepo.get().withWalletWipe(pubkyRepo::withWalletWipeAdmission) {
                        paykitSdkService.withWalletWipe {
                            expectedPendingAttemptId?.let { profileRemovalStore.requirePendingWalletReset(it) }
                            val pending = profileRemovalStore.pendingAttempt()
                            val attemptId = pending ?: UUID.randomUUID().toString()
                            profileRemovalStore.withAccess(attemptId, ProfileRemovalScope.WALLET_WIPE) {
                                privatePaykitRepo.get().withProfileDeletionPaused { pause ->
                                    stopNode().getOrThrow()
                                    profileRemovalStore.begin(attemptId)
                                    if (pending == null) cleanupRemote()
                                    wipeLocal(walletIndex, pause) { credentialsMayBeErased = true }.getOrThrow()
                                    profileRemovalStore.complete(attemptId)
                                    resetWalletState()
                                    onSuccess()
                                }
                            }
                        }
                    }
                }
            }.let { outcome ->
                val error = outcome.exceptionOrNull()
                if (error != null && credentialsMayBeErased) Result.failure(WipeIncomplete(error)) else outcome
            }
        } finally {
            if (wipeAdmitted) {
                lightningRepo.setWiping(false)
                backupRepo.setWiping(false)
            }
            wipeMutex.unlock()
        }
        return result.onFailure {
            Logger.error("Failed to wipe wallet", it, context = TAG)
            if (wipeAdmitted && lightningRepo.lightningState.value.nodeLifecycleState.isRunning()) {
                backupRepo.startObservingBackups()
            }
        }
    }

    private suspend fun stopNode(): Result<Unit> {
        backupRepo.reset()
        return lightningRepo.stop()
    }

    private suspend fun cleanupRemote() {
        step("remove Paykit published endpoints") { privatePaykitRepo.get().removePublishedEndpointsForCleanup(TAG) }
        step("remove Bitkit payment endpoints") { pubkyRepo.removeBitkitPaymentEndpoints() }
    }

    private suspend fun wipeLocal(
        walletIndex: Int,
        pause: PrivatePaykitRepo.ProfileDeletionPause,
        onCredentialWipe: () -> Unit,
    ): Result<Unit> {
        lightningRepo.wipeStorage(walletIndex).onFailure { return Result.failure(it) }
        migrationService.setNeedsPostMigrationSync(false)
        migrationService.cleanupAfterMigration()
        onCredentialWipe()
        usdtRepo.wipe { keychain.wipe() }.getOrThrow()
        paykitUsdtPaymentRepo.resetForWipe()
        // Consumption watermarks survive until the keychain wipe is acknowledged. Pending remains on any failure.
        privatePaykitAddressReservationRepo.clear()
        privatePaykitRepo.get().finishProfileRemoval(pause).getOrElse { return Result.failure(it) }
        pubkyRepo.finishProfileWipeLocally()
        step("delete FCM token") { firebaseMessaging.deleteToken() }
        coreService.wipeData().getOrThrow()
        db.clearAllTables()
        settingsStore.reset()
        cacheStore.reset()
        watchOnlyAccountRepo.clear()
        widgetsStore.reset()
        blocktankRepo.resetState()
        activityRepo.resetState()
        hwWalletRepo.resetState()
        migrationService.markMigrationChecked()
        return Result.success(Unit)
    }

    private suspend fun step(name: String, block: suspend () -> Any?): Boolean =
        runSuspendCatching { block() }
            .mapCatching { if (it is Result<*>) it.getOrThrow() }
            .onFailure { Logger.warn("Failed wipe step '$name'", it, context = TAG) }
            .isSuccess

    companion object {
        private const val TAG = "WipeWalletUseCase"
    }
}

class WipeAlreadyInProgress : AppError("Wallet wipe already in progress")

class WipeIncomplete(cause: Throwable? = null) : AppError("Wallet wipe did not complete, please reset again", cause)
