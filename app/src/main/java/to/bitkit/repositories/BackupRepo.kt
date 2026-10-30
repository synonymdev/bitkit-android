package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.UsdtException
import com.synonym.vssclient.VssException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.bitkit.R
import to.bitkit.async.appScope
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.UsdtRecoveryBackupStore
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.entities.TransferEntity
import to.bitkit.data.hwWalletNames
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.hasPendingWalletRestore
import to.bitkit.data.resetPin
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.ext.formatPlural
import to.bitkit.ext.isPaykitTemporarilyUnavailable
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.ActiveOnchainAttemptBackup
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.models.MetadataBackupV1
import to.bitkit.models.PaykitAllowanceStateBackup
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.SettingsBackupV1
import to.bitkit.models.Toast
import to.bitkit.models.WalletBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.models.WidgetsBackupV1
import to.bitkit.models.toLdkNetwork
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.services.PaykitSdkService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.NetworkValidationHelper
import to.bitkit.utils.jsonLogOf
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
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
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo,
    private val hwWalletStore: HwWalletStore,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
    private val coreService: CoreService,
    private val pubkyRepo: PubkyRepo,
    private val paykitSdkService: PaykitSdkService,
    private val privatePaykitRepo: Provider<PrivatePaykitRepo>,
    private val privatePaykitAddressReservationRepo: Provider<PrivatePaykitAddressReservationRepo>,
    private val paykitPaymentProofRepo: Provider<PaykitPaymentProofRepo>,
    private val paykitUsdtPaymentRepo: Provider<PaykitUsdtPaymentRepo>,
    private val usdtRepo: Provider<UsdtRepo>,
    private val paykitPaymentProofStore: PaykitPaymentProofStore,
    private val paykitPaymentRequestRepo: Provider<PaykitPaymentRequestRepo>,
    private val paykitPresentationStore: PaykitPaymentRequestPresentationStore,
    private val paykitAllowanceStore: PaykitAllowanceStore,
    private val keychain: Keychain,
    private val onchainSendAttemptStore: OnchainSendAttemptStore,
    private val lightningRepo: Provider<LightningRepo>,
    private val vssStoreIdProvider: VssStoreIdProvider,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val lightningService: LightningService,
    private val clock: Clock,
    private val db: AppDb,
    private val connectionRecovery: WalletConnectionRecovery,
) {
    private val walletBackupMutex = Mutex()
    private val usdtBackupMutex = Mutex()
    private val usdtRecoveryBackup = UsdtRecoveryBackupStore(vssBackupClient)
    private val scope = appScope(ioDispatcher, TAG)

    private val lifecycleLock = Any()
    private val resetMutex = Mutex()
    private var generation = 0L
    private var resetting = false

    // Reset owns all pending work; stopping observation only cancels its child scope.
    // Retiring observation parents remain attached until their cancellation cleanup finishes.
    private var pendingScope = newBackupScope(scope)
    private var observation: Observation? = null
    private val retiringObservations = mutableSetOf<Job>()
    private val backupJobs = mutableMapOf<BackupCategory, Job>()
    private val explicitBackups = mutableSetOf<BackupCategory>()
    private val categoryMutexes = BackupCategory.entries.associateWith { Mutex() }

    private val runningBackups = ConcurrentHashMap.newKeySet<BackupCategory>() // Tracks active jobs since app start
    private val failedBackupRequired = ConcurrentHashMap<BackupCategory, Long>()
    private val temporaryFailureRetries = ConcurrentHashMap<BackupCategory, Int>()
    private val temporaryFailureSince = ConcurrentHashMap<BackupCategory, Long>()
    private var lastNotificationTime = 0L

    private class Observation(val scope: CoroutineScope, val generation: Long) {
        val ready = CompletableDeferred<Unit>()
    }

    private fun newBackupScope(parent: CoroutineScope) =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _isWiping = MutableStateFlow(false)
    val isWiping: StateFlow<Boolean> = _isWiping.asStateFlow()

    private val restorePendingUntil = MutableStateFlow(0L)

    suspend fun reset() = withContext(ioDispatcher) {
        resetMutex.withLock {
            val pending = synchronized(lifecycleLock) {
                resetting = true
                observation = null
                pendingScope.coroutineContext[Job]
            }
            withContext(NonCancellable) {
                try {
                    pending?.cancel()
                    pending?.join()
                    synchronized(lifecycleLock) { generation += 1 }
                    vssBackupClient.reset()
                    vssBackupClientLdk.reset()
                    synchronized(lifecycleLock) {
                        backupJobs.clear()
                        explicitBackups.clear()
                        failedBackupRequired.clear()
                        temporaryFailureRetries.clear()
                        temporaryFailureSince.clear()
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        pendingScope = newBackupScope(scope)
                        resetting = false
                    }
                }
            }
        }
    }

    fun setWiping(isWiping: Boolean) = _isWiping.update { isWiping }

    /**
     * Holds ordinary uploads from the moment a restore is announced, closing the window between the
     * restore flow starting and [performFullRestoreFromLatestBackup] raising [_isRestoring]: the node
     * starts and syncs while the backup is still being read, and the resulting activity upload would
     * replace the stored envelope with the fresh wallet's state before it is read.
     *
     * The gate cannot suppress uploads indefinitely: it expires after [RESTORE_PENDING_TIMEOUT_MS], it is
     * held in memory only so a process death clears it, and it never blocks an explicit
     * [triggerBackup], including the migration rewrite a restore ends with.
     */
    fun setRestorePending(isPending: Boolean) {
        restorePendingUntil.update { if (isPending) currentTimeMillis() + RESTORE_PENDING_TIMEOUT_MS else 0L }
        Logger.debug("Set restore pending to '$isPending'", context = TAG)
    }

    suspend fun hasPendingWalletRestore(): Boolean = withContext(ioDispatcher) {
        keychain.hasPendingWalletRestore()
    }

    private fun currentTimeMillis(): Long = nowMillis(clock)
    private fun isRestorePending(): Boolean = currentTimeMillis() < restorePendingUntil.value
    private fun isUsdtRestorePending(): Boolean = keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name)
    private fun shouldSkipBackup(
        category: BackupCategory? = null,
    ): Boolean = if (category == BackupCategory.USDT_RECOVERY) {
        _isWiping.value || isUsdtRestorePending()
    } else {
        _isRestoring.value || _isWiping.value || isRestorePending() ||
            ((category == null || category == BackupCategory.WALLET) && isUsdtRestorePending())
    }
    private fun BackupItemStatus.shouldBackup(category: BackupCategory) =
        this.isRequired &&
            !this.running &&
            !shouldSkipBackup(category) &&
            failedBackupRequired[category] != this.required

    fun startObservingBackups() {
        val startup = synchronized(lifecycleLock) {
            if (observation != null || resetting || _isWiping.value) return
            val previous = retiringObservations.toList()
            val owner = Observation(newBackupScope(pendingScope), generation)
            observation = owner
            val parent = checkNotNull(owner.scope.coroutineContext[Job])
            retiringObservations.add(parent)
            parent.invokeOnCompletion { synchronized(lifecycleLock) { retiringObservations.remove(parent) } }
            owner.scope.launch(start = CoroutineStart.LAZY) {
                // Record new changes during the drain; only uploads and client setup must wait.
                startDataStoreListeners(owner)
                previous.joinAll()
                currentCoroutineContext().ensureActive()
                owner.ready.complete(Unit)
                startObservation(owner)
            }
        }
        startup.start()
    }

    private fun startObservation(owner: Observation) {
        Logger.debug("Start observing backup statuses and data store changes", context = TAG)
        owner.scope.launch {
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
                currentCoroutineContext().ensureActive()
                vssBackupClientLdk.setup()
            }
        }

        owner.scope.launch {
            BackupCategory.entries.forEach { category ->
                if (category !in runningBackups) {
                    updateBackupStatus(category, owner.generation) { status ->
                        if (status.running && category !in runningBackups) {
                            Logger.debug("Clearing stale running flag for: '$category'", context = TAG)
                            status.copy(running = false)
                        } else {
                            status
                        }
                    }
                }
            }
        }

        startBackupStatusObservers(owner)
        startPeriodicBackupFailureCheck(owner)
    }

    fun stopObservingBackups() {
        val parent = synchronized(lifecycleLock) {
            observation?.scope?.coroutineContext?.get(Job).also { observation = null }
        }
        parent?.cancel()
        Logger.debug("Stopped observing backup statuses and data store changes", context = TAG)
    }

    private fun startBackupStatusObservers(owner: Observation) {
        // Observe backup status changes for each category
        BackupCategory.entries.forEach { category ->
            owner.scope.launch {
                cacheStore.backupStatuses
                    .map { statuses -> statuses[category] ?: BackupItemStatus() }
                    .distinctUntilChanged { old, new ->
                        old.synced == new.synced &&
                            old.required == new.required &&
                            old.running == new.running
                    }
                    .collect { status ->
                        if (status.shouldBackup(category)) {
                            scheduleBackup(category, owner)
                        }
                    }
            }
        }
    }

    private fun startDataStoreListeners(owner: Observation) {
        owner.scope.launch {
            settingsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.SETTINGS)
                }
        }

        owner.scope.launch {
            widgetsStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.WIDGETS)
                }
        }

        // WALLET - Observe transfers
        owner.scope.launch {
            db.transferDao().observeAll()
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.WALLET)
                }
        }

        owner.scope.launch {
            watchOnlyAccountStore.data
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.WALLET)
                }
        }

        // METADATA - Observe entire CacheStore excluding backup statuses
        owner.scope.launch {
            cacheStore.data
                .map { it.copy(backupStatuses = mapOf()) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.METADATA)
                }
        }

        // METADATA - Observe pre-activity metadata changes
        owner.scope.launch {
            preActivityMetadataRepo.preActivityMetadataChanged
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.METADATA)
                }
        }

        // METADATA - Observe hardware wallet names only: the store is also rewritten by every connect,
        // and reconnect traffic must not re-upload the whole metadata envelope.
        owner.scope.launch {
            hwWalletStore.data
                .map { it.hwWalletNames() }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (shouldSkipBackup()) return@collect
                    markBackupRequired(owner, BackupCategory.METADATA)
                }
        }

        observeBackupChanges(owner, pubkyRepo.backupStateVersion, BackupCategory.METADATA)
        observeBackupChanges(owner, privatePaykitRepo.get().backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, paykitSdkService.backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, paykitPaymentProofStore.backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, onchainSendAttemptStore.backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, paykitUsdtPaymentRepo.get().backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, paykitUsdtPaymentRepo.get().backupStateVersion, BackupCategory.USDT_RECOVERY)
        observeBackupChanges(
            owner,
            usdtRepo.get().state.map { it.transfers }.distinctUntilChanged(),
            BackupCategory.USDT_RECOVERY,
        )
        observeBackupChanges(
            owner,
            usdtRepo.get().state.map { it.transfers }.distinctUntilChanged(),
            BackupCategory.WALLET,
        )
        observeBackupChanges(owner, paykitPresentationStore.backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(owner, paykitAllowanceStore.backupStateVersion, BackupCategory.WALLET)
        observeBackupChanges(
            owner,
            privatePaykitAddressReservationRepo.get().backupStateVersion,
            BackupCategory.WALLET,
        )

        // BLOCKTANK - Observe blocktank state changes (orders, cjitEntries, info)
        observeBackupChanges(owner, blocktankRepo.blocktankState, BackupCategory.BLOCKTANK)

        // ACTIVITY - Observe activity changes
        observeBackupChanges(owner, activityRepo.activitiesChanged, BackupCategory.ACTIVITY)
        // Hardware tags are carried by the metadata backup. Observe the narrower tag signal so ordinary
        // payment and sync traffic does not re-upload the whole metadata envelope.
        observeBackupChanges(owner, activityRepo.activityTagsChanged, BackupCategory.METADATA)

        // LIGHTNING_CONNECTIONS - Only display sync timestamp, ldk-node manages its own backups
        @OptIn(FlowPreview::class)
        owner.scope.launch {
            lightningService.syncStatusChanged
                .debounce(SYNC_STATUS_DEBOUNCE)
                .collect {
                    val lastSync = lightningService.status?.latestLightningWalletSyncTimestamp?.toLong()
                        ?.let { it * 1000 } // Convert seconds to millis
                        ?: return@collect
                    if (shouldSkipBackup()) return@collect
                    updateBackupStatus(BackupCategory.LIGHTNING_CONNECTIONS, owner.generation) {
                        it.copy(required = lastSync, synced = lastSync, running = false)
                    }
                }
        }
    }

    private fun observeBackupChanges(owner: Observation, flow: Flow<*>, category: BackupCategory): Job =
        owner.scope.launch {
            flow.drop(1).collect {
                if (shouldSkipBackup(category)) return@collect
                markBackupRequired(owner, category)
            }
        }

    private fun startPeriodicBackupFailureCheck(owner: Observation) {
        owner.scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(BACKUP_CHECK_INTERVAL)
                checkForFailedBackups(owner)
            }
        }
    }

    private suspend fun markBackupRequired(owner: Observation, category: BackupCategory) =
        markBackupRequired(category, owner.generation)

    private suspend fun markBackupRequired(category: BackupCategory, expectedGeneration: Long) =
        withContext(NonCancellable) {
            // A consumed change must remain pending even if observation stops before the cache write.
            updateBackupStatus(category, expectedGeneration) {
                failedBackupRequired -= category
                it.copy(required = maxOf(currentTimeMillis(), it.required + 1, it.synced + 1))
            }
        }

    private suspend fun updateBackupStatus(
        category: BackupCategory,
        expectedGeneration: Long,
        transform: (BackupItemStatus) -> BackupItemStatus,
    ) {
        cacheStore.update { data ->
            synchronized(lifecycleLock) {
                // Reset drains admitted writers before advancing the generation or clearing clients.
                if (generation != expectedGeneration) return@update data
                val status = data.backupStatuses[category] ?: BackupItemStatus()
                data.copy(backupStatuses = data.backupStatuses + (category to transform(status)))
            }
        }
    }

    private fun scheduleBackup(
        category: BackupCategory,
        owner: Observation? = null,
        requestedGeneration: Long? = null,
    ) {
        val job = synchronized(lifecycleLock) {
            if (resetting || _isWiping.value || (owner != null && observation !== owner) ||
                (requestedGeneration != null && requestedGeneration != generation)
            ) {
                return
            }
            if (owner == null) explicitBackups += category
            val existing = backupJobs[category]
            if (existing != null && !existing.isCompleted && !existing.isCancelled) return
            val expectedGeneration = generation
            val jobScope = owner?.scope ?: pendingScope
            jobScope.launch(start = CoroutineStart.LAZY) {
                try {
                    owner?.ready?.await()
                    // Coalescing pending work must not delay an explicit recovery acknowledgement.
                    delay(BACKUP_DEBOUNCE)
                    categoryMutexes.getValue(category).withLock {
                        runningBackups += category
                        try {
                            updateBackupStatus(category, expectedGeneration) { it.copy(running = true) }
                            if (category == BackupCategory.WALLET) {
                                paykitSdkService.isPaymentSubmissionActive.first { !it }
                            }
                            val status = cacheStore.backupStatuses.first()[category] ?: BackupItemStatus()
                            if (status.isRequired && !shouldSkipBackup(category) &&
                                failedBackupRequired[category] != status.required
                            ) {
                                performBackup(category, expectedGeneration)
                            }
                        } finally {
                            finishBackup(category, expectedGeneration)
                        }
                    }
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    synchronized(lifecycleLock) { backupJobs.remove(category, currentJob) }
                    withContext(NonCancellable) {
                        schedulePendingBackup(category, expectedGeneration, owner)
                    }
                }
            }.also { backupJobs[category] = it }
        }
        job.start()
    }

    private suspend fun schedulePendingBackup(
        category: BackupCategory,
        expectedGeneration: Long,
        previousOwner: Observation? = null,
    ) {
        var status = BackupItemStatus()
        updateBackupStatus(category, expectedGeneration) {
            status = it
            if (!it.isRequired) explicitBackups -= category
            it
        }
        synchronized(lifecycleLock) {
            if (resetting || generation != expectedGeneration) return
            if (!status.shouldBackup(category)) return
            if (category in explicitBackups) {
                scheduleBackup(category, requestedGeneration = expectedGeneration)
            } else {
                val owner = observation?.takeIf { previousOwner == null || previousOwner === it } ?: return
                if (owner.scope.isActive) scheduleBackup(category, owner)
            }
        }
    }

    private suspend fun finishBackup(category: BackupCategory, expectedGeneration: Long) = withContext(NonCancellable) {
        try {
            updateBackupStatus(category, expectedGeneration) { it.copy(running = false) }
        } finally {
            runningBackups -= category
        }
    }

    private fun checkForFailedBackups(owner: Observation) {
        val currentTime = currentTimeMillis()

        // find if there are any backup categories that have been failing for more than 30 minutes
        owner.scope.launch {
            val backupStatuses = cacheStore.backupStatuses.first()
            val hasFailedBackups = BackupCategory.entries.any { category ->
                val status = backupStatuses[category] ?: BackupItemStatus()

                val pendingSince = temporaryFailureSince[category]?.coerceAtMost(status.required) ?: status.required
                val isPendingAndOverdue = status.isRequired &&
                    currentTime - pendingSince > FAILED_BACKUP_CHECK_TIME
                return@any isPendingAndOverdue
            }

            if (hasFailedBackups) {
                showBackupFailureNotification(owner, currentTime)
            }
        }
    }

    private fun showBackupFailureNotification(owner: Observation, currentTime: Long) {
        // Throttle notifications to avoid spam
        if (currentTime - lastNotificationTime < FAILED_BACKUP_NOTIFICATION_INTERVAL) return

        lastNotificationTime = currentTime

        owner.scope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.settings__backup__failed_title),
                description = context.getString(R.string.settings__backup__failed_message).formatPlural(
                    mapOf("interval" to (BACKUP_CHECK_INTERVAL / MINUTE_IN_MS))
                ),
            )
        }
    }

    suspend fun triggerBackup(category: BackupCategory): Result<Unit> = performExplicitBackup(category)

    private suspend fun performExplicitBackup(
        category: BackupCategory,
        usdtSnapshot: String? = null,
    ): Result<Unit> = withBackupOperation { expectedGeneration ->
        var reservedRequired: Long? = null
        if (usdtSnapshot != null) {
            withContext(NonCancellable) {
                updateBackupStatus(category, expectedGeneration) {
                    val required = maxOf(currentTimeMillis(), it.required + 1, it.synced + 1)
                    reservedRequired = required
                    it.copy(required = required)
                }
            }
        }
        categoryMutexes.getValue(category).withLock {
            if (synchronized(lifecycleLock) { resetting || generation != expectedGeneration }) {
                return@withLock Result.failure(UsdtException.BackupUnavailable())
            }
            runningBackups += category
            try {
                performBackup(
                    category,
                    expectedGeneration,
                    usdtSnapshot,
                    explicitAttempt = true,
                    reservedRequired = reservedRequired,
                )
            } finally {
                finishBackup(category, expectedGeneration)
                withContext(NonCancellable) {
                    schedulePendingBackup(category, expectedGeneration)
                }
            }
        }
    }

    private suspend fun performBackup(
        category: BackupCategory,
        expectedGeneration: Long,
        usdtSnapshot: String? = null,
        explicitAttempt: Boolean = false,
        reservedRequired: Long? = null,
    ): Result<Unit> =
        if (category == BackupCategory.WALLET || category == BackupCategory.METADATA) {
            walletBackupMutex.withLock {
                performSerializedBackup(category, expectedGeneration, usdtSnapshot, explicitAttempt, reservedRequired)
            }
        } else {
            performSerializedBackup(category, expectedGeneration, usdtSnapshot, explicitAttempt, reservedRequired)
        }

    private suspend fun performSerializedBackup(
        category: BackupCategory,
        expectedGeneration: Long,
        usdtSnapshot: String?,
        explicitAttempt: Boolean,
        reservedRequired: Long?,
    ): Result<Unit> {
        Logger.debug("Backup starting for: '$category'", context = TAG)
        var backupRequired = reservedRequired ?: currentTimeMillis()
        updateBackupStatus(category, expectedGeneration) {
            if (reservedRequired == null) {
                val required = if (explicitAttempt) it.required + 1 else it.required
                backupRequired = maxOf(backupRequired, required, it.synced + 1)
            }
            if ((failedBackupRequired[category] ?: 0) <= backupRequired) failedBackupRequired -= category
            it.copy(running = true, required = maxOf(it.required, backupRequired))
        }
        if (synchronized(lifecycleLock) { resetting || generation != expectedGeneration }) {
            return Result.failure(UsdtException.BackupUnavailable())
        }
        val upload = when (category) {
            BackupCategory.WALLET -> uploadWalletBackup()
            BackupCategory.USDT_RECOVERY -> uploadUsdtRecoveryBackup(usdtSnapshot)
            else -> runSuspendCatching {
                vssBackupClient.putObject(key = category.name, data = getBackupDataBytes(category)).getOrThrow()
            }
        }
        return upload
            .onSuccess {
                withContext(NonCancellable) {
                    updateBackupStatus(category, expectedGeneration) {
                        if ((failedBackupRequired[category] ?: 0) <= backupRequired) {
                            failedBackupRequired -= category
                            temporaryFailureRetries -= category
                            temporaryFailureSince -= category
                        }
                        if (it.required <= backupRequired) {
                            explicitBackups -= category
                        } else if (explicitAttempt) {
                            explicitBackups += category
                        }
                        it.copy(running = false, synced = maxOf(it.synced, backupRequired))
                    }
                    if (category == BackupCategory.WALLET) {
                        updateBackupStatus(BackupCategory.METADATA, expectedGeneration) {
                            failedBackupRequired -= BackupCategory.METADATA
                            if (it.isRequired) {
                                it.copy(required = maxOf(currentTimeMillis(), it.required + 1, it.synced + 1))
                            } else {
                                it
                            }
                        }
                    }
                }
                Logger.info("Backup succeeded for: '$category'", context = TAG)
            }
            .onFailure { markBackupFailed(category, backupRequired, expectedGeneration, it, explicitAttempt) }
            .map { }
    }

    suspend fun persistWalletBackup(): Result<Unit> = withBackupOperation { expectedGeneration ->
        walletBackupMutex.withLock { uploadWalletBackup() }
            .onFailure { markBackupRequired(BackupCategory.WALLET, expectedGeneration) }
    }

    suspend fun persistUsdtRecoveryBackup(snapshot: String): Result<Unit> =
        performExplicitBackup(BackupCategory.USDT_RECOVERY, snapshot)

    private suspend fun withBackupOperation(
        operation: suspend (Long) -> Result<Unit>,
    ): Result<Unit> = withContext(ioDispatcher) {
        val callerContext = currentCoroutineContext().minusKey(Job)
        val task = synchronized(lifecycleLock) {
            if (resetting || _isWiping.value) return@withContext Result.failure(UsdtException.BackupUnavailable())
            val expectedGeneration = generation
            pendingScope.async(context = callerContext, start = CoroutineStart.LAZY) { operation(expectedGeneration) }
        }
        try {
            task.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(UsdtException.BackupUnavailable().apply { initCause(e) })
        } finally {
            withContext(NonCancellable) { task.cancelAndJoin() }
        }
    }

    private suspend fun uploadUsdtRecoveryBackup(snapshot: String? = null): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            usdtBackupMutex.withLock {
                if (shouldSkipBackup(BackupCategory.USDT_RECOVERY)) throw UsdtException.BackupUnavailable()
                val payments = paykitUsdtPaymentRepo.get()
                repeat(WALLET_SNAPSHOT_ATTEMPTS) {
                    val version = payments.backupStateVersion.value
                    val transfers = usdtRepo.get().state.value.transfers
                    val core = snapshot ?: usdtRepo.get().backupSnapshot() ?: return@withLock
                    val associations = payments.backupSnapshot()
                    if (version == payments.backupStateVersion.value &&
                        (snapshot != null || transfers == usdtRepo.get().state.value.transfers)
                    ) {
                        if (shouldSkipBackup(BackupCategory.USDT_RECOVERY)) throw UsdtException.BackupUnavailable()
                        usdtRecoveryBackup.persist(core, associations)
                        return@withLock
                    }
                    yield()
                }
                throw WalletBackupSnapshotChanged()
            }
        }
    }

    private suspend fun uploadWalletBackup(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (shouldSkipBackup()) throw UsdtException.BackupUnavailable()
            vssBackupClient.setup().getOrThrow()
            val data = getWalletBackupDataBytes()
            if (shouldSkipBackup()) throw UsdtException.BackupUnavailable()
            vssBackupClient.putObject(key = BackupCategory.WALLET.name, data = data).getOrThrow()
        }.map { }
    }

    private suspend fun markBackupFailed(
        category: BackupCategory,
        backupRequired: Long,
        expectedGeneration: Long,
        e: Throwable,
        explicitAttempt: Boolean,
    ) {
        val temporary = e.isTemporaryBackupFailure()
        withContext(NonCancellable) {
            updateBackupStatus(category, expectedGeneration) {
                if (temporary && explicitAttempt) explicitBackups += category
                if (!temporary && e !is WalletBackupSnapshotChanged && it.required == backupRequired) {
                    explicitBackups -= category
                }
                if (it.required == backupRequired && e !is WalletBackupSnapshotChanged) {
                    failedBackupRequired[category] = backupRequired
                } else if ((failedBackupRequired[category] ?: 0) <= backupRequired) {
                    failedBackupRequired -= category
                }
                it.copy(running = false)
            }
        }
        when {
            e is WalletBackupSnapshotChanged ->
                Logger.debug("Deferred changing backup snapshot for: '$category'", context = TAG)
            temporary -> retryAfterTemporaryFailure(category, backupRequired, expectedGeneration, e)
            else -> Logger.error("Backup failed for: '$category'", e, context = TAG)
        }
    }

    private fun retryAfterTemporaryFailure(
        category: BackupCategory,
        backupRequired: Long,
        expectedGeneration: Long,
        e: Throwable,
    ) {
        val job = synchronized(lifecycleLock) {
            if (resetting || generation != expectedGeneration ||
                (failedBackupRequired[category] ?: 0) > backupRequired
            ) {
                return
            }
            val attempt = temporaryFailureRetries.merge(category, 1, Int::plus) ?: 1
            temporaryFailureSince.putIfAbsent(category, backupRequired)
            val retryDelay = (TEMPORARY_FAILURE_RETRY_DELAY * (1 shl (attempt - 1).coerceAtMost(MAX_RETRY_SHIFT)))
                .coerceAtMost(TEMPORARY_FAILURE_RETRY_MAX_DELAY)
            Logger.warn("Retrying backup for: '$category' in '$retryDelay'", e, context = TAG)
            pendingScope.launch(start = CoroutineStart.LAZY) {
                delay(retryDelay)
                synchronized(lifecycleLock) {
                    if (resetting || generation != expectedGeneration) return@launch
                    val failed = failedBackupRequired[category]
                    if (failed == backupRequired) {
                        failedBackupRequired.remove(category, backupRequired)
                    } else if (failed != null || category !in explicitBackups) {
                        return@launch
                    }
                }
                schedulePendingBackup(category, expectedGeneration)
            }
        }
        job.start()
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
        BackupCategory.USDT_RECOVERY -> throw UsdtException.BackupUnavailable()
    }

    private suspend fun getMetadataBackupDataBytes(): ByteArray = withContext(ioDispatcher) {
        check(!hasPendingWalletRestore()) { "Wallet backup restore is incomplete" }
        val walletBackupRequired = cacheStore.data.first().backupStatuses[BackupCategory.WALLET]?.isRequired == true
        if (walletBackupRequired) {
            check(privatePaykitAddressReservationRepo.get().quarantinedPublicAddressesSnapshot().isEmpty()) {
                "Public address exclusions must be backed up before replacing metadata"
            }
        }
        // These reads must not fall back to an empty list: this envelope is the only copy of the tags, so
        // uploading a partial payload would replace the stored ones. Failing here marks the backup failed
        // and leaves the previous upload intact until the retry succeeds.
        val preActivityMetadata = preActivityMetadataRepo.getAllPreActivityMetadata().getOrThrow()
        // Hardware tags ride here rather than in the activity backup: their activities are rebuilt by the
        // device watcher, so Core re-attaches them once the watcher recreates the rows.
        val hardwareTagMetadata = activityRepo.getHardwareTagsAsPreActivityMetadata().getOrThrow()
        val tagMetadata = (preActivityMetadata + hardwareTagMetadata)
            .distinctBy { it.walletId to it.paymentId }
        // Like the tags above, this envelope is the only copy of the names, so a read failure must
        // propagate and fail the backup rather than upload an empty set over the stored ones.
        val hwWalletNames = hwWalletStore.backupSnapshot().takeIf { it.isNotEmpty() }
        val cacheData = cacheStore.data.first()
        val pubkySession = pubkyRepo.snapshotSessionBackupState().getOrThrow()
        val pubkyContactProfileOverrides = pubkyRepo.snapshotContactProfileOverrides().getOrThrow()

        val payload = MetadataBackupV1(
            createdAt = currentTimeMillis(),
            tagMetadata = tagMetadata,
            cache = cacheData,
            pubkySession = pubkySession,
            pubkyContactProfileOverrides = pubkyContactProfileOverrides,
            hwWalletNames = hwWalletNames,
        )

        json.encodeToString(payload).toByteArray()
    }

    private suspend fun getWalletBackupDataBytes(): ByteArray {
        val sdkGeneration = paykitSdkService.runtimeGeneration
        var attemptsRemaining = WALLET_SNAPSHOT_ATTEMPTS
        while (true) {
            check(paykitSdkService.runtimeGeneration == sdkGeneration) {
                "Paykit identity changed during wallet snapshot"
            }
            val snapshot = runSuspendCatching { captureWalletBackupDataBytes(sdkGeneration) }
            attemptsRemaining--
            if (snapshot.exceptionOrNull() !is WalletBackupSnapshotChanged || attemptsRemaining == 0) {
                return snapshot.getOrThrow()
            }
            yield()
        }
    }

    private suspend fun captureWalletBackupDataBytes(sdkGeneration: Long): ByteArray {
        check(!keychain.hasPendingWalletRestore()) {
            "Wallet backup restore is incomplete"
        }
        lateinit var transfers: List<TransferEntity>
        val privateRepo = privatePaykitRepo.get()
        val privateVersion = privateRepo.backupStateVersion.value
        val paykitSdkBackupState = privateRepo.backupSnapshot().getOrThrow()
        check(paykitSdkBackupState.state != null || !pubkyRepo.hasIdentityForBackup()) {
            "Paykit identity is unavailable for backup"
        }
        val reservationRepo = privatePaykitAddressReservationRepo.get()
        val reservationVersion = reservationRepo.backupStateVersion.value
        val privateReservations = reservationRepo.backupSnapshot().getOrThrow()

        val watchOnlyAccountSnapshot = watchOnlyAccountStore.backupSnapshot()
        val walletIndex = backupWalletIndex()
        val (snapshotAttempt, snapshotProofs) = onchainSendAttemptStore.backupSnapshot(walletIndex) {
            // Follow-up writes its transfer before completing the guarded attempt. Capture the
            // transfer under that same attempt lock so a backup cannot omit both.
            transfers = db.transferDao().getAll()
            paykitPaymentProofRepo.get().backupSnapshot()
        }
        // Every unsigned local guard is unrecoverable without its receipt. Defer the entire
        // snapshot until signing finishes; never omit only the guard or related Shop proof.
        check(
            snapshotAttempt?.let {
                !it.restoredFromBackup && it.evidence == OnchainSendEvidence.Pending && it.txid == null &&
                    it.originalInputs == null && it.candidateTxids.isEmpty()
            } != true
        ) { "Waiting for signed receipt before wallet backup" }
        val active = snapshotAttempt?.let { attempt ->
            val wire = ActiveOnchainAttemptBackup.from(
                attempt,
                Env.network.name.lowercase(),
                vssStoreIdProvider.getBackupWalletBinding(walletIndex),
            )
            validateActiveProof(wire, snapshotProofs)
            wire
        }
        val payload = WalletBackupV1(
            createdAt = currentTimeMillis(),
            transfers = transfers,
            privatePaykitHighestReservedReceiveIndexByAddressType = privateReservations,
            publicPaykitLegacyAddresses = reservationRepo
                .quarantinedPublicAddressesSnapshot().toList().takeIf { it.isNotEmpty() },
            paykitSdkBackupState = paykitSdkBackupState.state,
            watchOnlyAccounts = watchOnlyAccountSnapshot.accounts,
            watchOnlyAccountAllocationState = watchOnlyAccountSnapshot.allocationState,
            usdtWallet = usdtRepo.get().backupSnapshot(),
            paykitPaymentState = PaykitPaymentStateBackup(
                subscriptions = paykitPresentationStore.backupSnapshot(),
                pendingProofs = snapshotProofs,
                activeOnchainAttempt = active,
                usdt = paykitUsdtPaymentRepo.get().backupSnapshot(),
                acceptedOneTimeRequests = paykitPresentationStore.acceptedOneTimeBackupSnapshot(),
                allowances = paykitAllowanceStore.backupSnapshot()
                    .mapValues { PaykitAllowanceStateBackup(it.value) }
                    .ifEmpty { null },
            ),
        )

        check(paykitSdkService.runtimeGeneration == sdkGeneration) {
            "Paykit identity changed during wallet snapshot"
        }
        if (privateRepo.backupStateVersion.value != privateVersion ||
            paykitSdkService.backupStateVersion.value != paykitSdkBackupState.sdkVersion ||
            reservationRepo.backupStateVersion.value != reservationVersion
        ) {
            throw WalletBackupSnapshotChanged()
        }
        return json.encodeToString(payload).toByteArray()
    }

    suspend fun performFullRestoreFromLatestBackup(
        onCacheRestored: suspend () -> Unit = {},
        connectionSession: WalletConnectionRecovery.Session? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (connectionSession == null) {
            return@withContext runSuspendCatching {
                connectionRecovery.withCurrentWallet {
                    performFullRestoreFromLatestBackup(onCacheRestored, it).getOrThrow()
                }
            }
        }
        if (isUsdtRestorePending() && !keychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)) {
            return@withContext restoreUsdtRecoveryAfterLegacyMigration(connectionSession)
        }
        Logger.debug("Full restore starting", context = TAG)

        _isRestoring.update { true }

        // Mutated only by the sequential restore steps below, inside this single coroutine.
        val categoriesNeedingRewrite = mutableSetOf<BackupCategory>()

        val result = try {
            runSuspendCatching {
                val addressReservations = privatePaykitAddressReservationRepo.get()
                val requiresMetadata = walletBackupMutex.withLock {
                    // Drain admitted uploads before arming the restore guards or importing metadata.
                    if (!keychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)) {
                        keychain.upsertString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name, "")
                    }
                    if (!isUsdtRestorePending()) {
                        keychain.upsertString(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name, "")
                    }
                    addressReservations.preparePublicAddressRestore()
                }
                connectionSession.replay()
                prepareUsdtRecoveryRestore()
                var metadataReceived = false
                val metadataResult = performRestore(BackupCategory.METADATA) { dataBytes ->
                    metadataReceived = true
                    runSuspendCatching {
                        addressReservations.capturePublicAddressRestoreSource(dataBytes)
                        val address = json.parseToJsonElement(String(dataBytes)).jsonObject["cache"]
                            ?.jsonObject?.get("onchainAddress")?.jsonPrimitive?.contentOrNull.orEmpty()
                        addressReservations.restorePublicAddress(address)
                    }.getOrElse { throw PublicAddressRestoreFailed(it) }
                    val restored = restoreMetadataBackup(dataBytes, onCacheRestored)
                    if (restored.needsRewrite) categoriesNeedingRewrite += BackupCategory.METADATA
                    restored.createdAt
                }
                if (!metadataReceived) {
                    if (requiresMetadata) {
                        metadataResult.getOrThrow()
                        throw PrivatePaykitAddressReservationError.PublicAddressRestorePending
                    }
                    addressReservations.finishPublicAddressRestoreWithoutMetadata()
                }
                metadataResult.onFailure { if (it is PublicAddressRestoreFailed) throw it }
                restoreUsdtRecovery()
                performRestore(BackupCategory.SETTINGS) { dataBytes ->
                    val parsed = json.decodeFromString<SettingsBackupV1>(String(dataBytes))
                    connectionSession.restoreSettings(parsed)
                    parsed.createdAt
                }
                performRestore(BackupCategory.WIDGETS) { dataBytes ->
                    val parsed = json.decodeFromString<WidgetsBackupV1>(String(dataBytes))
                    widgetsStore.restoreFromBackup(parsed)
                    parsed.createdAt
                }
                // SETTINGS is optional; preserving explicit local corrections is required.
                connectionSession.replay()
                val retainedWalletBackup = keychain.loadString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)
                if (retainedWalletBackup.isNullOrEmpty()) {
                    var didRestoreWalletBackup = false
                    performRestore(BackupCategory.WALLET) { dataBytes ->
                        didRestoreWalletBackup = true
                        restoreWalletBackup(dataBytes)
                    }.getOrThrow()
                    if (!didRestoreWalletBackup) {
                        keychain.delete(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)
                    }
                } else {
                    val createdAt = restoreWalletBackup(retainedWalletBackup.toByteArray())
                    updateRestoredBackupStatus(BackupCategory.WALLET, createdAt)
                    Logger.info("Restore success for: '${BackupCategory.WALLET}'", context = TAG)
                }
                performRestore(BackupCategory.BLOCKTANK) { dataBytes ->
                    val parsed = json.decodeFromString<BlocktankBackupV1>(String(dataBytes))
                    blocktankRepo.restoreFromBackup(parsed)
                    parsed.createdAt
                }
                performRestore(BackupCategory.ACTIVITY) { dataBytes ->
                    val restored = restoreActivityBackup(dataBytes)
                    if (restored.needsRewrite) categoriesNeedingRewrite += BackupCategory.ACTIVITY
                    restored.createdAt
                }

                Logger.info("Full restore success", context = TAG)
            }.onSuccess {
                settingsStore.update { it.copy(backupVerified = true) }
            }.onFailure { e ->
                Logger.warn("Full restore error", e, context = TAG)
            }
        } finally {
            _isRestoring.update { false }
        }

        if (result.isSuccess) {
            rewriteMigratedBackups(categoriesNeedingRewrite)
        }

        return@withContext result
    }

    suspend fun restoreUsdtRecoveryAfterLegacyMigration(
        connectionSession: WalletConnectionRecovery.Session? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (connectionSession == null) {
                connectionRecovery.withCurrentWallet {
                    restoreUsdtRecoveryAfterLegacyMigration(it).getOrThrow()
                }
            } else {
                if (!isUsdtRestorePending()) {
                    keychain.upsertString(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name, "")
                }
                connectionSession.replay()
                prepareUsdtRecoveryRestore()
                restoreUsdtRecovery(retainWallet = false)
            }
        }
    }

    private suspend fun prepareUsdtRecoveryRestore() {
        val recoveryKey = Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name
        if (!isUsdtRestorePending()) keychain.upsertString(recoveryKey, "")
        paykitUsdtPaymentRepo.get().pauseForRecoveryRestore()
        usdtRepo.get().waitForRecoveryIdle()
        usdtBackupMutex.withLock { }
        walletBackupMutex.withLock { }
        vssBackupClient.setup().getOrThrow()
    }

    private suspend fun restoreUsdtRecovery(retainWallet: Boolean = true) {
        val recoveryKey = Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name
        val retained = keychain.loadString(recoveryKey)?.takeIf { it.isNotEmpty() }
            ?: keychain.loadString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)
        val legacyData = retained?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
            ?: vssBackupClient.getObject(BackupCategory.WALLET.name).getOrThrow()?.value
        if (legacyData != null) {
            keychain.upsertString(recoveryKey, legacyData.decodeToString())
            if (retainWallet) {
                keychain.upsertString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name, legacyData.decodeToString())
            }
        }
        val legacy = legacyData?.let { json.decodeFromString<WalletBackupV1>(it.decodeToString()) }
        usdtRecoveryBackup.restore(
            legacyWallet = legacy?.usdtWallet,
            legacyPaymentState = legacy?.paykitPaymentState?.usdt,
            restoreWallet = { usdtRepo.get().restoreBackup(it).getOrThrow() },
            restorePayments = { paykitUsdtPaymentRepo.get().restoreBackup(it) },
        )
        keychain.delete(recoveryKey)
        val restoredAt = currentTimeMillis()
        cacheStore.updateBackupStatus(BackupCategory.USDT_RECOVERY) {
            it.copy(running = false, synced = restoredAt, required = restoredAt)
        }
    }

    private suspend fun restoreMetadataBackup(
        dataBytes: ByteArray,
        onCacheRestored: suspend () -> Unit,
    ): RestoredCoreBackup {
        val migration = migrateCoreOwnedBackupFields(
            String(dataBytes),
            mapOf("tagMetadata" to preActivityMetadataRepo::migrateBackupPreActivityMetadataJson),
        )
        val parsed = json.decodeFromString<MetadataBackupV1>(migration.json)
        val cleanCache = parsed.cache.resetBip21() // Force address rotation
        cacheStore.update { cleanCache.copy(backupStatuses = it.backupStatuses) }
        Logger.debug("Restored caches: ${jsonLogOf(parsed.cache.copy(cachedRates = emptyList()))}", TAG)
        onCacheRestored()
        val persisted = preActivityMetadataRepo.upsertPreActivityMetadata(parsed.tagMetadata)
            .onFailure { Logger.warn("Failed to restore pre-activity metadata", it, context = TAG) }
            .isSuccess
        pubkyRepo.restoreSessionBackupState(parsed.pubkySession)
            .onFailure {
                Logger.warn("Failed to restore pubky session backup state", it, context = TAG)
            }
        pubkyRepo.restoreContactProfileOverrides(parsed.pubkyContactProfileOverrides)
            .onFailure {
                Logger.warn("Failed to restore pubky contact profile overrides", it, context = TAG)
            }
        // App-owned, so it takes no part in the Core field migration above and never sets needsRewrite.
        // Restored names wait as pending ones until each wallet is paired again. Failing to store them
        // must not discard the rest of this envelope, which has already been applied by here.
        runSuspendCatching { hwWalletStore.restoreNames(parsed.hwWalletNames.orEmpty()) }
            .onFailure { Logger.warn("Failed to restore hardware wallet names", it, context = TAG) }
        Logger.debug("Restored ${parsed.tagMetadata.size} pre-activity metadata", TAG)
        Logger.debug("Restored ${parsed.hwWalletNames.orEmpty().size} hardware wallet names", TAG)

        return RestoredCoreBackup(createdAt = parsed.createdAt, needsRewrite = migration.changed && persisted)
    }

    private suspend fun restoreActivityBackup(dataBytes: ByteArray): RestoredCoreBackup {
        val migration = migrateCoreOwnedBackupFields(
            String(dataBytes),
            mapOf(
                "activities" to activityRepo::migrateBackupActivitiesJson,
                "activityTags" to activityRepo::migrateBackupActivityTagsJson,
            ),
        )
        val parsed = json.decodeFromString<ActivityBackupV1>(migration.json)
        val persisted = activityRepo.restoreFromBackup(parsed)
            .onFailure { Logger.warn("Skipped activity backup rewrite after a failed restore", context = TAG) }
            .isSuccess

        return RestoredCoreBackup(createdAt = parsed.createdAt, needsRewrite = migration.changed && persisted)
    }

    private suspend fun restoreWalletBackup(dataBytes: ByteArray): Long {
        keychain.upsertString(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name, String(dataBytes))
        val parsed = json.decodeFromString<WalletBackupV1>(String(dataBytes))
        var restoredAttempt: OnchainSendAttempt? = null
        parsed.paykitPaymentState?.let {
            // Validate every proof before writing a guard or replacing existing payment state.
            it.pendingProofs.forEach { proof -> proof.restored() }
            val allowances = it.allowances.orEmpty().mapValues { (_, state) -> state.restored() }
            val walletIndex = backupWalletIndex()
            it.activeOnchainAttempt?.let { wire ->
                validateActiveProof(wire, it.pendingProofs)
                val attempt = wire.restored(
                    Env.network.name.lowercase(),
                    vssStoreIdProvider.getBackupWalletBinding(walletIndex),
                    WalletScope.default,
                    walletIndex,
                )
                val recipient = coreService.validateBitcoinAddress(attempt.address)
                require(!NetworkValidationHelper.isNetworkMismatch(recipient.network.toLdkNetwork(), Env.network)) {
                    "Restored payment recipient is on another network"
                }
                check(backupWalletIndex() == walletIndex) { "Backup wallet changed during restore" }
                onchainSendAttemptStore.restoreActive(attempt)
                restoredAttempt = attempt
                check(backupWalletIndex() == walletIndex) { "Backup wallet changed during restore" }
            }
            paykitPaymentRequestRepo.get().clear()
            paykitPresentationStore.restoreBackup(it.subscriptions)
            paykitPaymentProofRepo.get().restoreBackup(it.pendingProofs)
            paykitPresentationStore.restoreAcceptedOneTimeRequests(it.acceptedOneTimeRequests.orEmpty())
            paykitAllowanceStore.restoreBackup(allowances)
        }
        db.transferDao().upsert(parsed.transfers)
        watchOnlyAccountRepo.restore(
            parsed.watchOnlyAccounts.orEmpty(),
            parsed.watchOnlyAccountAllocationState,
        )
        lightningService.reconcileWatchOnlyAccounts()
        val addressReservationRepo = privatePaykitAddressReservationRepo.get()
        addressReservationRepo.restoreBackup(
            parsed.privatePaykitHighestReservedReceiveIndexByAddressType,
            parsed.publicPaykitLegacyAddresses.orEmpty().toSet(),
        ).getOrThrow()
        cacheStore.update { it.copy(onchainAddress = "", bip21 = "") }
        val privateRepo = privatePaykitRepo.get()
        privateRepo.restoreBackup(parsed.paykitSdkBackupState).getOrThrow()
        addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
        if (parsed.paykitPaymentState != null) {
            pubkyRepo.publicKey.value?.let { paykitPaymentRequestRepo.get().activate(it) }
        }
        keychain.delete(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)
        // Restore can finish while the node is already Running: no new lifecycle/event is guaranteed.
        restoredAttempt?.let { restored ->
            onchainSendAttemptStore.current()?.takeIf {
                it.attemptId == restored.attemptId && it.walletIndex == restored.walletIndex
            }
        }?.takeIf { it.hasPositiveEvidence }?.let { attempt ->
            if (attempt.requestId != null && !attempt.localFollowupComplete) {
                runSuspendCatching { paykitPaymentProofRepo.get().reconcile() }.onFailure {
                    Logger.warn("Restored accepted Shop payment remains pending local follow-up", it, context = TAG)
                }
            } else if (attempt.requestId == null && !attempt.localFollowupComplete) {
                runSuspendCatching {
                    lightningRepo.get().completeAcceptedOrdinaryFollowup(requireNotNull(attempt.txid))
                }.onFailure {
                    Logger.warn("Restored accepted payment remains pending local follow-up", it, context = TAG)
                }
            }
        }
        Logger.debug("Restored ${parsed.transfers.size} transfers", context = TAG)
        return parsed.createdAt
    }

    // Match the actual custom VSS namespace selected by Keychain, then verify its derived binding.
    private suspend fun backupWalletIndex(): Int {
        val index = db.configDao().getAll().first().firstOrNull()?.walletIndex ?: 0L
        require(index in 0..Int.MAX_VALUE.toLong())
        return index.toInt()
    }

    private fun validateActiveProof(
        wire: ActiveOnchainAttemptBackup,
        proofs: List<PaykitPaymentStateBackup.Proof>,
    ) {
        wire.validateProofs(proofs, WalletScope.default)
    }

    suspend fun getLatestBackupTime(): ULong? = withContext(ioDispatcher) {
        runCatching {
            withTimeout(VSS_TIMESTAMP_TIMEOUT) {
                vssBackupClient.setup().getOrThrow()
                coroutineScope {
                    BackupCategory.entries
                        .filter { it != BackupCategory.LIGHTNING_CONNECTIONS && it != BackupCategory.USDT_RECOVERY }
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
        val data = item.value

        @Serializable
        data class BackupWithCreatedAt(val createdAt: Long? = null)

        return runCatching {
            val millis = json.decodeFromString<BackupWithCreatedAt>(String(data)).createdAt ?: return@runCatching null
            (millis / 1000).toULong()
        }.getOrNull()
    }

    fun scheduleFullBackup() {
        Logger.debug("Scheduling backups for all categories", context = TAG)
        val requestedGeneration = synchronized(lifecycleLock) { generation }
        BackupCategory.entries
            .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
            .forEach {
                scheduleBackup(it, requestedGeneration = requestedGeneration)
            }
    }

    /**
     * Fill in wallet ids that predate wallet-scoped activity data before a backup
     * envelope is decoded. Each Core-owned array field is handed to the matching
     * Core migration helper as raw JSON, so the app never edits Core model JSON
     * itself. Records that already carry a wallet id are left unchanged, so this
     * is safe to run on current backups too.
     *
     * A field whose migration fails keeps its original JSON. For an envelope that already carries wallet
     * ids that is a no-op, so a Core failure costs nothing. For a legacy envelope the unmigrated field
     * then fails to decode and the category is skipped, which [performRestore] logs.
     */
    private suspend fun migrateCoreOwnedBackupFields(
        raw: String,
        fieldMigrations: Map<String, suspend (String) -> Result<String>>,
    ): CoreFieldMigration {
        val root = json.parseToJsonElement(raw).jsonObject
        val patched = root.toMutableMap()
        var changed = false

        for ((field, migrate) in fieldMigrations) {
            val element = root[field]
            if (element !is JsonArray) continue

            migrate(element.toString())
                .onSuccess { migratedJson ->
                    // Compare elements, not strings: Core's serializer may reorder keys or reformat
                    // without changing any value.
                    val migratedElement = json.parseToJsonElement(migratedJson)
                    if (migratedElement == element) return@onSuccess

                    patched[field] = migratedElement
                    changed = true
                    Logger.debug("Migrated backup field '$field' to current wallet scope", context = TAG)
                }
                .onFailure { Logger.warn("Failed to migrate backup field '$field'", it, context = TAG) }
        }

        return CoreFieldMigration(json = JsonObject(patched).toString(), changed = changed)
    }

    /**
     * Re-upload the app-owned VSS envelopes whose Core-owned fields were migrated on restore, so future
     * restores decode current wallet-scoped entries without the legacy migration path.
     */
    private suspend fun rewriteMigratedBackups(categories: Set<BackupCategory>) {
        if (categories.isEmpty()) return

        Logger.info("Rewriting migrated backups for: '${categories.joinToString()}'", context = TAG)
        categories.forEach { triggerBackup(it) }
    }

    private suspend fun performRestore(
        category: BackupCategory,
        restoreAction: suspend (dataBytes: ByteArray) -> Long,
    ): Result<Unit> = runSuspendCatching {
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
            .getOrThrow()

        updateRestoredBackupStatus(category, createdAtTimestamp)
    }.onFailure {
        // Full restore continues after optional category failures, so record them here.
        Logger.warn("Failed to restore: '$category'", it, context = TAG)
    }

    private suspend fun updateRestoredBackupStatus(category: BackupCategory, createdAt: Long) {
        cacheStore.updateBackupStatus(category) {
            if ((category == BackupCategory.WALLET || category == BackupCategory.METADATA) && it.isRequired) {
                it.copy(running = false)
            } else {
                it.copy(running = false, synced = createdAt, required = createdAt)
            }
        }
    }

    companion object {
        private const val TAG = "BackupRepo"

        private const val MINUTE_IN_MS = 60_000
        private const val BACKUP_DEBOUNCE = 5000L // 5 seconds

        /** Maximum captures before deferring a changing wallet to the next scheduled backup. */
        private const val WALLET_SNAPSHOT_ATTEMPTS = 3

        private const val BACKUP_CHECK_INTERVAL = 60 * 1000L // 1 minute
        private const val FAILED_BACKUP_CHECK_TIME = 30 * 60 * 1000L // 30 minutes
        private const val FAILED_BACKUP_NOTIFICATION_INTERVAL = 10 * 60 * 1000L // 10 minutes
        private const val SYNC_STATUS_DEBOUNCE = 500L // 500ms debounce for sync status updates
        private val VSS_TIMESTAMP_TIMEOUT = 60.seconds

        /** First wait before retrying a backup that failed on a temporary storage error, doubled per retry. */
        private val TEMPORARY_FAILURE_RETRY_DELAY = 10.seconds

        /** Longest wait between retries, past the Paykit shared-state lock's write cooldown. */
        private val TEMPORARY_FAILURE_RETRY_MAX_DELAY = 5.minutes

        /** Caps the doubling so the shift cannot overflow before the max delay applies. */
        private const val MAX_RETRY_SHIFT = 6

        /**
         * How long a pending restore gates ordinary uploads for. Longer than any restore in practice,
         * short enough that a restore that never returns cannot hold the gate for the session.
         */
        private val RESTORE_PENDING_TIMEOUT_MS = 10.minutes.inWholeMilliseconds
    }
}

internal class WalletBackupSnapshotChanged : AppError("Private payment state changed during wallet snapshot")

private class PublicAddressRestoreFailed(cause: Throwable) : AppError(
    "Failed to retain public address exclusion during restore",
    cause,
)

/**
 * Result of handing a backup envelope's Core-owned fields to Core for migration.
 *
 * @param json the envelope with every successfully migrated field replaced.
 * @param changed whether any field actually differed, meaning the envelope predates wallet-scoped
 * activity data and its VSS backup should be rewritten.
 */
private data class CoreFieldMigration(
    val json: String,
    val changed: Boolean,
)

/**
 * Outcome of restoring a backup category that embeds Core-owned data.
 *
 * @param createdAt the restored envelope's timestamp, used as the category's synced marker.
 * @param needsRewrite whether the envelope was migrated **and** Core persisted the result, meaning its VSS
 * backup can safely be rewritten with current entries.
 */
private data class RestoredCoreBackup(
    val createdAt: Long,
    val needsRewrite: Boolean,
)

internal fun Throwable.isTemporaryBackupFailure(): Boolean = isPaykitTemporarilyUnavailable() || when (this) {
    is VssException.ConnectionException,
    is VssException.NetworkException,
    is VssException.StoreException,
    is VssException.PutException,
    -> true
    is VssException.UnknownException -> isVssRequestSendFailure(errorDetails)
    else -> false
}

private fun isVssRequestSendFailure(details: String): Boolean {
    val prefix = "error sending request for url ("
    if (!details.startsWith(prefix) || !details.endsWith(")")) return false
    val url = runCatching { URI(details.removePrefix(prefix).dropLast(1)) }.getOrNull() ?: return false
    return url.scheme in setOf("http", "https") && !url.host.isNullOrEmpty() && url.path.endsWith("/putObjects")
}
