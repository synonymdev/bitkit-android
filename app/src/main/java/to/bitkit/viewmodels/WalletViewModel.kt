package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.ToastText
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.RecoveryModeError
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.MigrationService
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.shared.toast.Toaster
import to.bitkit.utils.Logger
import to.bitkit.utils.isTxSyncTimeout
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
    private val blocktankRepo: BlocktankRepo,
    private val migrationService: MigrationService,
    private val toaster: Toaster,
) : ViewModel() {
    companion object {
        private const val TAG = "WalletViewModel"
        private val TIMEOUT_RESTORE_WAIT = 30.seconds
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

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Initial)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private var syncJob: Job? = null

    init {
        checkAndPerformRNMigration()
        collectStates()
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

        migrationService.setShowingMigrationLoading(true)

        runCatching {
            migrationService.migrateFromReactNative()
            walletRepo.setWalletExistsState()
            walletExists = walletRepo.walletExists()
            loadCacheIfWalletExists()
            if (walletExists) {
                val channelMigration = buildChannelMigrationIfAvailable()
                startNode(0, channelMigration)
            } else {
                migrationService.setShowingMigrationLoading(false)
            }
        }.onFailure {
            Logger.error("RN migration failed", it, context = TAG)
            migrationService.markMigrationChecked()
            migrationService.setShowingMigrationLoading(false)
            toaster.error(
                titleRes = R.string.wallet__migration_error_title,
                bodyRes = R.string.wallet__migration_error_body,
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
            if (it.walletExists && _restoreState.value == RestoreState.InProgress.Wallet) {
                restoreFromBackup()
            }
        }
    }

    private suspend fun restoreFromBackup() {
        _restoreState.update { RestoreState.InProgress.Metadata }
        runCatching {
            restoreFromMostRecentBackup()
        }.onFailure {
            Logger.error("Restore from backup failed", it, context = TAG)
        }
        _restoreState.update { RestoreState.Completed }
    }

    private suspend fun restoreFromMostRecentBackup() {
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
            restoreFromRNRemoteBackup()
        } else {
            backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
        }
    }

    private suspend fun restoreFromRNRemoteBackup() = runCatching {
        migrationService.restoreFromRNRemoteBackup()
        walletRepo.loadFromCache()
    }.onFailure {
        Logger.warn("RN remote backup restore failed, falling back to VSS", it, context = TAG)
        backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
    }

    fun onRestoreContinue() = _restoreState.update { RestoreState.Settled }

    fun onRestoreRetry() = viewModelScope.launch(bgDispatcher) {
        _restoreState.update { it.countRetry() }
        setInitNodeLifecycleState()
        lightningRepo.restartNode()
    }

    @Suppress("ForbiddenComment")
    fun onProceedWithoutRestore(onDone: () -> Unit) = viewModelScope.launch {
        // TODO start LDK without trying to restore backup state from VSS if possible
        lightningRepo.stop()
        delay(LOADING_MS.milliseconds)
        _restoreState.update { RestoreState.Settled }
        onDone()
    }

    fun setInitNodeLifecycleState() = lightningRepo.setInitNodeLifecycleState()

    fun start(walletIndex: Int = 0) {
        if (!walletExists || isStarting) return

        viewModelScope.launch(bgDispatcher) {
            isStarting = true
            try {
                waitForRestoreIfNeeded()

                val channelMigration = buildChannelMigrationIfAvailable()
                startNode(walletIndex, channelMigration)
            } finally {
                isStarting = false
            }
        }
    }

    private suspend fun waitForRestoreIfNeeded() {
        if (!_restoreState.value.isOngoing()) return
        withTimeoutOrNull(TIMEOUT_RESTORE_WAIT) {
            _restoreState.first { !it.isOngoing() }
        } ?: Logger.warn("waitForRestoreIfNeeded timeout, proceeding anyway", context = TAG)
    }

    private fun buildChannelMigrationIfAvailable(): ChannelDataMigration? =
        migrationService.peekPendingChannelMigration()?.let { migration ->
            ChannelDataMigration(
                channelManager = migration.channelManager.map { it.toUByte() },
                channelMonitors = migration.channelMonitors.map { monitor -> monitor.map { it.toUByte() } },
            )
        }

    private suspend fun startNode(
        walletIndex: Int = 0,
        channelMigration: ChannelDataMigration?,
    ) {
        lightningRepo.start(walletIndex, channelMigration = channelMigration)
            .onSuccess {
                if (channelMigration != null) {
                    migrationService.consumePendingChannelMigration()
                }
                walletRepo.setWalletExistsState()
                walletRepo.syncBalances()
                if (_restoreState.value.isIdle()) {
                    walletRepo.refreshBip21()
                }
            }
            .onFailure {
                Logger.error("Node startup error", it, context = TAG)
                if (it !is RecoveryModeError) {
                    toaster.error(it)
                }
            }
    }

    fun stop() {
        if (!walletExists) return

        viewModelScope.launch(bgDispatcher) {
            lightningRepo.stop()
                .onFailure {
                    Logger.error("Node stop error", it)
                    toaster.error(it)
                }
        }
    }

    fun refreshState() = viewModelScope.launch {
        walletRepo.syncNodeAndWallet()
            .onFailure {
                Logger.error("Failed to refresh state: ${it.message}", it)
                if (it is CancellationException || it.isTxSyncTimeout()) return@onFailure
                toaster.error(it)
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
                    toaster.info(
                        titleRes = R.string.common__success,
                        bodyRes = R.string.wallet__peer_disconnected,
                    )
                }
                .onFailure {
                    toaster.error(
                        title = ToastText.Resource(R.string.common__error),
                        body = ToastText.Literal(it.message ?: context.getString(R.string.common__error_body)),
                    )
                }
        }
    }

    fun updateBip21Invoice(amountSats: ULong? = walletState.value.bip21AmountSats) = viewModelScope.launch {
        walletRepo.updateBip21Invoice(amountSats).onFailure { error ->
            toaster.error(
                title = ToastText.Resource(R.string.wallet__error_invoice_update),
                body = ToastText.Literal(error.message ?: context.getString(R.string.common__error_body)),
            )
        }
    }

    fun refreshReceiveState() = viewModelScope.launch {
        launch { blocktankRepo.refreshInfo() }
        lightningRepo.updateGeoBlockState()
        walletRepo.refreshBip21()
    }

    fun wipeWallet() = viewModelScope.launch(bgDispatcher) {
        walletRepo.wipeWallet().onFailure {
            toaster.error(it)
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        walletRepo.createWallet(bip39Passphrase)
            .onSuccess {
                backupRepo.scheduleFullBackup()
            }
            .onFailure {
                toaster.error(it)
            }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        _restoreState.update { RestoreState.InProgress.Wallet }

        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase,
        ).onFailure {
            toaster.error(it)
        }
    }

    // region debug methods

    fun addTagToSelected(newTag: String) = viewModelScope.launch {
        walletRepo.addTagToSelected(newTag).onFailure {
            toaster.error(it)
        }
    }

    fun removeTag(tag: String) = viewModelScope.launch {
        walletRepo.removeTag(tag).onFailure {
            toaster.error(it)
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
    data object Completed : RestoreState
    data object Settled : RestoreState

    fun retryCount() = (this as? Retry)?.count ?: 0
    fun countRetry(): RestoreState = if (this is Retry) Retry(count + 1) else Retry(1)
    fun isOngoing() = this is InProgress
    fun isIdle() = this is Initial || this is Settled
}
