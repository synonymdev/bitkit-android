package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BoltzSwapEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R
import to.bitkit.data.ConnectionRecoveryOwnerChanged
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.of
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.NodeStartYieldedToStopError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.RecoveryModeError
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WipeInProgressError
import to.bitkit.services.BoltzService
import to.bitkit.services.MigrationService
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.isTxSyncTimeout
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
    private val blocktankRepo: BlocktankRepo,
    private val pubkyRepo: PubkyRepo,
    private val migrationService: MigrationService,
    private val connectivityRepo: ConnectivityRepo,
    private val boltzService: BoltzService,
    private val connectionRecovery: WalletConnectionRecovery,
) : ViewModel() {
    companion object {
        private const val TAG = "WalletViewModel"
        private val TIMEOUT_RESTORE_WAIT = 30.seconds

        /** Base backoff between swap updates stream attempts; scales linearly per attempt. */
        private val SWAP_UPDATES_RETRY_DELAY = 5.seconds

        /** Upper bound for the backoff between swap updates stream attempts. */
        private val SWAP_UPDATES_RETRY_CAP = 60.seconds

        /**
         * Ceiling on swap updates stream start attempts per run (~14 min of backoff). Giving up is
         * safe: the stream is retried on the next node start and when entering a swap flow.
         */
        private const val SWAP_UPDATES_MAX_ATTEMPTS = 20
    }

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    @Volatile
    private var isStarting = false

    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    val isRecoveryMode = lightningRepo.isRecoveryMode

    val isShowingMigrationLoading: StateFlow<Boolean> = migrationService.isShowingMigrationLoading
    val isRestoringFromRNRemoteBackup: StateFlow<Boolean> = migrationService.isRestoringFromRNRemoteBackup
    val isWiping: StateFlow<Boolean> = backupRepo.isWiping

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Initial)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val retryLightningPaymentMutex = Mutex()
    private val backupRestoreMutex = Mutex()
    private val _isRetryingLightningPayment = MutableStateFlow(false)
    val isRetryingLightningPayment = _isRetryingLightningPayment.asStateFlow()

    private val _connectionEditor = MutableStateFlow<ConnectionRecoveryUiState?>(null)
    val connectionEditor = _connectionEditor.asStateFlow()
    private val connectionEditorGeneration = AtomicLong()
    private var connectionRetryJob: Job? = null
    private val startupAttempt = AtomicLong()
    private val _connectionRetryBusy = MutableStateFlow(false)
    val connectionRetryBusy = _connectionRetryBusy.asStateFlow()
    private val _connectionStartupError = MutableStateFlow<ConnectionStartupFailure?>(null)
    val connectionStartupError = _connectionStartupError.asStateFlow()
    private var connectionEditorJob: Job? = null

    private data class AppliedConnection(val walletIndex: Int, val generation: Long, val revision: String?)

    @Volatile
    private var appliedConnection: AppliedConnection? = null

    private fun appliedConnection(owner: WalletConnectionRecovery.Owner, revision: String?) =
        AppliedConnection(owner.walletIndex, owner.generation, revision)

    fun openConnectionRecovery() {
        if (_connectionEditor.value != null) return
        val id = connectionEditorGeneration.incrementAndGet()
        _connectionEditor.update { ConnectionRecoveryUiState(id = id, busy = true) }
        launchConnectionEditorOperation(id) {
            val owner = connectionRecovery.captureOwner()
            connectionRecovery.withOperation(owner) { session ->
                session.replay()
                val settings = settingsStore.data.first()
                session.commitCurrentState {
                    updateConnectionEditor(id) {
                        ConnectionRecoveryUiState(
                            id = id,
                            owner = owner,
                            electrum = settings.electrumServer,
                            rgs = settings.rgsServerUrl.orEmpty()
                        )
                    }
                }
            }
        }
    }

    private fun launchConnectionEditorOperation(id: Long, operation: suspend () -> Unit) {
        connectionEditorJob = viewModelScope.launch(bgDispatcher) {
            try {
                runSuspendCatching { operation() }.onFailure { error ->
                    if (error is ConnectionRecoveryOwnerChanged) {
                        invalidateConnectionEditor(id, error)
                    } else {
                        updateConnectionEditor(id) { it.copy(error = error.message) }
                    }
                }
            } catch (error: CancellationException) {
                invalidateConnectionEditor(id, error)
                throw error
            } finally {
                updateConnectionEditor(id) { it.copy(busy = false) }
            }
        }
    }

    fun closeConnectionRecovery() {
        connectionEditorJob?.cancel()
        _connectionEditor.update { null }
    }

    private fun updateConnectionEditor(id: Long, transform: (ConnectionRecoveryUiState) -> ConnectionRecoveryUiState?) {
        _connectionEditor.update { if (it?.id == id) transform(it) else it }
    }

    fun editRecoveryElectrum(value: String) = _connectionEditor.update {
        it?.takeUnless { state -> state.busy }?.copy(electrum = value, saved = false, error = null) ?: it
    }

    fun editRecoveryRgs(value: String) = _connectionEditor.update {
        it?.takeUnless { state -> state.busy }?.copy(rgs = value, saved = false, error = null) ?: it
    }

    fun saveRecoveryConnection(electrum: Boolean) {
        val state = _connectionEditor.value ?: return
        val owner = state.owner ?: return
        if (state.busy) return
        _connectionEditor.update { it?.copy(busy = true, saved = false, error = null) }
        launchConnectionEditorOperation(state.id) {
            connectionRecovery.withOperation(owner) { session ->
                val settings = if (electrum) {
                    val url = state.electrum.trim()
                    lightningRepo.validateElectrumServer(url).getOrThrow()
                    session.save(electrum = url)
                } else {
                    val input = state.rgs.trim()
                    val url = when {
                        input.isBlank() -> Env.ldkRgsServerUrl
                        input.startsWith("https://") || input.startsWith("http://") -> input
                        else -> "https://$input"
                    }
                    url?.let { lightningRepo.validateRgsUrl(it).getOrThrow() }
                    session.save(rgs = WalletConnectionRecovery.RgsChoice(url))
                }
                session.commitCurrentState {
                    updateConnectionEditor(state.id) {
                        it.copy(
                            saved = true,
                            electrum = if (electrum) settings.electrumServer else it.electrum,
                            rgs = if (!electrum) settings.rgsServerUrl.orEmpty() else it.rgs,
                        )
                    }
                }
            }
        }
    }

    fun retryRecoveryConnection() {
        val state = _connectionEditor.value ?: return
        if (state.busy || _connectionRetryBusy.value) return
        val owner = state.owner
        if (owner == null) {
            closeConnectionRecovery()
            openConnectionRecovery()
            return
        }
        updateConnectionEditor(state.id) { it.copy(busy = true, error = null) }
        requestRecoveryRetry(owner, state.id)
    }

    private fun requestRecoveryRetry(
        owner: WalletConnectionRecovery.Owner?,
        editorId: Long?,
        backupOnly: Boolean = false,
    ): Job {
        connectionRetryJob?.takeIf { it.isActive }?.let { return it }
        _connectionRetryBusy.update { true }
        val attempt = startupAttempt.incrementAndGet()
        return viewModelScope.launch(bgDispatcher) {
            var attemptOwner = owner
            try {
                runSuspendCatching {
                    val admittedOwner = attemptOwner ?: connectionRecovery.captureRuntimeOwner().also {
                        attemptOwner = it
                    }
                    retryRecovery(admittedOwner, backupOnly, attempt)
                }.onSuccess { editorId?.let { updateConnectionEditor(it) { null } } }
                    .onFailure { error -> reportStartupFailure(attemptOwner, error, attempt, editorId) }
            } catch (error: CancellationException) {
                editorId?.let { invalidateConnectionEditor(it, error) }
                throw error
            } finally {
                editorId?.let { updateConnectionEditor(it) { state -> state.copy(busy = false) } }
                _connectionRetryBusy.update { false }
            }
        }.also { connectionRetryJob = it }
    }

    private suspend fun reportStartupFailure(
        owner: WalletConnectionRecovery.Owner?,
        error: Throwable,
        attempt: Long,
        editorId: Long? = null,
    ) {
        if (error is ConnectionRecoveryOwnerChanged) {
            if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
            editorId?.let { invalidateConnectionEditor(it, error) }
            return
        }
        if (error is RecoveryModeError || error is WipeInProgressError || error is NodeStartYieldedToStopError) return
        Logger.error("Wallet connection recovery startup failed", error, context = TAG)
        if (startupAttempt.get() != attempt) return
        if (owner == null) {
            _connectionStartupError.update {
                ConnectionStartupFailure(error.message ?: context.getString(R.string.common__error_body))
            }
            editorId?.let { updateConnectionEditor(it) { state -> state.copy(busy = false, error = error.message) } }
            ToastEventBus.send(error)
            return
        }
        runSuspendCatching {
            connectionRecovery.withOperation(owner) { session ->
                val markersClear = runSuspendCatching { !backupRepo.hasPendingWalletRestore() }.getOrDefault(false)
                val requiresRecovery = !markersClear || runSuspendCatching {
                    session.pendingRevision() != null
                }.getOrDefault(true)
                val published = session.commitCurrentState {
                    if (startupAttempt.get() != attempt) return@commitCurrentState false
                    _connectionStartupError.update {
                        ConnectionStartupFailure(
                            message = error.message ?: context.getString(R.string.common__error_body),
                            canProceedWithoutRestore = markersClear,
                            requiresRecovery = requiresRecovery,
                            owner = owner,
                        )
                    }
                    editorId?.let {
                        updateConnectionEditor(
                            it
                        ) { state -> state.copy(busy = false, error = error.message) }
                    }
                    true
                }
                if (published) ToastEventBus.send(error)
            }
        }.onFailure {
            if (it is ConnectionRecoveryOwnerChanged) editorId?.let { id -> invalidateConnectionEditor(id, it) }
            Logger.warn("Startup failure owner is no longer available", it, context = TAG)
        }
    }

    private fun invalidateConnectionEditor(id: Long, error: Throwable) = updateConnectionEditor(id) {
        it.copy(owner = null, busy = false, error = error.message)
    }

    private var syncJob: Job? = null
    private var pendingWalletStart = false

    init {
        checkAndPerformRNMigration()
        collectStates()
        observeNetworkState()
    }

    private fun observeNetworkState() = viewModelScope.launch(bgDispatcher) {
        connectivityRepo.isOnline.collect { state ->
            if (state == ConnectivityState.CONNECTED && pendingWalletStart) {
                pendingWalletStart = false
                val isChecked = migrationService.isMigrationChecked()
                if (!isChecked && migrationService.hasRNWalletData()) {
                    Logger.info("Network restored, retrying RN migration...", context = TAG)
                    checkAndPerformRNMigration()
                } else {
                    Logger.info("Network restored, retrying wallet start...", context = TAG)
                    start()
                }
            }
        }
    }

    private suspend fun isNetworkConnected(): Boolean {
        val state = connectivityRepo.isOnline.first()
        return state == ConnectivityState.CONNECTED
    }

    private fun checkAndPerformRNMigration() = viewModelScope.launch(bgDispatcher) {
        val isChecked = migrationService.isMigrationChecked()
        if (isChecked) {
            loadCacheIfWalletExists()
            return@launch
        }

        val hasNative = migrationService.hasNativeWalletData()
        if (hasNative) {
            migrationService.markMigrationChecked()
            loadCacheIfWalletExists()
            return@launch
        }

        val hasRN = migrationService.hasRNWalletData()
        if (!hasRN) {
            migrationService.markMigrationChecked()
            loadCacheIfWalletExists()
            return@launch
        }

        runSuspendCatching {
            connectionRecovery.withIdentityChange(
                isStillNeeded = {
                    !migrationService.isMigrationChecked() &&
                        !migrationService.hasNativeWalletData() && migrationService.hasRNWalletData()
                },
            ) {
                migrationService.setShowingMigrationLoading(true)
                Logger.info("RN wallet data found, starting migration...", context = TAG)
                migrationService.migrateFromReactNative()
            }
            walletRepo.setWalletExistsState()
            walletExists = walletRepo.walletExists()
            loadCacheIfWalletExists()
            if (walletExists) {
                // Re-check network before starting wallet (like iOS)
                if (!isNetworkConnected()) {
                    Logger.warn("Network offline, dismissing loader and skipping wallet start", context = TAG)
                    migrationService.setShowingMigrationLoading(false)
                    pendingWalletStart = true
                    return@runSuspendCatching
                }
                startNode(0)
            } else {
                migrationService.setShowingMigrationLoading(false)
            }
        }.onFailure {
            Logger.error("RN migration failed", it, context = TAG)
            migrationService.markMigrationChecked()
            migrationService.setShowingMigrationLoading(false)
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = "Migration Failed",
                description = "Please restore your wallet manually using your recovery phrase"
            )
        }
    }

    private fun loadCacheIfWalletExists() {
        if (walletExists) {
            walletRepo.loadFromCache()
        }
    }

    private fun collectStates() = viewModelScope.launch {
        walletState.collect {
            walletExists = it.walletExists
            if (!it.walletExists) {
                appliedConnection = null
                _connectionStartupError.update { null }
                closeConnectionRecovery()
                return@collect
            }

            when (_restoreState.value) {
                RestoreState.InProgress.Wallet -> supervisorScope {
                    val restore = async { restoreFromBackup() }
                    try {
                        restore.await()
                    } catch (_: CancellationException) {
                        // Identity replacement cancels its admitted restore, not the wallet-state observer.
                        currentCoroutineContext().ensureActive()
                    }
                }
                RestoreState.Initial if backupRepo.hasPendingWalletRestore() -> {
                    _restoreState.update { RestoreState.BackupFailed(1) }
                }

                else -> Unit
            }
        }
    }

    private suspend fun restoreFromBackup(
        resumePendingWalletBackup: Boolean = false,
        connectionSession: WalletConnectionRecovery.Session? = null,
    ) {
        if (connectionSession == null) {
            runSuspendCatching {
                connectionRecovery.withCurrentWallet {
                    restoreFromBackup(resumePendingWalletBackup, it)
                }
            }.onFailure {
                Logger.error("Connection recovery restore admission failed", it, context = TAG)
                _restoreState.update { RestoreState.BackupFailed(it.backupRetryCount() + 1, true) }
                backupRepo.setRestorePending(false)
                ToastEventBus.send(it)
            }
            return
        }
        if (!backupRestoreMutex.tryLock()) return
        val retryCount = _restoreState.value.backupRetryCount()
        _restoreState.update { RestoreState.InProgress.Metadata }
        try {
            runSuspendCatching {
                if (resumePendingWalletBackup) {
                    backupRepo.performFullRestoreFromLatestBackup(
                        onCacheRestored = walletRepo::loadFromCache,
                        connectionSession = connectionSession,
                    )
                        .getOrThrow()
                    pubkyRepo.initialize()
                } else {
                    restoreFromMostRecentBackup(connectionSession)
                }
            }.onSuccess {
                _restoreState.update { RestoreState.Completed }
            }.onFailure {
                Logger.error("Restore from backup failed", it, context = TAG)
                ToastEventBus.send(it)
                val pendingRestore = runSuspendCatching { backupRepo.hasPendingWalletRestore() }.getOrDefault(true)
                _restoreState.update { RestoreState.BackupFailed(retryCount + 1, pendingRestore) }
            }
        } finally {
            backupRepo.setRestorePending(false)
            backupRestoreMutex.unlock()
        }
    }

    private suspend fun restoreFromMostRecentBackup(connectionSession: WalletConnectionRecovery.Session) {
        val (rnTimestamp, vssTimestamp) = coroutineScope {
            val rn = async { migrationService.getRNRemoteBackupTimestamp() }
            val vss = async { backupRepo.getLatestBackupTime() }
            rn.await() to vss.await()
        }

        val shouldRestoreRN = when {
            rnTimestamp == null -> false
            vssTimestamp == null || vssTimestamp == 0uL -> true
            else -> rnTimestamp >= vssTimestamp
        }

        if (shouldRestoreRN) {
            restoreFromRNRemoteBackup(connectionSession)
        } else {
            backupRepo.performFullRestoreFromLatestBackup(
                onCacheRestored = walletRepo::loadFromCache,
                connectionSession = connectionSession,
            )
                .getOrThrow()
        }

        pubkyRepo.initialize()
    }

    private suspend fun restoreFromRNRemoteBackup(connectionSession: WalletConnectionRecovery.Session) {
        val legacyRestore = runSuspendCatching {
            migrationService.restoreFromRNRemoteBackup()
            walletRepo.loadFromCache()
        }
        if (legacyRestore.isSuccess) {
            backupRepo.restoreUsdtRecoveryAfterLegacyMigration(connectionSession).getOrThrow()
        } else {
            Logger.warn(
                "RN remote backup restore failed, falling back to VSS",
                legacyRestore.exceptionOrNull(),
                context = TAG,
            )
            backupRepo.performFullRestoreFromLatestBackup(
                onCacheRestored = walletRepo::loadFromCache,
                connectionSession = connectionSession,
            )
                .getOrThrow()
        }
    }

    fun onRestoreContinue() {
        viewModelScope.launch(bgDispatcher) {
            val shouldPrune = !settingsStore.restoredMonitoredTypesFromBackup
            settingsStore.update {
                it.copy(pendingRestoreAddressTypePrune = it.pendingRestoreAddressTypePrune || shouldPrune)
            }
        }
        _restoreState.update { RestoreState.Settled }
    }

    fun onRestoreRetry() = requestRecoveryRetry(owner = null, editorId = null)

    private suspend fun retryRecovery(owner: WalletConnectionRecovery.Owner, backupOnly: Boolean, attempt: Long) {
        connectionRecovery.withOperation(owner) { session ->
            session.commitCurrentState {
                if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
            }
            val revision = session.replay()
            connectionRecovery.withStartup(owner) { startupSession ->
                val connection = appliedConnection(owner, revision)
                if (!backupOnly || (revision != null && appliedConnection != connection)) {
                    if (!backupOnly && !backupRepo.hasPendingWalletRestore()) _restoreState.update { it.countRetry() }
                    setInitNodeLifecycleState()
                    migrationService.lockChannelMigration()
                    try {
                        val channelMigration = buildChannelMigrationIfAvailable()
                        startNodeOwned(owner.walletIndex, channelMigration, isRestoreRetry = true) {
                            startupSession.commitCurrentState { appliedConnection = connection }
                        }.getOrThrow()
                    } finally {
                        migrationService.unlockChannelMigration()
                    }
                }
            }
            if (backupOnly || backupRepo.hasPendingWalletRestore()) {
                backupRepo.setRestorePending(true)
                restoreFromBackup(resumePendingWalletBackup = true, connectionSession = session)
                if (_restoreState.value !is RestoreState.Completed) {
                    session.complete(revision)
                    // Restore failures carry their own required-state gate; optional profile work is not a node error.
                    session.commitCurrentState {
                        if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
                    }
                    return@withOperation
                }
            }
            session.complete(revision)
            session.commitCurrentState {
                if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
            }
        }
    }

    fun onBackupRestoreRetry() = requestRecoveryRetry(owner = null, editorId = null, backupOnly = true)

    @Suppress("ForbiddenComment")
    fun onProceedWithoutRestore(onDone: () -> Unit) = viewModelScope.launch {
        val failure = _connectionStartupError.value
        val attempt = startupAttempt.get()
        if (_connectionRetryBusy.value || isStarting) return@launch
        if (failure?.canProceedWithoutRestore == false) return@launch
        if (failure == null && _restoreState.value !is RestoreState.BackupFailed) return@launch
        runSuspendCatching {
            val owner = failure?.owner ?: connectionRecovery.captureRuntimeOwner()
            connectionRecovery.withOperation(owner) { session ->
                if (!canStillProceed(session, failure, attempt)) return@withOperation
                val revision = session.pendingRevision()
                if (revision != null) {
                    if (appliedConnection != appliedConnection(owner, revision) ||
                        lightningState.value.nodeLifecycleState !is NodeLifecycleState.Running
                    ) {
                        session.commitCurrentState {
                            if (startupAttempt.get() == attempt && _connectionStartupError.value === failure) {
                                _connectionStartupError.update {
                                    ConnectionStartupFailure(
                                        message = context.getString(R.string.onboarding__connection_saved),
                                        owner = owner,
                                    )
                                }
                            }
                        }
                        return@withOperation
                    }
                    session.complete(revision)
                }
                // TODO start LDK without trying to restore backup state from VSS if possible
                if (lightningState.value.nodeLifecycleState is NodeLifecycleState.ErrorStarting) {
                    lightningRepo.stop().getOrThrow()
                    delay(LOADING_MS.milliseconds)
                }
                if (!canStillProceed(session, failure, attempt)) return@withOperation
                session.commitCurrentState {
                    if (startupAttempt.get() != attempt || _connectionStartupError.value !== failure) {
                        return@commitCurrentState
                    }
                    _connectionStartupError.update { null }
                    _restoreState.update { RestoreState.Settled }
                    onDone()
                }
            }
        }.onFailure { Logger.error("Proceed without restore admission failed", it, context = TAG) }
    }

    private suspend fun canStillProceed(
        session: WalletConnectionRecovery.Session,
        failure: ConnectionStartupFailure?,
        attempt: Long,
    ): Boolean {
        if (runSuspendCatching { !backupRepo.hasPendingWalletRestore() }.getOrDefault(false)) return true
        session.commitCurrentState {
            if (startupAttempt.get() == attempt && _connectionStartupError.value === failure) {
                _connectionStartupError.update { it?.copy(canProceedWithoutRestore = false) }
                _restoreState.update { (it as? RestoreState.BackupFailed)?.copy(hasPendingWalletRestore = true) ?: it }
            }
        }
        return false
    }

    fun setInitNodeLifecycleState() = lightningRepo.setInitNodeLifecycleState()

    fun start(walletIndex: Int = 0) {
        // Cancel before the guards: a start that short-circuits never reaches LightningRepo.start,
        // so a stop deferred by an earlier background would fire while the app is foregrounded.
        lightningRepo.cancelPendingStop()

        if (!walletExists || isStarting) return

        viewModelScope.launch(bgDispatcher) {
            isStarting = true
            try {
                if (!isNetworkConnected()) {
                    Logger.warn("Network offline, skipping wallet start", context = TAG)
                    pendingWalletStart = true

                    if (migrationService.isShowingMigrationLoading.value) {
                        migrationService.setShowingMigrationLoading(false)
                    }
                    return@launch
                }

                waitForRestoreIfNeeded()

                startNode(walletIndex)
            } finally {
                isStarting = false
            }
        }
    }

    private suspend fun waitForRestoreIfNeeded() {
        if (!_restoreState.value.isOngoing()) return
        val finished = withTimeoutOrNull(TIMEOUT_RESTORE_WAIT) {
            _restoreState.first { !it.isOngoing() }
        }
        if (finished != null) return
        if (migrationService.isRestoringFromRNRemoteBackup.value) {
            Logger.warn("RN remote restore still running, waiting for channel state", context = TAG)
            _restoreState.first { !it.isOngoing() }
            return
        }
        Logger.warn("waitForRestoreIfNeeded timeout, proceeding anyway", context = TAG)
    }

    private fun buildChannelMigrationIfAvailable(): ChannelDataMigration? =
        migrationService.peekPendingChannelMigration()?.let { migration ->
            ChannelDataMigration(
                channelManager = migration.channelManager.map { it.toUByte() },
                channelMonitors = migration.channelMonitors.map { monitor -> monitor.map { it.toUByte() } },
            )
        }

    private suspend fun startNode(walletIndex: Int = 0): Result<Unit> {
        val attempt = startupAttempt.incrementAndGet()
        var owner: WalletConnectionRecovery.Owner? = null
        return runSuspendCatching {
            val admittedOwner = connectionRecovery.captureRuntimeOwner().also { owner = it }
            require(admittedOwner.walletIndex == walletIndex) { "Wallet startup owner changed" }
            val revision = connectionRecovery.withStartup(admittedOwner) { session ->
                session.commitCurrentState {
                    if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
                }
                val revision = session.replay()
                val connection = appliedConnection(admittedOwner, revision)
                migrationService.lockChannelMigration()
                try {
                    // A skipped start of a running node cannot acknowledge a newly saved configuration.
                    startNodeOwned(
                        walletIndex,
                        buildChannelMigrationIfAvailable(),
                        isRestoreRetry = revision != null && appliedConnection != connection,
                    ) {
                        session.commitCurrentState { appliedConnection = connection }
                    }.getOrThrow()
                } finally {
                    migrationService.unlockChannelMigration()
                }
                revision
            }
            connectionRecovery.withOperation(admittedOwner) { session ->
                session.complete(revision)
                session.commitCurrentState {
                    if (startupAttempt.get() == attempt) _connectionStartupError.update { null }
                }
            }
        }.onFailure { reportStartupFailure(owner, it, attempt) }
    }

    private suspend fun startNodeOwned(
        walletIndex: Int,
        channelMigration: ChannelDataMigration?,
        isRestoreRetry: Boolean,
        onStarted: suspend () -> Unit,
    ): Result<Unit> {
        val result = if (isRestoreRetry) {
            lightningRepo.restartNode(channelMigration, walletIndex)
        } else {
            lightningRepo.start(walletIndex, channelMigration = channelMigration)
        }
        return result
            .onSuccess {
                onStarted()
                if (channelMigration != null) {
                    migrationService.consumePendingChannelMigration()
                }
                walletRepo.setWalletExistsState()
                connectMigrationPeers()
                migrationService.cleanupInvalidMigrationTransfers()
                walletRepo.syncBalances()
                if (_restoreState.value.isIdle()) {
                    walletRepo.refreshBip21()
                }
                ensureSwapUpdatesRunning()
                // checkForOrphanedChannelMonitorRecovery()
            }
    }

    private var swapEventsCollected = false
    private var swapUpdatesJob: Job? = null

    @Volatile
    private var swapUpdatesRunning = false

    /**
     * Ensure the swap updates stream is running so pending LN -> onchain swaps are tracked and
     * auto-claimed. A live stream is left untouched: restarting it would abort bitkit-core's
     * background tasks and could race an in-flight claim. New swaps are reconciled at creation
     * inside bitkit-core, and the stream reconciles every pending swap periodically, so a
     * running stream is always enough. Runs only where swaps are supported and enabled in dev
     * settings, see [BoltzService.isSwapEnabled].
     */
    fun ensureSwapUpdatesRunning() {
        if (!boltzService.isSwapSupported) return
        if (swapUpdatesRunning || swapUpdatesJob?.isActive == true) return
        swapUpdatesJob = viewModelScope.launch {
            if (!boltzService.isSwapEnabled()) return@launch
            collectSwapEventsOnce()
            startSwapUpdates()
        }
    }

    /**
     * Open the swap updates stream so any pending LN -> onchain swaps resume and auto-claim.
     * Uses the wallet's current fee rate for the claim tx. Retries up to a ceiling: without the
     * stream a paid swap has nothing to broadcast its claim, so give up only after
     * [SWAP_UPDATES_MAX_ATTEMPTS] and leave the next trigger to retry. Once started, bitkit-core
     * keeps the WebSocket alive with its own reconnect loop.
     */
    private suspend fun startSwapUpdates() {
        var attempt = 0
        while (attempt < SWAP_UPDATES_MAX_ATTEMPTS) {
            val started = runSuspendCatching {
                val speed = settingsStore.data.first().defaultTransactionSpeed
                val feeRate = lightningRepo.getFeeRateForSpeed(speed).getOrNull()?.toDouble()
                boltzService.startUpdates(feeRateSatPerVb = feeRate, acceptZeroConf = true)
            }.onFailure {
                Logger.warn("Failed to start swap updates, attempt '${attempt + 1}'", it, context = TAG)
            }.isSuccess

            if (started) {
                swapUpdatesRunning = true
                return
            }
            attempt++
            delay((SWAP_UPDATES_RETRY_DELAY * attempt).coerceAtMost(SWAP_UPDATES_RETRY_CAP))
        }
        Logger.warn("Gave up starting swap updates after '$attempt' attempts", context = TAG)
    }

    /** Refresh balances when a swap lands on-chain so savings reflect it without a manual sync. */
    private fun collectSwapEventsOnce() {
        if (swapEventsCollected) return
        swapEventsCollected = true
        // UNDISPATCHED so we subscribe before the updates stream starts: the events flow has
        // no replay, so a swap claimed early would otherwise leave balances stale.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            boltzService.events.collect { event ->
                if (event is BoltzSwapEvent.Claimed) {
                    Logger.info("Savings swap claimed: ${event.swapId}", context = TAG)
                    walletRepo.syncBalances()
                }
            }
        }
    }

    private suspend fun connectMigrationPeers() {
        val peerUris = migrationService.tryFetchMigrationPeersFromBackup()
        for (uri in peerUris) {
            runCatching {
                val peer = PeerDetails.of(uri)
                lightningRepo.connectPeer(peer)
            }.onFailure {
                Logger.error("Failed to connect migration peer: $uri", it, context = TAG)
            }
        }
    }

    fun stop() {
        if (!walletExists) return

        swapUpdatesJob?.cancel()
        swapUpdatesRunning = false
        viewModelScope.launch(bgDispatcher) {
            stopSwapUpdates()
        }
        lightningRepo.stopDebounced()
    }

    private suspend fun stopSwapUpdates() {
        runSuspendCatching { boltzService.stopUpdates() }
            .onFailure { Logger.error("Failed to stop swap updates", it, context = TAG) }
    }

    fun refreshState() = viewModelScope.launch {
        walletRepo.syncNodeAndWallet()
            .onFailure {
                Logger.error("Failed to refresh state: ${it.message}", it)
                if (it is CancellationException || it.isTxSyncTimeout()) return@onFailure
                ToastEventBus.send(it)
            }
    }

    suspend fun refreshChannelsAndPeers() {
        lightningRepo.refreshChannelsAndPeers(onRefreshed = walletRepo::refreshMaxSendLightning)
    }

    /**
     * Refresh wallet balances and channel state from the running node without a chain sync, so a
     * just-received payment is reflected immediately (e.g. when entering the transfer-to-savings flow).
     */
    fun refreshBalances() = viewModelScope.launch {
        walletRepo.syncBalances()
        lightningRepo.syncState()
    }

    suspend fun resetPaymentRoutingCachesAndWait(): Result<Unit> {
        if (!retryLightningPaymentMutex.tryLock()) return Result.failure(LightningPaymentRetryInProgressError())

        _isRetryingLightningPayment.update { true }
        return try {
            lightningRepo.resetPaymentRoutingCachesAndWait()
        } finally {
            _isRetryingLightningPayment.update { false }
            retryLightningPaymentMutex.unlock()
        }
    }

    fun onPullToRefresh() {
        // Cancel any existing sync, manual or event triggered
        syncJob?.cancel()
        walletRepo.cancelSyncByEvent()
        lightningRepo.clearPendingSync()

        syncJob = viewModelScope.launch {
            _isRefreshing.update { true }
            try {
                walletRepo.syncNodeAndWallet(source = SyncSource.MANUAL)
            } finally {
                _isRefreshing.update { false }
            }
        }
    }

    fun disconnectPeer(peer: PeerDetails) {
        viewModelScope.launch {
            lightningRepo.disconnectPeer(peer)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = context.getString(R.string.common__success),
                        description = context.getString(R.string.wallet__peer_disconnected)
                    )
                }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message ?: context.getString(R.string.common__error_body)
                    )
                }
        }
    }

    fun updateBip21Invoice(amountSats: ULong? = walletState.value.bip21AmountSats) = viewModelScope.launch {
        walletRepo.updateBip21Invoice(amountSats).onFailure { error ->
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_invoice_update),
                description = error.message ?: context.getString(R.string.common__error_body)
            )
        }
    }

    fun updateOnchainBip21Amount(amountSats: ULong?) = viewModelScope.launch {
        walletRepo.updateOnchainBip21Amount(amountSats).onFailure { error ->
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_invoice_update),
                description = error.message ?: context.getString(R.string.common__error_body)
            )
        }
    }

    fun retryReceiveInvoice() = viewModelScope.launch {
        walletRepo.retryReceiveInvoice().onFailure { error ->
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_invoice_update),
                description = error.message ?: context.getString(R.string.common__error_body)
            )
        }
    }

    fun refreshReceiveState() = viewModelScope.launch {
        launch { blocktankRepo.refreshInfo() }
        walletRepo.refreshReceiveState().onFailure { error ->
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_invoice_update),
                description = error.message ?: context.getString(R.string.common__error_body)
            )
        }
    }

    fun wipeWallet() = viewModelScope.launch(bgDispatcher) {
        walletRepo.wipeWallet().onFailure {
            ToastEventBus.send(it)
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        walletRepo.createWallet(bip39Passphrase)
            .onSuccess {
                // The acknowledged new wallet must not inherit a cancelled restore's screen state.
                _restoreState.update { RestoreState.Initial }
                _connectionStartupError.update { null }
                closeConnectionRecovery()
                backupRepo.scheduleFullBackup()
            }
            .onFailure {
                ToastEventBus.send(it)
            }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        _restoreState.update { RestoreState.InProgress.Wallet }
        // The node starts and syncs long before the backup is read, so ordinary uploads are held from
        // here rather than from the restore itself, which would upload over the backup it has not read.
        backupRepo.setRestorePending(true)
        // Same reason for the received-sheet hold: by the time the user taps continue the node has
        // already been replaying historical transactions for a while.
        settingsStore.update {
            it.copy(pendingRestoreActivitySeenSince = nowTimestamp().epochSecond, restoreSyncedBlockHeight = 0)
        }

        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase,
        ).onFailure {
            // Nothing reaches restoreFromBackup when the wallet was never created, so release here.
            backupRepo.setRestorePending(false)
            // No node starts, so no sync would ever lift the received-sheet hold.
            settingsStore.update { it.copy(pendingRestoreActivitySeenSince = 0) }
            ToastEventBus.send(it)
        }
    }

    // region debug methods

    fun addTagToSelected(newTag: String) = viewModelScope.launch {
        walletRepo.addTagToSelected(newTag).onFailure {
            ToastEventBus.send(it)
        }
    }

    fun removeTag(tag: String) = viewModelScope.launch {
        walletRepo.removeTag(tag).onFailure {
            ToastEventBus.send(it)
        }
    }

    fun resetPreActivityMetadataTagsForCurrentInvoice() = viewModelScope.launch {
        walletRepo.resetPreActivityMetadataTagsForCurrentInvoice()
    }

    fun updateBip21Description(newText: String) {
        if (newText.isEmpty()) {
            Logger.warn(context.getString(R.string.common__empty))
        }
        walletRepo.setBip21Description(newText)
    }

    fun setBip21AmountSats(amountSats: ULong?) {
        walletRepo.setBip21AmountSats(amountSats?.takeIf { it > 0uL })
    }

    suspend fun handleHideBalanceOnOpen() {
        val hideBalanceOnOpen = settingsStore.data.map { it.hideBalanceOnOpen }.first()
        if (hideBalanceOnOpen) {
            settingsStore.update { it.copy(hideBalance = true) }
        }
    }
}

