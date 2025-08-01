package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.models.TransactionSpeed
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    fun reset() = viewModelScope.launch { settingsStore.reset() }

    val hasSeenSpendingIntro = settingsStore.data.map { it.hasSeenSpendingIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenSpendingIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSpendingIntro = value) }
        }
    }

    val hasSeenWidgetsIntro = settingsStore.data.map { it.hasSeenWidgetsIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenWidgetsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenWidgetsIntro = value) }
        }
    }

    val hasSeenTransferIntro = settingsStore.data.map { it.hasSeenTransferIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenTransferIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenTransferIntro = value) }
        }
    }

    val hasSeenSavingsIntro = settingsStore.data.map { it.hasSeenSavingsIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenSavingsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSavingsIntro = value) }
        }
    }

    val hasSeenShopIntro = settingsStore.data.map { it.hasSeenShopIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenShopIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenShopIntro = value) }
        }
    }

    val hasSeenProfileIntro = settingsStore.data.map { it.hasSeenProfileIntro }
        .asStateFlow(initialValue = false)

    fun setHasSeenProfileIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenProfileIntro = value) }
        }
    }

    val quickPayIntroSeen = settingsStore.data.map { it.quickPayIntroSeen }
        .asStateFlow(initialValue = false)

    fun setQuickPayIntroSeen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickPayIntroSeen = value) }
        }
    }

    val isPinOnIdleEnabled = settingsStore.data.map { it.isPinOnIdleEnabled }
        .asStateFlow(initialValue = false)

    fun setIsPinOnIdleEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinOnIdleEnabled = value) }
        }
    }

    val isPinForPaymentsEnabled = settingsStore.data.map { it.isPinForPaymentsEnabled }
        .asStateFlow(initialValue = false)

    fun setIsPinForPaymentsEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinForPaymentsEnabled = value) }
        }
    }

    val defaultTransactionSpeed = settingsStore.data.map { it.defaultTransactionSpeed }
        .asStateFlow(initialValue = TransactionSpeed.Medium)

    fun setDefaultTransactionSpeed(speed: TransactionSpeed) {
        viewModelScope.launch {
            settingsStore.update { it.copy(defaultTransactionSpeed = speed) }
        }
    }

    val isDevModeEnabled = settingsStore.data.map { it.isDevModeEnabled }
        .asStateFlow(initialValue = false)

    fun setIsDevModeEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isDevModeEnabled = value) }
        }
    }

    val isPinEnabled = settingsStore.data.map { it.isPinEnabled }
        .asStateFlow(SharingStarted.Eagerly, false)

    val isPinOnLaunchEnabled = settingsStore.data.map { it.isPinOnLaunchEnabled }
        .asStateFlow(SharingStarted.Eagerly, false)

    fun setIsPinOnLaunchEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinOnLaunchEnabled = value) }
        }
    }

    val isBiometricEnabled = settingsStore.data.map { it.isBiometricEnabled }
        .asStateFlow(SharingStarted.Eagerly, false)

    fun setIsBiometricEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isBiometricEnabled = value) }
        }
    }

    val showWidgets = settingsStore.data.map { it.showWidgets }
        .asStateFlow(initialValue = false)

    fun setShowWidgets(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(showWidgets = value) }
        }
    }

    val showWidgetTitles = settingsStore.data.map { it.showWidgetTitles }
        .asStateFlow(initialValue = false)

    fun setShowWidgetTitles(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(showWidgetTitles = value) }
        }
    }

    val lastUsedTags = settingsStore.data.map { it.lastUsedTags }
        .asStateFlow(initialValue = emptyList())

    fun deleteLastUsedTag(tag: String) {
        viewModelScope.launch {
            settingsStore.deleteLastUsedTag(tag)
        }
    }

    val isQuickpayEnabled = settingsStore.data.map { it.isQuickPayEnabled }
        .asStateFlow(initialValue = false)

    fun setIsQuickPayEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isQuickPayEnabled = value) }
        }
    }

    val quickPayAmount = settingsStore.data.map { it.quickPayAmount }
        .asStateFlow(initialValue = 5)

    fun setQuickPayAmount(value: Int) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickPayAmount = value) }
        }
    }

    val enableSwipeToHideBalance = settingsStore.data.map { it.enableSwipeToHideBalance }
        .asStateFlow(initialValue = true)

    fun setEnableSwipeToHideBalance(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    enableSwipeToHideBalance = value,
                    hideBalance = if (!value) false else it.hideBalance,
                    hideBalanceOnOpen = if (!value) false else it.hideBalanceOnOpen,
                )
            }
        }
    }

    val hideBalance = settingsStore.data.map { it.hideBalance }
        .asStateFlow(initialValue = false)

    fun setHideBalance(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hideBalance = value) }
        }
    }

    val hideBalanceOnOpen = settingsStore.data.map { it.hideBalanceOnOpen }
        .asStateFlow(initialValue = false)

    fun setHideBalanceOnOpen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hideBalanceOnOpen = value) }
        }
    }

    val enableAutoReadClipboard = settingsStore.data.map { it.enableAutoReadClipboard }
        .asStateFlow(initialValue = false)

    fun setEnableAutoReadClipboard(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(enableAutoReadClipboard = value) }
        }
    }

    val enableSendAmountWarning = settingsStore.data.map { it.enableSendAmountWarning }
        .asStateFlow(initialValue = false)

    fun setEnableSendAmountWarning(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(enableSendAmountWarning = value) }
        }
    }

    // utils
    private fun <T> Flow<T>.asStateFlow(
        started: SharingStarted = SharingStarted.WhileSubscribed(5000),
        initialValue: T,
    ): StateFlow<T> = stateIn(viewModelScope, started, initialValue)
}
