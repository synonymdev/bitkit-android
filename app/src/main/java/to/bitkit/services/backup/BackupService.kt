package to.bitkit.services.backup

import kotlinx.coroutines.delay
import to.bitkit.models.BackupCategory
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
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
        // val walletData = walletService.exportData()
        // val encrypted = cryptoService.encrypt(walletData)
        // val uploaded = cloudService.upload(encrypted)
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performSettingsBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performWidgetsBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performMetadataBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performBlocktankBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performSlashtagsBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performLdkActivityBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private suspend fun performLightningConnectionsBackup(): Result<Unit> = runCatching {
        delay(1000)
        throw BackupError.BackupFailure("TODO: backup not implemented")
    }

    private fun uploadBackup(key: String, blob: ByteArray): Result<Unit> {
        return Result.success(Unit)
    }

    companion object {
        private const val TAG = "BackupService"
    }
}

sealed class BackupError(message: String) : AppError(message) {
    data class BackupFailure(override val message: String) : BackupError(message)
}
