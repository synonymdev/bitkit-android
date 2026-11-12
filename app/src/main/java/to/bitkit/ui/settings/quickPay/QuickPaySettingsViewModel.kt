package to.bitkit.ui.settings.quickPay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.QuickPayAuthorization
import to.bitkit.repositories.QuickPayChange
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPaySettingsViewModel @Inject constructor(
    settingsStore: SettingsStore,
    private val quickPayRepo: QuickPayRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "QuickPaySettingsViewModel"

        /** Keeps the state alive across short configuration changes. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    private val authorization = MutableStateFlow<QuickPayAuthorization?>(null)

    val uiState: StateFlow<QuickPaySettingsUiState> = combine(
        settingsStore.data,
        authorization,
    ) { settings, authorization ->
        QuickPaySettingsUiState(
            isQuickPayEnabled = settings.isQuickPayEnabled,
            quickPayAmount = settings.quickPayAmount,
            quickPayDailyLimitMultiplier = settings.quickPayDailyLimitMultiplier,
            authorization = authorization,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), QuickPaySettingsUiState())

    fun setQuickPayEnabled(enabled: Boolean) = requestChange(QuickPayChange.Enabled(enabled))

    fun setQuickPayAmount(amount: Int) = requestChange(QuickPayChange.Amount(amount))

    fun setQuickPayDailyLimitMultiplier(multiplier: Int) =
        requestChange(QuickPayChange.DailyLimitMultiplier(multiplier))

    fun onAuthorized() {
        val change = authorization.getAndUpdate { null }?.change ?: return
        viewModelScope.launch {
            quickPayRepo.applySettingsChange(change)
                .onFailure { Logger.error("Failed to save QuickPay change '$change'", it, context = TAG) }
        }
    }

    fun onAuthorizationCancelled() = authorization.update { null }

    private fun requestChange(change: QuickPayChange) {
        viewModelScope.launch {
            quickPayRepo.requestSettingsChange(change)
                .onSuccess { pending -> pending?.let { authorization.compareAndSet(null, it) } }
                .onFailure { Logger.error("Failed to request QuickPay change '$change'", it, context = TAG) }
        }
    }
}

@Immutable
data class QuickPaySettingsUiState(
    val isQuickPayEnabled: Boolean = false,
    val quickPayAmount: Int = 5,
    val quickPayDailyLimitMultiplier: Int = 5,
    val authorization: QuickPayAuthorization? = null,
)
