package to.bitkit.ui.settings.quickPay

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.QuickPayAuthorization
import to.bitkit.repositories.QuickPayChange
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPaySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsStore: SettingsStore,
    private val quickPayRepo: QuickPayRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "QuickPaySettingsViewModel"

        /** Keeps the state alive across short configuration changes. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    val uiState: StateFlow<QuickPaySettingsUiState> = combine(
        settingsStore.data,
        quickPayRepo.pendingSettingsChange,
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

    fun onAuthorized(authorization: QuickPayAuthorization) {
        viewModelScope.launch {
            quickPayRepo.applyAuthorizedSettingsChange(authorization)
                .onFailure {
                    Logger.error("Failed to save QuickPay change '${authorization.change}'", it, context = TAG)
                    toastError()
                }
        }
    }

    fun onAuthorizationCancelled() = quickPayRepo.cancelSettingsChange()

    private fun requestChange(change: QuickPayChange) {
        val result = quickPayRepo.requestSettingsChange(change)
        viewModelScope.launch {
            result.await()
                .onFailure {
                    Logger.error("Failed to request QuickPay change '$change'", it, context = TAG)
                    toastError()
                }
        }
    }

    private suspend fun toastError() = ToastEventBus.send(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.common__error),
        description = context.getString(R.string.common__error_body),
    )
}

@Immutable
data class QuickPaySettingsUiState(
    val isQuickPayEnabled: Boolean = false,
    val quickPayAmount: Int = 5,
    val quickPayDailyLimitMultiplier: Int = 5,
    val authorization: QuickPayAuthorization? = null,
)
