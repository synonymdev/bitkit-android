package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.migrateBackupActivitiesJson
import com.synonym.bitkitcore.migrateBackupActivityTagsJson
import com.synonym.bitkitcore.migrateBackupPreActivityMetadataJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import to.bitkit.R
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.resetPin
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.ext.formatPlural
import to.bitkit.ext.nowMillis
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.models.MetadataBackupV1
import to.bitkit.models.SettingsBackupV1
import to.bitkit.models.Toast
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WidgetsBackupV1
import to.bitkit.services.LightningService
import to.bitkit.services.PaykitSdkService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.jsonLogOf
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Manages backup & restore of wallet metadata to a remote VSS server.
 *
 * **Backup State Machine:**
 * ```
 *  Idle State:          running=false, synced≥required
 *       ↓ (data changes → markBackupRequired())
 *   Pending State:       running=false, synced<required
 *       ↓ (scheduleBackup())
 *   Running State:       running=true,  synced<required
 *       ↓ (triggerBackup() succeeds)
 *   Idle State:          running=false, synced≥required
 * ```
 */
@Suppress("LongParameterList", "TooManyFunctions")
@OptIn(ExperimentalTime::class)
@Singleton
class BackupRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val cacheStore: CacheStore,
    private val vssBackupClient: VssBackupClient,
    private val vssBackupClientLdk: VssBackupClientLdk,
    private val settingsStore: SettingsStore,
    private val widgetsStore: WidgetsStore,
    private val watchOnlyAccountStore: WatchOnlyAccountStore,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val pubkyRepo: PubkyRepo,
    private val paykitSdkService: PaykitSdkService,
    private val privatePaykitRepo: Provider<PrivatePaykitRepo>,
    private val privatePaykitAddressReservationRepo: Provider<PrivatePaykitAddressReservationRepo>,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val lightningService: LightningService,
    private val clock: Clock,
    private val db: AppDb,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val backupJobs = mutableMapOf<BackupCategory, Job>()
    private val statusObserverJobs = mutableListOf<Job>()
    private val dataListenerJobs = mutableListOf<Job>()
    private var periodicCheckJob: Job? = null

    private val runningBackups = ConcurrentHashMap.newKeySet<BackupCategory>() // Tracks active jobs since app start
    private val failedBackupRequired = ConcurrentHashMap<BackupCategory, Long>()

    private var isObserving = false
    private var lastNotificationTime = 0L

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _isWiping = MutableStateFlow(false)

    fun reset() {
        stopObservingBackups()
        vssBackupClient.reset()
        vssBackupClientLdk.reset()
    }

    fun setWiping(isWiping: Boolean) = _isWiping.update { isWiping }
    private fun currentTimeMillis(): Long = nowMillis(clock)
    private fun shouldSkipBackup(): Boolean = _isRestoring.value || _isWiping.value
    private fun BackupItemStatus.shouldBackup(category: BackupCategory) =
        this.isRequired &&
            !this.running &&
            !shouldSkipBackup() &&
            failedBackupRequired[category] != this.required

    fun startObservingBackups() {
        if (isObserving) return

        isObserving = true
        Logger.debug("Start observing backup statuses and data store changes", context = TAG)

        scope.launch {
            vssBackupClient.setupWithRetry {
                onSuccess = { attempt ->
                    Logger.debug("VSS client setup succeeded on attempt $attempt", context = TAG)
                }
                onRetry = { attempt, maxAttempts, delayMs ->
                    Logger.debug(
                        "VSS client setup deferred, retrying in ${delayMs}ms (attempt $attempt/$maxAttempts)",
                        context = TAG,
                    )
                }
                onExhausted = { maxAttempts ->
                    Logger.warn("VSS client setup failed after $maxAttempts attempts", context = TAG)
                }
            }.onSuccess {
                scope.launch { vssBackupClientLdk.setup() }
            }
        }

        scope.launch {
            BackupCategory.entries.forEach { category ->
                if (category !in runningBackups) {
                    cacheStore.updateBackupStatus(category) { status ->
                        if (status.running) {
                            Logger.debug("Clearing stale running flag for: '$category'", context = TAG)
                            status.copy(running = false)
                        } else {
                            status
                        }
                    }
                }
            }
        }

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
                        old.synced == new.synced &&
                            old.required == new.required &&
                            old.running == new.running
                    }
                    .collect { status ->
                        if (status.shouldBackup(category)) {
                            scheduleBackup(category)
                        }
                    }
            }
            statusObserverJobs.add(job)
        }

        Logger.debug("Started ${statusObserverJobs.size} backup status observers", context = TAG)
    }

    @Suppress("LongMethod")
    private fun startDataStoreListeners() {
        val settingsJob = scope.launch {
            settingsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.SETTINGS)
                }
        }
        dataListenerJobs.add(settingsJob)

        val widgetsJob = scope.launch {
            widgetsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.WIDGETS)
                }
        }
        dataListenerJobs.add(widgetsJob)

        // WALLET - Observe transfers
        val transfersJob = scope.launch {
            db.transferDao().observeAll()
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.WALLET)
                }
        }
        dataListenerJobs.add(transfersJob)

        val watchOnlyAccountsJob = scope.launch {
            watchOnlyAccountStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.WALLET)
                }
        }
        dataListenerJobs.add(watchOnlyAccountsJob)

        // METADATA - Observe entire CacheStore excluding backup statuses
        val cacheMetadataJob = scope.launch {
            cacheStore.data
                .map { it.copy(backupStatuses = mapOf()) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.METADATA)
                }
        }
        dataListenerJobs.add(cacheMetadataJob)

        // METADATA - Observe pre-activity metadata changes
        val preActivityMetadataJob = scope.launch {
            preActivityMetadataRepo.preActivityMetadataChanged
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(BackupCategory.METADATA)
                }
        }
        dataListenerJobs.add(preActivityMetadataJob)

        dataListenerJobs.add(observeBackupChanges(pubkyRepo.backupStateVersion, BackupCategory.METADATA))
        dataListenerJobs.add(observeBackupChanges(privatePaykitRepo.get().backupStateVersion, BackupCategory.WALLET))
        dataListenerJobs.add(observeBackupChanges(paykitSdkService.backupStateVersion, BackupCategory.WALLET))
        dataListenerJobs.add(
            observeBackupChanges(
                privatePaykitAddressReservationRepo.get().backupStateVersion,
                BackupCategory.WALLET,
            )
        )

        // BLOCKTANK - Observe blocktank state changes (orders, cjitEntries, info)
        dataListenerJobs.add(observeBackupChanges(blocktankRepo.blocktankState, BackupCategory.BLOCKTANK))

        // ACTIVITY - Observe activity changes
        dataListenerJobs.add(observeBackupChanges(activityRepo.activitiesChanged, BackupCategory.ACTIVITY))

        // LIGHTNING_CONNECTIONS - Only display sync timestamp, ldk-node manages its own backups
        @OptIn(FlowPreview::class)
        val lightningConnectionsJob = scope.launch {
            lightningService.syncStatusChanged
                .debounce(SYNC_STATUS_DEBOUNCE)
                .collect {
                    val lastSync = lightningService.status?.latestLightningWalletSyncTimestamp?.toLong()
                        ?.let { it * 1000 } // Convert seconds to millis
                        ?: return@collect
                    if (shouldSkipBackup()) return@collect
                    cacheStore.updateBackupStatus(BackupCategory.LIGHTNING_CONNECTIONS) {
                        it.copy(required = lastSync, synced = lastSync, running = false)
                    }
                }
        }
        dataListenerJobs.add(lightningConnectionsJob)

        Logger.debug("Started ${dataListenerJobs.size} data store listeners", context = TAG)
    }

    private fun observeBackupChanges(flow: Flow<*>, category: BackupCategory): Job =
        scope.launch {
            flow.drop(1).collect {
                if (shouldSkipBackup()) return@collect
                markBackupRequired(category)
            }
        }

    private fun startPeriodicBackupFailureCheck() {
        periodicCheckJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(BACKUP_CHECK_INTERVAL)
                checkForFailedBackups()
            }
        }
    }

    private fun markBackupRequired(category: BackupCategory) {
        scope.launch {
            failedBackupRequired -= category
            cacheStore.updateBackupStatus(category) {
                it.copy(required = currentTimeMillis())
            }
            Logger.verbose("Marked backup required for: '$category'", context = TAG)
        }
    }

    private fun scheduleBackup(category: BackupCategory) {
        backupJobs[category]?.cancel()

        Logger.verbose("Scheduling backup for: '$category'", context = TAG)

        backupJobs[category] = scope.launch {
            runningBackups += category
            cacheStore.updateBackupStatus(category) {
                it.copy(running = true)
            }

            delay(BACKUP_DEBOUNCE)

            val status = cacheStore.backupStatuses.first()[category] ?: BackupItemStatus()
            if (status.isRequired && !shouldSkipBackup()) {
                triggerBackup(category)
            } else {
                Logger.debug("Backup no longer needed for: '$category'", context = TAG)
                runningBackups -= category
                cacheStore.updateBackupStatus(category) {
                    it.copy(running = false)
                }
            }
        }.also { job ->
            job.invokeOnCompletion { exception ->
                if (exception != null) {
                    Logger.debug("Backup job cancelled for: '$category'", context = TAG)
                    scope.launch {
                        runningBackups -= category
                        cacheStore.updateBackupStatus(category) {
                            it.copy(running = false)
                        }
                    }
                }
            }
        }
    }

    private fun checkForFailedBackups() {
        val currentTime = currentTimeMillis()

        // find if there are any backup categories that have been failing for more than 30 minutes
        scope.launch {
            val backupStatuses = cacheStore.backupStatuses.first()
            val hasFailedBackups = BackupCategory.entries.any { category ->
                val status = backupStatuses[category] ?: BackupItemStatus()

                val isPendingAndOverdue = status.isRequired &&
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
                    mapOf("interval" to (BACKUP_CHECK_INTERVAL / MINUTE_IN_MS))
                ),
            )
        }
    }

    suspend fun triggerBackup(category: BackupCategory) = withContext(ioDispatcher) {
        Logger.debug("Backup starting for: '$category'", context = TAG)

        val backupRequired = currentTimeMillis()
        runningBackups += category
        failedBackupRequired -= category
        cacheStore.updateBackupStatus(category) {
            it.copy(running = true, required = backupRequired)
        }

        vssBackupClient.putObject(key = category.name, data = getBackupDataBytes(category))
            .onSuccess {
                runningBackups -= category
                failedBackupRequired -= category
                cacheStore.updateBackupStatus(category) {
                    it.copy(
                        running = false,
                        synced = currentTimeMillis(),
                    )
                }
                Logger.info("Backup succeeded for: '$category'", context = TAG)
            }
            .onFailure { e ->
                runningBackups -= category
                cacheStore.updateBackupStatus(category) {
                    if (it.required == backupRequired) {
                        failedBackupRequired[category] = backupRequired
                    } else {
                        failedBackupRequired -= category
                    }
                    it.copy(running = false)
                }
                Logger.error("Backup failed for: '$category'", e, context = TAG)
            }
    }

    private suspend fun getBackupDataBytes(category: BackupCategory): ByteArray = when (category) {
        BackupCategory.SETTINGS -> {
            val data = settingsStore.data.first().resetPin()
            val payload = SettingsBackupV1(
                createdAt = currentTimeMillis(),
                settings = data,
            )
            json.encodeToString(payload).toByteArray()
        }

        BackupCategory.WIDGETS -> {
            val data = widgetsStore.data.first()
            val payload = WidgetsBackupV1(
                createdAt = currentTimeMillis(),
                widgets = data,
            )
            json.encodeToString(payload).toByteArray()
        }

        BackupCategory.WALLET -> getWalletBackupDataBytes()

        BackupCategory.METADATA -> getMetadataBackupDataBytes()

        BackupCategory.BLOCKTANK -> {
            val blocktankState = blocktankRepo.blocktankState.first()

            val payload = BlocktankBackupV1(
                createdAt = currentTimeMillis(),
                orders = blocktankState.orders,
                cjitEntries = blocktankState.cjitEntries,
                info = blocktankState.info,
            )

            json.encodeToString(payload).toByteArray()
        }

        BackupCategory.ACTIVITY -> {
            val activities = activityRepo.getActivities().getOrDefault(emptyList())
            val closedChannels = activityRepo.getClosedChannels().getOrDefault(emptyList())
            val activityTags = activityRepo.getAllActivitiesTags().getOrDefault(emptyList())

            val payload = ActivityBackupV1(
                createdAt = currentTimeMillis(),
                activities = activities,
                activityTags = activityTags,
                closedChannels = closedChannels,
            )

            json.encodeToString(payload).toByteArray()
        }

        BackupCategory.LIGHTNING_CONNECTIONS -> throw NotImplementedError("LIGHTNING backup is managed by ldk-node")
    }

    private suspend fun getMetadataBackupDataBytes(): ByteArray = withContext(ioDispatcher) {
        val preActivityMetadata = preActivityMetadataRepo.getAllPreActivityMetadata().getOrDefault(emptyList())
        val cacheData = cacheStore.data.first()
        val pubkySession = pubkyRepo.snapshotSessionBackupState().getOrDefault(null)
        val pubkyContactProfileOverrides = pubkyRepo.snapshotContactProfileOverrides().getOrDefault(null)

        val payload = MetadataBackupV1(
            createdAt = currentTimeMillis(),
            tagMetadata = preActivityMetadata,
            cache = cacheData,
            pubkySession = pubkySession,
            pubkyContactProfileOverrides = pubkyContactProfileOverrides,
        )

        json.encodeToString(payload).toByteArray()
    }

    private suspend fun getWalletBackupDataBytes(): ByteArray {
        val transfers = db.transferDao().getAll()
        val privateReservations = privatePaykitAddressReservationRepo.get().backupSnapshot()
            .onFailure {
                Logger.warn("Failed to snapshot private Paykit reservations", it, context = TAG)
            }
            .getOrThrow()
        val paykitSdkBackupState = privatePaykitRepo.get().backupSnapshot()
            .onFailure {
                Logger.warn("Failed to snapshot Paykit SDK state", it, context = TAG)
            }
            .getOrThrow()

        val watchOnlyAccountSnapshot = watchOnlyAccountStore.backupSnapshot()
        val payload = WalletBackupV1(
            createdAt = currentTimeMillis(),
            transfers = transfers,
            privatePaykitHighestReservedReceiveIndexByAddressType = privateReservations,
            paykitSdkBackupState = paykitSdkBackupState,
            watchOnlyAccounts = watchOnlyAccountSnapshot.accounts,
            watchOnlyAccountAllocationState = watchOnlyAccountSnapshot.allocationState,
        )

        return json.encodeToString(payload).toByteArray()
    }

    @Suppress("LongMethod")
    suspend fun performFullRestoreFromLatestBackup(
        onCacheRestored: suspend () -> Unit = {},
    ): Result<Unit> = withContext(ioDispatcher) {
        Logger.debug("Full restore starting", context = TAG)

        _isRestoring.update { true }

        val result = runCatching {
            performRestore(BackupCategory.METADATA) { dataBytes ->
                val migrated = migrateCoreOwnedBackupFields(
                    String(dataBytes),
                    mapOf("tagMetadata" to ::migrateBackupPreActivityMetadataJson),
                )
                val parsed = json.decodeFromString<MetadataBackupV1>(migrated)
                val cleanCache = parsed.cache.resetBip21() // Force address rotation
                cacheStore.update { cleanCache }
                Logger.debug("Restored caches: ${jsonLogOf(parsed.cache.copy(cachedRates = emptyList()))}", TAG)
                onCacheRestored()
                preActivityMetadataRepo.upsertPreActivityMetadata(parsed.tagMetadata).getOrNull()
                pubkyRepo.restoreSessionBackupState(parsed.pubkySession)
                    .onFailure {
                        Logger.warn("Failed to restore pubky session backup state", it, context = TAG)
                    }
                pubkyRepo.restoreContactProfileOverrides(parsed.pubkyContactProfileOverrides)
                    .onFailure {
                        Logger.warn("Failed to restore pubky contact profile overrides", it, context = TAG)
                    }
                Logger.debug("Restored ${parsed.tagMetadata.size} pre-activity metadata", TAG)
                parsed.createdAt
            }
            performRestore(BackupCategory.SETTINGS) { dataBytes ->
                val parsed = json.decodeFromString<SettingsBackupV1>(String(dataBytes))
                settingsStore.restoreFromBackup(parsed)
                parsed.createdAt
            }
            performRestore(BackupCategory.WIDGETS) { dataBytes ->
                val parsed = json.decodeFromString<WidgetsBackupV1>(String(dataBytes))
                widgetsStore.restoreFromBackup(parsed)
                parsed.createdAt
            }
            performRestore(BackupCategory.WALLET) { dataBytes ->
                restoreWalletBackup(dataBytes)
            }.getOrThrow()
            performRestore(BackupCategory.BLOCKTANK) { dataBytes ->
                val parsed = json.decodeFromString<BlocktankBackupV1>(String(dataBytes))
                blocktankRepo.restoreFromBackup(parsed)
                parsed.createdAt
            }
            performRestore(BackupCategory.ACTIVITY) { dataBytes ->
                val migrated = migrateCoreOwnedBackupFields(
                    String(dataBytes),
                    mapOf(
                        "activities" to ::migrateBackupActivitiesJson,
                        "activityTags" to ::migrateBackupActivityTagsJson,
                    ),
                )
                val parsed = json.decodeFromString<ActivityBackupV1>(migrated)
                activityRepo.restoreFromBackup(parsed)
                parsed.createdAt
            }

            Logger.info("Full restore success", context = TAG)
        }.onSuccess {
            settingsStore.update { it.copy(backupVerified = true) }
        }.onFailure { e ->
            Logger.warn("Full restore error", e, context = TAG)
        }

        _isRestoring.update { false }

        return@withContext result
    }

    private suspend fun restoreWalletBackup(dataBytes: ByteArray): Long {
        val parsed = json.decodeFromString<WalletBackupV1>(String(dataBytes))
        db.transferDao().upsert(parsed.transfers)
        watchOnlyAccountStore.restore(
            parsed.watchOnlyAccounts.orEmpty(),
            parsed.watchOnlyAccountAllocationState,
        )
        lightningService.reconcileWatchOnlyAccounts(parsed.watchOnlyAccounts.orEmpty())
        if (!parsed.privatePaykitHighestReservedReceiveIndexByAddressType.isNullOrEmpty()) {
            cacheStore.update { it.copy(onchainAddress = "", bip21 = "") }
        }
        val addressReservationRepo = privatePaykitAddressReservationRepo.get()
        addressReservationRepo.restoreBackup(parsed.privatePaykitHighestReservedReceiveIndexByAddressType).getOrThrow()
        val privateRepo = privatePaykitRepo.get()
        privateRepo.restoreBackup(parsed.paykitSdkBackupState).onFailure {
            Logger.warn("Failed to restore Paykit SDK backup state", it, context = TAG)
        }
        addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
        Logger.debug("Restored ${parsed.transfers.size} transfers", context = TAG)
        return parsed.createdAt
    }

    suspend fun getLatestBackupTime(): ULong? = withContext(ioDispatcher) {
        runCatching {
            withTimeout(VSS_TIMESTAMP_TIMEOUT) {
                vssBackupClient.setup().getOrThrow()
                coroutineScope {
                    BackupCategory.entries
                        .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
                        .map { category -> async { getRemoteBackupTimestamp(category) } }
                        .mapNotNull { it.await() }
                        .filter { it > 0uL }
                        .maxOrNull()
                }
            }
        }.onFailure { e ->
            Logger.warn("Failed to get VSS backup timestamp: $e", context = TAG)
        }.getOrNull()
    }

    private suspend fun getRemoteBackupTimestamp(category: BackupCategory): ULong? {
        val item = vssBackupClient.getObject(category.name).getOrNull() ?: return null
        val data = item.value ?: return null

        @Serializable
        data class BackupWithCreatedAt(val createdAt: Long? = null)

        return runCatching {
            val millis = json.decodeFromString<BackupWithCreatedAt>(String(data)).createdAt ?: return@runCatching null
            (millis / 1000).toULong()
        }.getOrNull()
    }

    fun scheduleFullBackup() {
        Logger.debug("Scheduling backups for all categories", context = TAG)
        BackupCategory.entries
            .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
            .forEach {
                scheduleBackup(it)
            }
    }

    /**
     * Fill in wallet ids that predate wallet-scoped activity data before a backup
     * envelope is decoded. Each Core-owned array field is handed to the matching
     * Core migration helper as raw JSON, so the app never edits Core model JSON
     * itself. Records that already carry a wallet id are left unchanged, so this
     * is safe to run on current backups too.
     */
    private fun migrateCoreOwnedBackupFields(
        raw: String,
        fieldMigrations: Map<String, (String) -> String>,
    ): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val patched = root.toMutableMap()
        for ((field, migrate) in fieldMigrations) {
            val element = root[field]
            if (element is JsonArray) {
                patched[field] = json.parseToJsonElement(migrate(element.toString()))
            }
        }
        return JsonObject(patched).toString()
    }

    private suspend fun performRestore(
        category: BackupCategory,
        restoreAction: suspend (dataBytes: ByteArray) -> Long,
    ): Result<Unit> = runCatching {
        var createdAtTimestamp = currentTimeMillis()

        vssBackupClient.getObject(category.name).map { it?.value }
            .onSuccess { dataBytes ->
                if (dataBytes == null) {
                    Logger.warn("Restore null for: '$category'", context = TAG)
                } else {
                    createdAtTimestamp = restoreAction(dataBytes)
                    Logger.info("Restore success for: '$category'", context = TAG)
                }
            }
            .onFailure {
                Logger.debug("Restore error for: '$category'", context = TAG)
            }

        cacheStore.updateBackupStatus(category) {
            it.copy(running = false, synced = createdAtTimestamp, required = createdAtTimestamp)
        }
    }

    companion object {
        private const val TAG = "BackupRepo"

        private const val MINUTE_IN_MS = 60_000
        private const val BACKUP_DEBOUNCE = 5000L // 5 seconds
        private const val BACKUP_CHECK_INTERVAL = 60 * 1000L // 1 minute
        private const val FAILED_BACKUP_CHECK_TIME = 30 * 60 * 1000L // 30 minutes
        private const val FAILED_BACKUP_NOTIFICATION_INTERVAL = 10 * 60 * 1000L // 10 minutes
        private const val SYNC_STATUS_DEBOUNCE = 500L // 500ms debounce for sync status updates
        private val VSS_TIMESTAMP_TIMEOUT = 60.seconds
    }
}
