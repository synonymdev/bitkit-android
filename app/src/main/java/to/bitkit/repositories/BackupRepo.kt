package to.bitkit.repositories

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.resetPin
import to.bitkit.di.BgDispatcher
import to.bitkit.di.json
import to.bitkit.ext.formatPlural
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.Toast
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import uniffi.vss_rust_client_ffi.VssItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val cacheStore: CacheStore,
    private val vssBackupClient: VssBackupClient,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
) {
    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val backupJobs = mutableMapOf<BackupCategory, Job>()
    private val statusObserverJobs = mutableListOf<Job>()
    private val dataListenerJobs = mutableListOf<Job>()
    private var periodicCheckJob: Job? = null
    private var isObserving = false
    private var isRestoring = false

    private var lastNotificationTime = 0L

    fun startObservingBackups() {
        if (isObserving) return

        isObserving = true
        Logger.debug("Start observing backup statuses and data store changes", context = TAG)

        scope.launch { vssBackupClient.setup() }
        startBackupStatusObservers()
        startDataStoreListeners()
        startPeriodicBackupFailureCheck()
    }

    fun stopObservingBackups() {
        if (!isObserving) return

        isObserving = false

        // Cancel all backup jobs
        backupJobs.values.forEach { it.cancel() }
        backupJobs.clear()

        // Cancel backup status observer jobs
        statusObserverJobs.forEach { it.cancel() }
        statusObserverJobs.clear()

        // Cancel data store listener jobs
        dataListenerJobs.forEach { it.cancel() }
        dataListenerJobs.clear()

        // Cancel periodic check job
        periodicCheckJob?.cancel()
        periodicCheckJob = null

        Logger.debug("Stopped observing backup statuses and data store changes", context = TAG)
    }

    private fun startBackupStatusObservers() {
        // Observe backup status changes for each category
        BackupCategory.entries.forEach { category ->
            val job = scope.launch {
                cacheStore.backupStatuses
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
            statusObserverJobs.add(job)
        }

        Logger.debug("Started ${statusObserverJobs.size} backup status observers", context = TAG)
    }

    private fun startDataStoreListeners() {
        val settingsJob = scope.launch {
            settingsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (!isRestoring) {
                        markBackupRequired(BackupCategory.SETTINGS)
                    }
                }
        }
        dataListenerJobs.add(settingsJob)

        val widgetsJob = scope.launch {
            widgetsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (!isRestoring) {
                        markBackupRequired(BackupCategory.WIDGETS)
                    }
                }
        }
        dataListenerJobs.add(widgetsJob)

        Logger.debug("Started ${dataListenerJobs.size} data store listeners", context = TAG)
    }

    private fun startPeriodicBackupFailureCheck() {
        periodicCheckJob = scope.launch {
            while (true) {
                delay(BACKUP_CHECK_INTERVAL)
                checkForFailedBackups()
            }
        }
    }

    private fun markBackupRequired(category: BackupCategory) {
        scope.launch {
            cacheStore.updateBackupStatus(category) {
                it.copy(required = System.currentTimeMillis())
            }
            Logger.debug("Marked backup required for: '$category'", context = TAG)
        }
    }

    private fun scheduleBackup(category: BackupCategory) {
        // Cancel existing backup job for this category
        backupJobs[category]?.cancel()

        Logger.debug("Scheduling backup for: '$category'", context = TAG)

        backupJobs[category] = scope.launch {
            delay(BACKUP_DEBOUNCE)

            // Double-check if backup is still needed
            val status = cacheStore.backupStatuses.first()[category] ?: BackupItemStatus()
            if (status.synced < status.required && !status.running && !isRestoring) {
                triggerBackup(category)
            }
        }
    }

    private fun checkForFailedBackups() {
        val currentTime = System.currentTimeMillis()

        // find if there are any backup categories that have been failing for more than 30 minutes
        scope.launch {
            val backupStatuses = cacheStore.backupStatuses.first()
            val hasFailedBackups = BackupCategory.entries.any { category ->
                val status = backupStatuses[category] ?: BackupItemStatus()

                val isPendingAndOverdue = status.synced < status.required &&
                    currentTime - status.required > FAILED_BACKUP_CHECK_TIME
                return@any isPendingAndOverdue
            }

            if (hasFailedBackups) {
                showBackupFailureNotification(currentTime)
            }
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
        Logger.debug("Backup starting for: '$category'", context = TAG)

        cacheStore.updateBackupStatus(category) {
            it.copy(running = true, required = System.currentTimeMillis())
        }

        encryptAndUpload(category)
            .onSuccess {
                cacheStore.updateBackupStatus(category) {
                    it.copy(
                        running = false,
                        synced = System.currentTimeMillis(),
                    )
                }
                Logger.info("Backup succeeded for: '$category'", context = TAG)
            }
            .onFailure { e ->
                cacheStore.updateBackupStatus(category) {
                    it.copy(running = false)
                }
                Logger.error("Backup failed for: '$category'", e = e, context = TAG)
            }
    }

    private suspend fun encryptAndUpload(category: BackupCategory): Result<VssItem> = runCatching {
        val dataBytes = getBackupDataBytes(category)

        // TODO encrypt data before upload
        val encrypted = dataBytes

        return vssBackupClient.putObject(category.name, encrypted)
    }

    private suspend fun getBackupDataBytes(category: BackupCategory): ByteArray = when (category) {
        BackupCategory.SETTINGS -> {
            val data = settingsStore.data.first().resetPin()
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

    suspend fun performFullRestoreFromLatestBackup(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("Full restore starting", context = TAG)

        isRestoring = true

        return@withContext try {
            performRestore(BackupCategory.SETTINGS) { dataBytes ->
                val parsed = json.decodeFromString<SettingsData>(String(dataBytes)).resetPin()
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
            Logger.warn("Full restore error", e = e, context = TAG)
            Result.failure(e)
        } finally {
            isRestoring = false
        }
    }

    private suspend fun performRestore(
        category: BackupCategory,
        restoreAction: suspend (ByteArray) -> Unit,
    ): Result<Unit> = runCatching {
        vssBackupClient.getObject(category.name).map { it?.value }
            .onSuccess { dataBytes ->
                if (dataBytes == null) {
                    Logger.warn("Restore null for: '$category'", context = TAG)
                } else {
                    restoreAction(dataBytes)
                    Logger.info("Restore success for: '$category'", context = TAG)
                }
            }
            .onFailure {
                Logger.debug("Restore error for: '$category'", context = TAG)
            }

        cacheStore.updateBackupStatus(category) {
            it.copy(running = false, synced = System.currentTimeMillis())
        }
    }

    companion object {
        private const val TAG = "BackupRepo"

        private const val BACKUP_DEBOUNCE = 5000L // 5 seconds
        private const val BACKUP_CHECK_INTERVAL = 60 * 1000L // 1 minute
        private const val FAILED_BACKUP_CHECK_TIME = 30 * 60 * 1000L // 30 minutes
        private const val FAILED_BACKUP_NOTIFICATION_INTERVAL = 10 * 60 * 1000L // 10 minutes
    }
}
