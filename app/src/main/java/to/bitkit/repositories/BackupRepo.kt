package to.bitkit.repositories

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.AppStorage
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.formatPlural
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.Toast
import to.bitkit.data.backup.BackupService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val appStorage: AppStorage,
    private val backupService: BackupService,
) {
    private val scope = CoroutineScope(SupervisorJob() + bgDispatcher)
    private val backupJobs = mutableMapOf<BackupCategory, Job>()
    private var periodicCheckJob: Job? = null

    private var lastNotificationTime = 0L

    init {
        startObservingBackupStatuses()
        startPeriodicBackupFailureCheck()
    }

    private fun startObservingBackupStatuses() {
        BackupCategory.entries.forEach { category ->
            scope.launch {
                appStorage.backupStatuses
                    .map { statuses -> statuses[category] ?: BackupItemStatus() }
                    .distinctUntilChanged { old, new ->
                        // restart scheduling when synced or required timestamps change
                        old.synced == new.synced && old.required == new.required
                    }
                    .collect { status ->
                        if (status.synced < status.required && !status.running) {
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
            if (status.synced <= status.required && !status.running) {
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
            val backupResult = backupService.performBackup(category)

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
            Logger.error("Backup failed for category $category", e = e, context = TAG)
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

    companion object {
        private const val TAG = "BackupRepo"

        private const val BACKUP_DEBOUNCE = 5000L // 5 seconds
        private const val BACKUP_CHECK_INTERVAL = 60 * 1000L // 1 minute
        private const val FAILED_BACKUP_CHECK_TIME = 30 * 60 * 1000L // 30 minutes
        private const val FAILED_BACKUP_NOTIFICATION_INTERVAL = 10 * 60 * 1000L // 10 minutes
    }
}