sealed interface RestoreState {
    data object Initial : RestoreState

    sealed interface InProgress : RestoreState {
        object Wallet : InProgress
        object Metadata : InProgress
    }

    data class Retry(val count: Int) : RestoreState
    data class BackupFailed(val count: Int, val hasPendingWalletRestore: Boolean = true) : RestoreState
    data object Completed : RestoreState
    data object Settled : RestoreState

    fun retryCount() = when (this) {
        is Retry -> count
        is BackupFailed -> count
        else -> 0
    }

    fun backupRetryCount() = (this as? BackupFailed)?.count ?: 0
    fun countRetry(): RestoreState = when (this) {
        is Retry -> Retry(count + 1)
        is BackupFailed -> copy(count = count + 1)
        else -> Retry(1)
    }
    fun isOngoing() = this is InProgress
    fun isIdle() = this is Initial || this is Settled
}

class LightningPaymentRetryInProgressError : AppError("Lightning payment retry already in progress")

@Immutable
data class ConnectionRecoveryUiState(
    internal val id: Long = 0,
    internal val owner: WalletConnectionRecovery.Owner? = null,
    val electrum: String = "",
    val rgs: String = "",
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

@Immutable
data class ConnectionStartupFailure(
    val message: String,
    val canProceedWithoutRestore: Boolean = false,
    val requiresRecovery: Boolean = true,
    internal val owner: WalletConnectionRecovery.Owner? = null,
)

fun canProceedWithoutRestore(restoreState: RestoreState, startupClearance: Boolean?): Boolean =
    startupClearance ?: ((restoreState as? RestoreState.BackupFailed)?.hasPendingWalletRestore == false)
