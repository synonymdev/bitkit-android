package to.bitkit.ui.settings.quickPay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import javax.inject.Inject

@HiltViewModel
class QuickPaySettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
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
        viewModelScope.launch { settingsStore.update(change::applyTo) }
    }

    fun onAuthorizationCancelled() = authorization.update { null }

    /**
     * Applies [change] after the same PIN or biometric check as Send when the user requires a PIN for payments and
     * the change enables QuickPay or raises one of its limits. Disabling and lowering always apply at once.
     */
    private fun requestChange(change: QuickPayChange) {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            val isGated = settings.isPinEnabled && settings.isPinForPaymentsEnabled
            if (!isGated || !change.isRaise(settings)) {
                settingsStore.update(change::applyTo)
                return@launch
            }
            authorization.compareAndSet(null, QuickPayAuthorization(change, settings.isBiometricEnabled))
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

/** A pending PIN or biometric check before [change] is saved. */
@Immutable
data class QuickPayAuthorization(
    val change: QuickPayChange,
    val useBiometrics: Boolean,
)

/** One QuickPay setting the user asked to change, with its new value. */
sealed interface QuickPayChange {
    fun isRaise(settings: SettingsData): Boolean
    fun applyTo(settings: SettingsData): SettingsData

    data class Enabled(val value: Boolean) : QuickPayChange {
        override fun isRaise(settings: SettingsData) = value && !settings.isQuickPayEnabled
        override fun applyTo(settings: SettingsData) = settings.copy(isQuickPayEnabled = value)
    }

    data class Amount(val value: Int) : QuickPayChange {
        override fun isRaise(settings: SettingsData) = value > settings.quickPayAmount
        override fun applyTo(settings: SettingsData) = settings.copy(quickPayAmount = value)
    }

    data class DailyLimitMultiplier(val value: Int) : QuickPayChange {
        override fun isRaise(settings: SettingsData) = value > settings.quickPayDailyLimitMultiplier
        override fun applyTo(settings: SettingsData) = settings.copy(quickPayDailyLimitMultiplier = value)
    }
}
