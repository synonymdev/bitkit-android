package to.bitkit.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.LegacyRnCloseRecoveryScanResult
import com.synonym.bitkitcore.LegacyRnCloseRecoverySweepPreview
import com.synonym.bitkitcore.testNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import to.bitkit.R
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.WidgetsStore
import to.bitkit.env.Env
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

data class LegacyRnRecoveryUiState(
    val indexLimit: String = "10000",
    val isScanning: Boolean = false,
    val isPreparing: Boolean = false,
    val isBroadcasting: Boolean = false,
    val scanResult: LegacyRnCloseRecoveryScanResult? = null,
    val sweepPreview: LegacyRnCloseRecoverySweepPreview? = null,
    val broadcastTxid: String? = null,
    val error: String? = null,
)

@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class DevSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseMessaging: FirebaseMessaging,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val widgetsStore: WidgetsStore,
    private val currencyRepo: CurrencyRepo,
    private val logsRepo: LogsRepo,
    private val cacheStore: CacheStore,
    private val blocktankRepo: BlocktankRepo,
    private val appDb: AppDb,
) : ViewModel() {
    private val _legacyRnRecoveryState = MutableStateFlow(LegacyRnRecoveryUiState())
    val legacyRnRecoveryState = _legacyRnRecoveryState.asStateFlow()

    fun setLegacyRnRecoveryIndexLimit(value: String) {
        val filtered = value.filter { it.isDigit() }
        _legacyRnRecoveryState.update {
            it.copy(
                indexLimit = filtered,
                scanResult = null,
                sweepPreview = null,
                broadcastTxid = null,
                error = null,
            )
        }
    }

    fun scanLegacyRnRecovery() = viewModelScope.launch {
        val indexLimit = legacyRnRecoveryIndexLimitOrNull() ?: return@launch

        _legacyRnRecoveryState.update {
            it.copy(
                isScanning = true,
                scanResult = null,
                sweepPreview = null,
                broadcastTxid = null,
                error = null,
            )
        }

        walletRepo.scanLegacyRnNativeSegwitRecoveryFunds(indexLimit)
            .onSuccess { result ->
                _legacyRnRecoveryState.update { it.copy(scanResult = result) }
            }
            .onFailure { error ->
                _legacyRnRecoveryState.update { it.copy(error = error.recoveryMessage()) }
            }

        _legacyRnRecoveryState.update { it.copy(isScanning = false) }
    }

    fun prepareLegacyRnRecoverySweep() = viewModelScope.launch {
        val indexLimit = legacyRnRecoveryIndexLimitOrNull() ?: return@launch

        _legacyRnRecoveryState.update {
            it.copy(
                isPreparing = true,
                sweepPreview = null,
                broadcastTxid = null,
                error = null,
            )
        }

        val feeRate = lightningRepo.getFeeRateForSpeed(TransactionSpeed.default()).getOrNull()?.toUInt()
        walletRepo.prepareLegacyRnNativeSegwitRecoverySweep(
            indexLimit = indexLimit,
            feeRateSatsPerVbyte = feeRate,
        ).onSuccess { preview ->
            _legacyRnRecoveryState.update { it.copy(sweepPreview = preview) }
        }.onFailure { error ->
            _legacyRnRecoveryState.update { it.copy(error = error.recoveryMessage()) }
        }

        _legacyRnRecoveryState.update { it.copy(isPreparing = false) }
    }

    fun broadcastLegacyRnRecoverySweep() = viewModelScope.launch {
        val preview = _legacyRnRecoveryState.value.sweepPreview ?: return@launch

        _legacyRnRecoveryState.update { it.copy(isBroadcasting = true, error = null) }

        walletRepo.broadcastLegacyRnNativeSegwitRecoverySweep(preview.txHex)
            .onSuccess { txid ->
                _legacyRnRecoveryState.update { it.copy(broadcastTxid = txid) }
                ToastEventBus.send(type = Toast.ToastType.SUCCESS, title = "Sweep broadcast", description = txid)
            }
            .onFailure { error ->
                _legacyRnRecoveryState.update { it.copy(error = error.recoveryMessage()) }
            }

        _legacyRnRecoveryState.update { it.copy(isBroadcasting = false) }
    }

    private fun legacyRnRecoveryIndexLimitOrNull(): UInt? {
        val indexLimit = _legacyRnRecoveryState.value.indexLimit.toUIntOrNull()
        if (indexLimit == null || indexLimit == 0u) {
            _legacyRnRecoveryState.update { it.copy(error = "Enter a valid index limit.") }
            return null
        }
        return indexLimit
    }

    private fun Throwable.recoveryMessage(): String = localizedMessage ?: message ?: "Unknown error"

    fun openChannel() = viewModelScope.launch {
        val peer = lightningRepo.getPeers()?.firstOrNull()

        if (peer == null) {
            ToastEventBus.send(type = Toast.ToastType.WARNING, title = "No peer connected")
            return@launch
        }

        lightningRepo.openChannel(peer, 50_000u, 25_000u)
            .onSuccess {
                ToastEventBus.send(type = Toast.ToastType.INFO, title = "Channel pending")
            }
            .onFailure { ToastEventBus.send(it) }
    }

    fun registerForNotifications() = viewModelScope.launch {
        lightningRepo.registerForNotifications()
            .onSuccess {
                ToastEventBus.send(type = Toast.ToastType.INFO, title = "Registered for notifications")
            }
            .onFailure { ToastEventBus.send(it) }
    }

    fun testLspNotification() = viewModelScope.launch {
        runCatching {
            testNotification(
                deviceToken = firebaseMessaging.token.await(),
                secretMessage = "hello",
                notificationType = "incomingHtlc",
                customUrl = Env.blocktankNotificationApiUrl,
            )
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "LSP notification sent to this device")
        }.onFailure {
            ToastEventBus.send(type = Toast.ToastType.WARNING, title = "Error testing LSP notification")
        }
    }

    fun fakeBgReceive() = viewModelScope.launch {
        cacheStore.setBackgroundReceive(
            NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 21_000_000,
            )
        )
    }

    fun resetWidgetsState() = viewModelScope.launch {
        widgetsStore.reset()
    }

    fun refreshCurrencyRates() = viewModelScope.launch {
        currencyRepo.triggerRefresh()
    }

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { uri -> onReady(uri) }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }

    fun resetBackupState() = viewModelScope.launch {
        cacheStore.update { it.copy(backupStatuses = mapOf()) }
    }

    fun wipeWallet() = viewModelScope.launch {
        walletRepo.wipeWallet()
    }

    fun resetCacheStore() = viewModelScope.launch {
        cacheStore.reset()
    }

    fun resetDatabase() = viewModelScope.launch {
        appDb.clearAllTables()
    }

    fun resetBlocktankState() = viewModelScope.launch {
        blocktankRepo.resetState()
    }

    fun wipeLogs() = Logger.reset()
}
