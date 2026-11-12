package to.bitkit.ui.settings.quickPay

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    private val authorization = MutableStateFlow<QuickPayAuthorization?>(null)
    private var requestGeneration = 0

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
        val authorized = authorization.getAndUpdate { null } ?: return
        viewModelScope.launch {
            quickPayRepo.applyAuthorizedSettingsChange(authorized)
                .onFailure {
                    Logger.error("Failed to save QuickPay change '${authorized.change}'", it, context = TAG)
                    toastError()
                }
        }
    }

    fun onAuthorizationCancelled() {
        requestGeneration++
        authorization.update { null }
        quickPayRepo.cancelSettingsChange()
    }

    private fun requestChange(change: QuickPayChange) {
        val generation = requestGeneration
        viewModelScope.launch {
            quickPayRepo.requestSettingsChange(change)
                .onSuccess { pending ->
                    if (pending == null) {
                        authorization.getAndUpdate { null }?.let(quickPayRepo::cancelSettingsChange)
                        return@onSuccess
                    }
                    if (generation != requestGeneration) {
                        quickPayRepo.cancelSettingsChange(pending)
                        return@onSuccess
                    }
                    authorization.update { pending }
                }
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
