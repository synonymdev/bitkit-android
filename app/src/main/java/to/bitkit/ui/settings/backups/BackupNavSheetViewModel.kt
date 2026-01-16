package to.bitkit.ui.settings.backups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.BackupCategory
import to.bitkit.models.HealthState
import to.bitkit.models.Toast
import to.bitkit.repositories.HealthRepo
import to.bitkit.ui.settings.backups.BackupContract.SideEffect
import to.bitkit.ui.settings.backups.BackupContract.UiState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class BackupNavSheetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val keychain: Keychain,
    private val healthRepo: HealthRepo,
    private val cacheStore: CacheStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effects.emit(effect) }

    companion object {
        private const val TAG = "BackupNavSheetViewModel"
    }

    init {
        collectState()
    }

    private fun collectState() {
        viewModelScope.launch {
            combine(healthRepo.healthState, cacheStore.backupStatuses) { healthState, backupStatuses ->
                when (healthState.backups) {
                    HealthState.ERROR -> null
                    else -> {
                        BackupCategory.entries
                            .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
                            .maxOfOrNull { category -> backupStatuses[category]?.synced ?: 0L }
                            .takeIf { it != 0L }
                    }
                }
            }.collect { lastBackupTimeMs ->
                _uiState.update {
                    it.copy(lastBackupTimeMs = lastBackupTimeMs)
                }
            }
        }
    }

    fun loadMnemonicData() = viewModelScope.launch {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)!! // NPE handled with UI toast
            val bip39Passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name) ?: ""

            _uiState.update {
                it.copy(
                    bip39Mnemonic = mnemonic,
                    bip39Passphrase = bip39Passphrase,
                )
            }
        }.onFailure {
            Logger.error("Error loading mnemonic", it, context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.security__mnemonic_error),
                description = context.getString(R.string.security__mnemonic_error_description),
            )
        }
    }

    @Suppress("MagicNumber")
    fun onRevealMnemonic() {
        viewModelScope.launch {
            delay(200) // Small delay for better UX
            _uiState.update { it.copy(showMnemonic = true) }
        }
    }

    fun onShowMnemonicContinue() {
        val state = _uiState.value
        if (state.bip39Passphrase.isNotEmpty()) {
            setEffect(SideEffect.NavigateToShowPassphrase)
        } else {
            setEffect(SideEffect.NavigateToConfirmMnemonic)
        }
    }

    fun onShowPassphraseContinue() {
        setEffect(SideEffect.NavigateToConfirmMnemonic)
    }

    fun onConfirmMnemonicContinue() {
        val state = _uiState.value
        if (state.bip39Passphrase.isNotEmpty()) {
            setEffect(SideEffect.NavigateToConfirmPassphrase)
        } else {
            setEffect(SideEffect.NavigateToWarning)
        }
    }

    fun onPassphraseInput(passphrase: String) {
        _uiState.update { it.copy(enteredPassphrase = passphrase) }
    }

    fun onConfirmPassphraseContinue() {
        setEffect(SideEffect.NavigateToWarning)
    }

    fun onWarningContinue() {
        setEffect(SideEffect.NavigateToSuccess)
    }

    fun onSuccessContinue() {
        viewModelScope.launch {
            settingsStore.update { it.copy(backupVerified = true) }
            setEffect(SideEffect.NavigateToMultipleDevices)
        }
    }

    fun onMultipleDevicesContinue() {
        setEffect(SideEffect.NavigateToMetadata)
    }

    fun onMetadataClose() {
        setEffect(SideEffect.DismissSheet)
    }

    fun resetState() {
        _uiState.update { UiState() }
    }
}

interface BackupContract {
    companion object {
        private val PLACEHOLDER_MNEMONIC = List(24) { "secret" }.joinToString(" ")
    }

    data class UiState(
        val bip39Mnemonic: String = PLACEHOLDER_MNEMONIC,
        val bip39Passphrase: String = "",
        val showMnemonic: Boolean = false,
        val enteredPassphrase: String = "",
        val lastBackupTimeMs: Long? = null,
    )

    sealed interface SideEffect {
        data object NavigateToShowPassphrase : SideEffect
        data object NavigateToConfirmMnemonic : SideEffect
        data object NavigateToConfirmPassphrase : SideEffect
        data object NavigateToWarning : SideEffect
        data object NavigateToSuccess : SideEffect
        data object NavigateToMultipleDevices : SideEffect
        data object NavigateToMetadata : SideEffect
        data object DismissSheet : SideEffect
    }
}
