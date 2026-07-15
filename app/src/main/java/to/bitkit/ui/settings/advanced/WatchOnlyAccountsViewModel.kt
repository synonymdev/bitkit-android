package to.bitkit.ui.settings.advanced

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.Toast
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.localizedPubkyAuthMessage
import javax.inject.Inject

@HiltViewModel
class WatchOnlyAccountsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo,
    lightningService: LightningService,
) : ViewModel() {
    private val walletIndex = lightningService.currentWalletIndex
    private val _updatingAccountId = MutableStateFlow<String?>(null)
    val updatingAccountId = _updatingAccountId.asStateFlow()

    val accounts = watchOnlyAccountRepo.accounts
        .map { accounts -> accounts.filter { it.walletIndex == walletIndex }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun rename(account: WatchOnlyAccountRecord, name: String) {
        viewModelScope.launch {
            runSuspendCatching { watchOnlyAccountRepo.rename(account.id, name) }
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.watch_only_accounts__name_saved),
                    )
                }
                .onFailure { showError(it) }
        }
    }

    fun setTrackingEnabled(account: WatchOnlyAccountRecord, enabled: Boolean) {
        viewModelScope.launch {
            _updatingAccountId.update { account.id }
            try {
                runSuspendCatching {
                    watchOnlyAccountRepo.setTrackingEnabled(account.id, enabled)
                }.onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(
                            if (enabled) {
                                R.string.watch_only_accounts__tracking_enabled
                            } else {
                                R.string.watch_only_accounts__tracking_disabled
                            }
                        ),
                    )
                }.onFailure { error -> showError(error) }
            } finally {
                _updatingAccountId.update { null }
            }
        }
    }

    private suspend fun showError(error: Throwable) {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = error.localizedPubkyAuthMessage(context),
        )
    }
}
