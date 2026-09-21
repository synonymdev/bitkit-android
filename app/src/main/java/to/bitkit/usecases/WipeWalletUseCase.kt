package to.bitkit.usecases

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Suppress("LongParameterList")
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
) {
    private val wipeMutex = Mutex()

    suspend operator fun invoke(
        walletIndex: Int = 0,
        resetWalletState: () -> Unit,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        if (!wipeMutex.tryLock()) return Result.failure(WipeAlreadyInProgress())
        backupRepo.setWiping(true)
        lightningRepo.setWiping(true)
        val result = try {
            runSuspendCatching {
                // Fail closed: everything after this widens the window in which the shared mirror stays
                // readable by Ring, so an unverifiable export-disable must abort the wipe.
                pubkyRepo.disableSharedIdentityExport().getOrThrow()
                stopNode().getOrThrow()
                cleanupRemote()
                wipeLocal(walletIndex, resetWalletState).getOrThrow()
                onSuccess()
            }
        } finally {
            lightningRepo.setWiping(false)
            backupRepo.setWiping(false)
            wipeMutex.unlock()
        }
        return result.onFailure {
            Logger.error("Failed to wipe wallet", it, context = TAG)
            if (lightningRepo.lightningState.value.nodeLifecycleState.isRunning()) {
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
        step("close Paykit SDK") { privatePaykitRepo.get().closeAndClear() }
    }

    private suspend fun wipeLocal(walletIndex: Int, resetWalletState: () -> Unit): Result<Unit> {
        lightningRepo.wipeStorage(walletIndex).onFailure { return Result.failure(it) }
        step("clear post-migration sync flag") { migrationService.setNeedsPostMigrationSync(false) }
        step("clear migration data") { migrationService.cleanupAfterMigration() }
        step("clear Paykit address reservations") { privatePaykitAddressReservationRepo.clear() }
        step("wipe Pubky local state") { pubkyRepo.wipeLocalState() }
        val keychainWiped = step("wipe keychain") { keychain.wipe() }
        step("delete FCM token") { firebaseMessaging.deleteToken() }
        step("wipe core data") { coreService.wipeData() }
        step("clear database") { db.clearAllTables() }
        step("reset settings") { settingsStore.reset() }
        step("reset cache") { cacheStore.reset() }
        step("clear watch-only accounts") { watchOnlyAccountRepo.clear() }
        step("reset widgets") { widgetsStore.reset() }
        blocktankRepo.resetState()
        activityRepo.resetState()
        hwWalletRepo.resetState()
        resetWalletState()
        step("mark migration checked") { migrationService.markMigrationChecked() }
        return if (keychainWiped) Result.success(Unit) else Result.failure(WipeIncomplete())
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

class WipeIncomplete : AppError("Wallet wipe did not complete, please reset again")
