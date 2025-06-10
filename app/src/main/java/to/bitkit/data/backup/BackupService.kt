package to.bitkit.data.backup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.di.json
import to.bitkit.models.BackupCategory
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
    private val vssClient: VssBackupClient,
    private val appStorage: AppStorage,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
) {
    suspend fun performBackup(category: BackupCategory): Result<Unit> {
        Logger.debug("Performing backup for category: $category", context = TAG)

        return when (category) {
            BackupCategory.WALLET -> performWalletBackup()
            BackupCategory.SETTINGS -> performSettingsBackup()
            BackupCategory.WIDGETS -> performWidgetsBackup()
            BackupCategory.METADATA -> performMetadataBackup()
            BackupCategory.BLOCKTANK -> performBlocktankBackup()
            BackupCategory.SLASHTAGS -> performSlashtagsBackup()
            BackupCategory.LDK_ACTIVITY -> performLdkActivityBackup()
            BackupCategory.LIGHTNING_CONNECTIONS -> performLightningConnectionsBackup()
        }
    }

    private suspend fun performWalletBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Wallet backup")
    }

    private suspend fun performSettingsBackup(): Result<Unit> = runCatching {
        val data = settingsStore.data.first()

        val dataBytes = json.encodeToString(data).toByteArray()

        encryptAndUpload(BackupCategory.SETTINGS, dataBytes)
    }

    private suspend fun performWidgetsBackup(): Result<Unit> = runCatching {
        val data = widgetsStore.data.first()

        val dataBytes = json.encodeToString(data).toByteArray()

        encryptAndUpload(BackupCategory.WIDGETS, dataBytes)
    }

    private suspend fun performMetadataBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Metadata backup")
    }

    private suspend fun performBlocktankBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Blocktank backup")
    }

    private suspend fun performSlashtagsBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Slashtags backup")
    }

    private suspend fun performLdkActivityBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("LDK activity backup")
    }

    private suspend fun performLightningConnectionsBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Lightning connections backup")
    }

    private suspend fun encryptAndUpload(
        category: BackupCategory,
        dataBytes: ByteArray,
    ): VssObjectInfo {
        // TODO encrypt data before upload
        val encrypted = dataBytes

        val result = vssClient.putObject(category, encrypted).getOrThrow()

        Logger.info("Backup uploaded for category: $category", context = TAG)
        return result
    }

    suspend fun performSettingsRestore(): Result<Unit> {
        val category = BackupCategory.SETTINGS

        return runCatching {
            val dataBytes = fetchBackupData(category).getOrThrow()

            val restoredSettings = json.decodeFromString<SettingsData>(String(dataBytes))
            settingsStore.update { restoredSettings }

            appStorage.updateBackupStatus(category) {
                it.copy(running = false, synced = System.currentTimeMillis())
            }

            Logger.info("Restore success for: $category", context = TAG)
        }.onFailure { exception ->
            Logger.debug("Restore error for: $category", context = TAG)
        }
    }
    suspend fun performWidgetsRestore(): Result<Unit> {
        val category = BackupCategory.WIDGETS

        return runCatching {
            val dataBytes = fetchBackupData(category).getOrThrow()
            val parsed = json.decodeFromString<WidgetsData>(String(dataBytes))

            widgetsStore.update { parsed }

            appStorage.updateBackupStatus(category) {
                it.copy(running = false, synced = System.currentTimeMillis())
            }

            Logger.info("Restore success for: $category", context = TAG)
        }.onFailure { exception ->
            Logger.debug("Restore error for: $category", context = TAG)
        }
    }

    private suspend fun fetchBackupData(category: BackupCategory): Result<ByteArray> = runCatching {
        val objectInfo = vssClient.getObject(category).getOrThrow()
        objectInfo.data
    }

    /**
     * List all available backup categories
     */
    suspend fun listBackups(): Result<List<VssObjectInfo>> {
        // TODO return a list of BackupCategories enum entries for each category that has a backup
        Logger.debug("Listing all backups", context = TAG)
        return vssClient.listObjects().map { it.objects }
    }

    companion object {
        private const val TAG = "BackupService"
    }
}

sealed class BackupError(message: String) : AppError(message) {
    data class BackupFailure(override val message: String) : BackupError(message)
}
