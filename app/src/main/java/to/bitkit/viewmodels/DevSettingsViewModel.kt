package to.bitkit.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.testNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

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
