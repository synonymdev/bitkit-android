package to.bitkit.repositories

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssBackupsClient
import to.bitkit.data.backup.VssObjectInfo
import to.bitkit.di.BgDispatcher
import to.bitkit.di.json
import to.bitkit.ext.formatPlural
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.Toast
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val appStorage: AppStorage,
    private val vssBackupsClient: VssBackupsClient,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + bgDispatcher)
    private val backupJobs = mutableMapOf<BackupCategory, Job>()
    private var periodicCheckJob: Job? = null
    private var isObserving = false
    private var isRestoring = false

    private var lastNotificationTime = 0L

    fun startObservingBackups() {
        if (isObserving) return

        isObserving = true
        observeBackupStatuses()
        startPeriodicBackupFailureCheck()
        Logger.debug("Started observing backup statuses", context = TAG)
    }

    fun stopObservingBackups() {
        if (!isObserving) return

        isObserving = false

        // Cancel all backup jobs
        backupJobs.values.forEach { it.cancel() }
        backupJobs.clear()

        // Cancel periodic check job
        periodicCheckJob?.cancel()
        periodicCheckJob = null

        Logger.debug("Stopped observing backup statuses", context = TAG)
    }

    private fun observeBackupStatuses() {
        BackupCategory.entries.forEach { category ->
            scope.launch {
                appStorage.backupStatuses
                    .map { statuses -> statuses[category] ?: BackupItemStatus() }
                    .distinctUntilChanged { old, new ->
                        // restart scheduling when synced or required timestamps change
                        old.synced == new.synced && old.required == new.required
                    }
                    .collect { status ->
                        if (status.synced < status.required && !status.running && !isRestoring) {
                            scheduleBackup(category)
                        }
                    }
            }
        }
    }

    private fun scheduleBackup(category: BackupCategory) {
        // Cancel existing backup job for this category
        backupJobs[category]?.cancel()

        backupJobs[category] = scope.launch {
            delay(BACKUP_DEBOUNCE)

            // Double-check if backup is still needed
            val status = appStorage.backupStatuses.value[category] ?: BackupItemStatus()
            if (status.synced < status.required && !status.running && !isRestoring) {
                triggerBackup(category)
            }
        }
    }

    private fun startPeriodicBackupFailureCheck() {
        periodicCheckJob = scope.launch {
            while (true) {
                delay(BACKUP_CHECK_INTERVAL)
                checkForFailedBackups()
            }
        }
    }

    private fun checkForFailedBackups() {
        val currentTime = System.currentTimeMillis()

        // find if there are any backup categories that have been failing for more than 30 minutes
        val hasFailedBackups = BackupCategory.entries.any { category ->
            val status = appStorage.backupStatuses.value[category] ?: BackupItemStatus()

            val isPendingAndOverdue = status.synced < status.required &&
                currentTime - status.required > FAILED_BACKUP_CHECK_TIME
            return@any isPendingAndOverdue
        }

        if (hasFailedBackups) {
            showBackupFailureNotification(currentTime)
        }
    }

    private fun showBackupFailureNotification(currentTime: Long) {
        // Throttle notifications to avoid spam
        if (currentTime - lastNotificationTime < FAILED_BACKUP_NOTIFICATION_INTERVAL) return

        lastNotificationTime = currentTime

        scope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.settings__backup__failed_title),
                description = context.getString(R.string.settings__backup__failed_message).formatPlural(
                    mapOf("interval" to (BACKUP_CHECK_INTERVAL / 60000)) // displayed in minutes
                ),
            )
        }
    }

    suspend fun triggerBackup(category: BackupCategory) = withContext(bgDispatcher) {
        Logger.debug("Backup starting for category: $category", context = TAG)

        appStorage.updateBackupStatus(category) {
            it.copy(running = true, required = System.currentTimeMillis())
        }

        try {
            val backupResult = performBackup(category)

            if (backupResult.isSuccess) {
                appStorage.updateBackupStatus(category) {
                    it.copy(
                        running = false,
                        synced = System.currentTimeMillis(),
                    )
                }
                Logger.info("Backup succeeded for category: $category", context = TAG)
            } else {
                throw backupResult.exceptionOrNull() ?: Exception("Unknown backup failure")
            }
        } catch (e: Throwable) {
            appStorage.updateBackupStatus(category) {
                it.copy(running = false)
            }
            Logger.error("Backup failed for category: $category", e = e, context = TAG)
        }
    }

    fun markBackupRequired(category: BackupCategory) {
        scope.launch {
            appStorage.updateBackupStatus(category) {
                it.copy(required = System.currentTimeMillis())
            }
        }
        Logger.debug("Marked backup required for category: $category", context = TAG)
    }

    private suspend fun performBackup(category: BackupCategory): Result<Unit> {
        Logger.debug("Performing backup for category: $category", context = TAG)

        return runCatching {
            val dataBytes = getDataBytes(category)
            encryptAndUpload(category, dataBytes)
        }
    }

    private suspend fun getDataBytes(category: BackupCategory): ByteArray = when (category) {
        BackupCategory.SETTINGS -> {
            val data = settingsStore.data.first()
            json.encodeToString(data).toByteArray()
        }

        BackupCategory.WIDGETS -> {
            val data = widgetsStore.data.first()
            json.encodeToString(data).toByteArray()
        }

        BackupCategory.WALLET -> {
            throw NotImplementedError("Wallet backup not yet implemented")
        }

        BackupCategory.METADATA -> {
            throw NotImplementedError("Metadata backup not yet implemented")
        }

        BackupCategory.BLOCKTANK -> {
            throw NotImplementedError("Blocktank backup not yet implemented")
        }

        BackupCategory.SLASHTAGS -> {
            throw NotImplementedError("Slashtags backup not yet implemented")
        }

        BackupCategory.LDK_ACTIVITY -> {
            throw NotImplementedError("LDK activity backup not yet implemented")
        }

        BackupCategory.LIGHTNING_CONNECTIONS -> {
            throw NotImplementedError("Lightning connections backup not yet implemented")
        }
    }

    private suspend fun encryptAndUpload(category: BackupCategory, dataBytes: ByteArray): VssObjectInfo {
        // TODO encrypt data before upload
        val encrypted = dataBytes

        val result = vssBackupsClient.putObject(category, encrypted).getOrThrow()

        Logger.info("Backup uploaded for category: $category", context = TAG)
        return result
    }


    suspend fun performFullRestoreFromLatestBackup(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Full restore starting", context = TAG)

        isRestoring = true

        return@withContext try {
            performRestore(BackupCategory.SETTINGS) { dataBytes ->
                val parsed = json.decodeFromString<SettingsData>(String(dataBytes))
                settingsStore.update { parsed }
            }
            performRestore(BackupCategory.WIDGETS) { dataBytes ->
                val parsed = json.decodeFromString<WidgetsData>(String(dataBytes))
                widgetsStore.update { parsed }
            }
            // TODO: Add other backup categories as they get implemented:
            // performMetadataRestore()
            // performWalletRestore()
            // performBlocktankRestore()
            // performSlashtagsRestore()
            // performLdkActivityRestore()

            Logger.info("Full restore completed", context = TAG)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            isRestoring = false
        }
    }

    private suspend fun performRestore(
        category: BackupCategory,
        restoreAction: suspend (ByteArray) -> Unit,
    ): Result<Unit> = runCatching {
        val dataBytes = fetchBackupData(category).getOrThrow()

        restoreAction(dataBytes)

        appStorage.updateBackupStatus(category) {
            it.copy(running = false, synced = System.currentTimeMillis())
        }

        Logger.info("Restore success for category: $category", context = TAG)
    }.onFailure { exception ->
        Logger.debug("Restore error for category: $category", context = TAG)
    }

    private suspend fun fetchBackupData(category: BackupCategory): Result<ByteArray> = runCatching {
        val objectInfo = vssBackupsClient.getObject(category).getOrThrow()
        objectInfo.data
    }

    companion object {
        private const val TAG = "BackupRepo"

        private const val BACKUP_DEBOUNCE = 5000L // 5 seconds
        private const val BACKUP_CHECK_INTERVAL = 60 * 1000L // 1 minute
        private const val FAILED_BACKUP_CHECK_TIME = 30 * 60 * 1000L // 30 minutes
        private const val FAILED_BACKUP_NOTIFICATION_INTERVAL = 10 * 60 * 1000L // 10 minutes
    }
}
