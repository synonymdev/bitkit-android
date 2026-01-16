package to.bitkit.usecases

import com.google.firebase.messaging.FirebaseMessaging
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.utils.Logger
import javax.inject.Inject
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
    private val widgetsStore: WidgetsStore,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val lightningRepo: LightningRepo,
    private val firebaseMessaging: FirebaseMessaging,
    private val migrationService: MigrationService,
) {
    suspend operator fun invoke(
        walletIndex: Int = 0,
        resetWalletState: () -> Unit,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        return runCatching {
            backupRepo.setWiping(true)
            backupRepo.reset()

            keychain.wipe()
            firebaseMessaging.deleteToken()

            coreService.wipeData()
            db.clearAllTables()

            settingsStore.reset()
            cacheStore.reset()
            widgetsStore.reset()

            blocktankRepo.resetState()
            activityRepo.resetState()
            resetWalletState()

            migrationService.markMigrationChecked()

            lightningRepo.wipeStorage(walletIndex)
                .onSuccess {
                    onSuccess()
                    if (Env.isDebug) Logger.reset()
                }
                .getOrThrow()
        }.onFailure {
            Logger.error("Wipe wallet error", it, context = TAG)
        }.also {
            backupRepo.setWiping(false)
        }
    }

    companion object {
        private const val TAG = "WipeWalletUseCase"
    }
}
