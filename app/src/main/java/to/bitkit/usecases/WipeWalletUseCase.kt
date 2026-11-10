package to.bitkit.usecases

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.async.appScope
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaykitUsdtPaymentRepo
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.BoltzService
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Singleton
class WipeWalletUseCase @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
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
    private val connectionRecovery: WalletConnectionRecovery,
    private val boltzService: BoltzService,
) {
    private val wipeMutex = Mutex()
    private val remoteScope = appScope(bgDispatcher, TAG)

    /**
     * The PIN, its attempt counter and the wipe-pending flag outlive every other local step, so a wipe interrupted
     * at any point relaunches locked and can be run again from the start. The flag is deleted last.
     */
    suspend operator fun invoke(
        walletIndex: Int = 0,
        resetWalletState: () -> Unit,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        if (!wipeMutex.tryLock()) return Result.failure(WipeAlreadyInProgress())
        backupRepo.setWiping(true)
        lightningRepo.setWiping(true)
        boltzService.setWiping(true)
        hwWalletRepo.setWiping(true)
        val result = try {
            runSuspendCatching {
                connectionRecovery.withIdentityChange {
                    paykitSdkService.withWalletWipe {
                        requiredStep(WipeStep.STOP_NODE) { stopNode().getOrThrow() }
                        cleanupRemote()
                        wipeLocal(walletIndex, resetWalletState)
                        onSuccess()
                    }
                }
            }
        } finally {
            hwWalletRepo.setWiping(false)
            boltzService.setWiping(false)
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
        // The stream outlives a screen, so left running it would keep the wiped wallet's keys for the next one
        return lightningRepo.stop().onSuccess { boltzService.stopUpdatesAndAwait() }
    }

    private suspend fun cleanupRemote() {
        remoteStep("remove Paykit published endpoints") {
            privatePaykitRepo.get().removePublishedEndpointsForCleanup(TAG)
        }
        remoteStep("remove Bitkit payment endpoints") { pubkyRepo.removeBitkitPaymentEndpoints() }
        step("close Paykit SDK") { privatePaykitRepo.get().closeAndClear() }
        remoteStep("delete FCM token") { firebaseMessaging.deleteToken().await() }
    }

    private suspend fun wipeLocal(walletIndex: Int, resetWalletState: () -> Unit) {
        requiredStep(WipeStep.LDK_STORAGE) { lightningRepo.wipeStorage(walletIndex).getOrThrow() }
        step("clear post-migration sync flag") { migrationService.setNeedsPostMigrationSync(false) }
        step("clear migration data") { migrationService.cleanupAfterMigration() }
        step("clear Paykit address reservations") { privatePaykitAddressReservationRepo.clear() }
        step("wipe Pubky local state") { pubkyRepo.wipeLocalState() }
        val usdtWipe = requiredStep(WipeStep.KEYCHAIN) { usdtRepo.wipe { keychain.wipe(retaining = RETAINED_KEYS) } }
            .onFailure { Logger.warn("Failed wipe step 'wipe USDT'", it, context = TAG) }
        if (usdtWipe.isSuccess) paykitUsdtPaymentRepo.resetForWipe()
        step("wipe core data") { coreService.wipeData() }
        step("clear database") { db.clearAllTables() }
        requiredStep(WipeStep.SETTINGS) { settingsStore.reset() }
        step("reset cache") { cacheStore.reset() }
        step("clear watch-only accounts") { watchOnlyAccountRepo.clear() }
        step("reset widgets") { widgetsStore.reset() }
        blocktankRepo.resetState()
        activityRepo.resetState()
        // A Trezor pairing credential left behind would outlive the wallet
        requiredStep(WipeStep.HARDWARE_WALLET) { hwWalletRepo.resetState().getOrThrow() }
        resetWalletState()
        // The RN app's seed, passphrase and PIN must not outlive the wallet
        requiredStep(WipeStep.LEGACY_DATA) { migrationService.removeLegacyAppData().getOrThrow() }
        // Must hold before the flag is cleared, or a later launch could re-run RN migration and re-import its PIN
        requiredStep(WipeStep.MIGRATION) { migrationService.markMigrationChecked() }
        requiredStep(WipeStep.PIN) { deletePin() }
        requiredStep(WipeStep.COMMIT) { keychain.deleteWipePending() }
        if (usdtWipe.isFailure) throw WipeIncomplete()
    }

    private suspend fun deletePin() {
        keychain.delete(Keychain.Key.PIN.name)
        keychain.delete(Keychain.Key.PIN_ATTEMPTS_REMAINING.name)
        // Only now, so the retained PIN stays readable until it is deleted
        keychain.resetEncryptionKey()
    }

    private suspend fun <T> requiredStep(step: WipeStep, block: suspend () -> T): T =
        runSuspendCatching { block() }.getOrElse { throw WipeStepFailed(step, it) }

    private suspend fun step(name: String, block: suspend () -> Any?): Boolean =
        runSuspendCatching { block() }
            .mapCatching { if (it is Result<*>) it.getOrThrow() }
            .onFailure { Logger.warn("Failed wipe step '$name'", it, context = TAG) }
            .isSuccess

    private suspend fun remoteStep(name: String, block: suspend () -> Any?): Boolean = step(name) {
        // Detached, as an SDK call in flight does not end on cancellation, so the bound ends the wait instead. It keeps
        // the caller's context, as Paykit admits only the wipe's own operations while the wipe runs.
        val call = remoteScope.async(currentCoroutineContext().minusKey(Job)) { block() ?: Unit }
        try {
            val result = runCatching { withTimeoutOrNull(REMOTE_STEP_TIMEOUT) { call.await() } }.getOrElse {
                // A call cancelled on its own while the wipe goes on is a failed step like any other
                if (it is CancellationException) currentCoroutineContext().ensureActive()
                throw RemoteStepFailed(it)
            }
            result ?: throw RemoteStepTimeout()
        } finally {
            if (!call.isCompleted) call.cancel()
        }
    }

    companion object {
        private const val TAG = "WipeWalletUseCase"

        /** Bounds each network cleanup step, so an unreachable server cannot hold up the wipe. */
        private val REMOTE_STEP_TIMEOUT = 15.seconds

        /** Kept through the bulk keychain wipe and deleted only once everything else is gone. */
        private val RETAINED_KEYS = setOf(Keychain.Key.PIN, Keychain.Key.PIN_ATTEMPTS_REMAINING)
    }
}

class WipeAlreadyInProgress : AppError("Wallet wipe already in progress")

class WipeIncomplete : AppError("Wallet wipe left USDT wallet data behind")

/** The wipe steps that must succeed; [BEGIN] is establishing the durable wipe intent. */
enum class WipeStep {
    BEGIN,
    STOP_NODE,
    LDK_STORAGE,
    KEYCHAIN,
    SETTINGS,
    HARDWARE_WALLET,
    LEGACY_DATA,
    MIGRATION,
    PIN,
    COMMIT,
}

class WipeStepFailed(val step: WipeStep, cause: Throwable) : AppError("Failed wipe step '$step'", cause)

private class RemoteStepTimeout : AppError("Remote cleanup timed out")

private class RemoteStepFailed(cause: Throwable) : AppError("Remote cleanup failed", cause)
