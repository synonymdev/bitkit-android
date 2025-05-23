package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    val showEmptyState: StateFlow<Boolean> = settingsStore.data.map { it.showEmptyState }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setShowEmptyState(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(showEmptyState = value) }
        }
    }

    val hasSeenSpendingIntro: StateFlow<Boolean> = settingsStore.data.map { it.hasSeenSpendingIntro }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenSpendingIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSpendingIntro = value) }
        }
    }

    val hasSeenTransferIntro: StateFlow<Boolean> = settingsStore.data.map { it.hasSeenTransferIntro }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenTransferIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenTransferIntro = value) }
        }
    }

    val hasSeenSavingsIntro: StateFlow<Boolean> = settingsStore.data.map { it.hasSeenSavingsIntro }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenSavingsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenSavingsIntro = value) }
        }
    }

    val hasSeenProfileIntro: StateFlow<Boolean> = settingsStore.data.map { it.hasSeenProfileIntro }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenProfileIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hasSeenProfileIntro = value) }
        }
    }

    val quickpayIntroSeen: StateFlow<Boolean> = settingsStore.data.map { it.quickPayIntroSeen }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setQuickPayIntroSeen(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickPayIntroSeen = value) }
        }
    }

    val isPinOnIdleEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isPinOnIdleEnabled }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setIsPinOnIdleEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinOnIdleEnabled = value) }
        }
    }

    val isPinForPaymentsEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isPinForPaymentsEnabled }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setIsPinForPaymentsEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinForPaymentsEnabled = value) }
        }
    }

    val defaultTransactionSpeed = settingsStore.data.map { it.defaultTransactionSpeed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionSpeed.Medium)

    fun setDefaultTransactionSpeed(speed: TransactionSpeed) {
        viewModelScope.launch {
            settingsStore.update { it.copy(defaultTransactionSpeed = speed) }
        }
    }

    val isDevModeEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isDevModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setIsDevModeEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isDevModeEnabled = value) }
        }
    }

    val isPinEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isPinEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPinOnLaunchEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isPinOnLaunchEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setIsPinOnLaunchEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinOnLaunchEnabled = value) }
        }
    }

    val isBiometricEnabled: StateFlow<Boolean> = settingsStore.data.map { it.isBiometricEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setIsBiometricEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isBiometricEnabled = value) }
        }
    }
}
