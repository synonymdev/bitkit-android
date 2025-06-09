package to.bitkit.data.backup

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import to.bitkit.di.json
import to.bitkit.models.BackupCategory
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
    private val vssClient: VssBackupClient,
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
        // TODO: Get actual settings data
        val settingsData = mapOf(
            "placeholder" to "settings_data",
            "timestamp" to System.currentTimeMillis().toString(),
        )

        val dataBytes = json.encodeToString(settingsData).toByteArray()

        encryptAndUpload(BackupCategory.SETTINGS, dataBytes)
    }

    private suspend fun performWidgetsBackup(): Result<Unit> = runCatching {
        delay(1000)
        TODO("Widgets backup")
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

    /**
     * Restore backup data for a specific category
     */
    suspend fun restoreBackup(category: BackupCategory): Result<ByteArray> {
        Logger.debug("Restoring backup for category: $category", context = TAG)

        return vssClient.getObject(category).map { it.data }
    }

    /**
     * Delete backup data for a specific category
     */
    suspend fun deleteBackup(category: BackupCategory): Result<Unit> {
        Logger.debug("Deleting backup for category: $category", context = TAG)
        return vssClient.deleteObject(category)
    }

    /**
     * List all available backup categories
     */
    suspend fun listBackups(): Result<List<VssObjectInfo>> {
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
