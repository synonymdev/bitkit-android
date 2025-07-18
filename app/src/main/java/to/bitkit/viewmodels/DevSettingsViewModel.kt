package to.bitkit.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.testNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class DevSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val firebaseMessaging: FirebaseMessaging,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val widgetsStore: WidgetsStore,
    private val currencyRepo: CurrencyRepo,
    private val logsRepo: LogsRepo,
    private val cacheStore: CacheStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    fun openChannel() {
        viewModelScope.launch(bgDispatcher) {
            val peer = lightningRepo.getPeers()?.firstOrNull()

            if (peer == null) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "No peer connected")
                return@launch
            }

            lightningRepo.openChannel(peer, 50_000u, 25_000u)
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Channel pending")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun registerForNotifications() {
        viewModelScope.launch {
            lightningRepo.registerForNotifications()
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Registered for notifications")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun testLspNotification() {
        viewModelScope.launch(bgDispatcher) {
            runCatching {
                testNotification(
                    deviceToken = firebaseMessaging.token.await(),
                    secretMessage = "hello",
                    notificationType = "incomingHtlc",
                    customUrl = Env.blocktankPushNotificationServer,
                )

            }.onFailure {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Error testing LSP notification")
            }
        }
    }

    fun fakeBgTransaction() {
        viewModelScope.launch {
            NewTransactionSheetDetails.save(
                context,
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.RECEIVED,
                    sats = 123456789,
                )
            )
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Restart to see new transaction sheet")
        }
    }

    fun resetWidgetsState() {
        viewModelScope.launch {
            widgetsStore.reset()
        }
    }

    fun refreshCurrencyRates() {
        viewModelScope.launch {
            currencyRepo.triggerRefresh()
        }
    }

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { uri -> onReady(uri) }
                .onFailure { err ->
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }

    fun resetBackupState() {
        viewModelScope.launch {
            cacheStore.update { it.copy(backupStatuses = mapOf()) }
        }
    }

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }

    fun wipeWallet() {
        viewModelScope.launch {
            walletRepo.wipeWallet()
        }
    }
}
