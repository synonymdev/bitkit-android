package to.bitkit.ui.settings.paymentPreference

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class PaymentPreferenceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PaymentPreferenceUiState())
    val uiState: StateFlow<PaymentPreferenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settingsStore.data, pubkyRepo.isAuthenticated) { settings, isAuthenticated ->
                settings to isAuthenticated
            }.collect { (settings, isAuthenticated) ->
                _uiState.update {
                    it.copy(
                        lightningEnabled = settings.publicPaykitLightningEnabled,
                        onchainEnabled = settings.publicPaykitOnchainEnabled,
                        privateContactsEnabled = settings.sharesPrivatePaykitEndpoints,
                        publicContactsEnabled = settings.sharesPublicPaykitEndpoints,
                        hasPubkyProfile = isAuthenticated,
                    )
                }
            }
        }
    }

    fun setLightningEnabled(isEnabled: Boolean) {
        updatePaymentMethod(lightningEnabled = isEnabled)
    }

    fun setOnchainEnabled(isEnabled: Boolean) {
        updatePaymentMethod(onchainEnabled = isEnabled)
    }

    fun setPrivateContactsEnabled(isEnabled: Boolean) {
        if (_uiState.value.isUpdatingPrivateContacts) return
        if (isEnabled && !_uiState.value.hasPubkyProfile) {
            viewModelScope.launch { showSyncError(PublicPaykitError.SessionNotActive) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingPrivateContacts = true) }
            val previous = settingsStore.data.first()
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPrivatePaykitEndpoints = isEnabled,
                )
            }

            val result = if (isEnabled) {
                privatePaykitRepo.enableSharingAndPrepareSavedContacts(contactPublicKeys())
            } else {
                privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contactPublicKeys())
            }

            result.exceptionOrNull()?.let {
                if (!isEnabled) {
                    privatePaykitRepo.setContactSharingCleanupPending(true)
                }
                if (isEnabled) {
                    settingsStore.update { settings ->
                        settings.copy(sharesPrivatePaykitEndpoints = previous.sharesPrivatePaykitEndpoints)
                    }
                }
                showSyncError(it)
            }
            _uiState.update { it.copy(isUpdatingPrivateContacts = false) }
        }
    }

    fun setPublicContactsEnabled(isEnabled: Boolean) {
        if (_uiState.value.isUpdatingPublicContacts) return
        if (isEnabled && !_uiState.value.hasPubkyProfile) {
            viewModelScope.launch { showSyncError(PublicPaykitError.SessionNotActive) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingPublicContacts = true) }
            val previous = settingsStore.data.first()
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = isEnabled,
                )
            }

            publicPaykitRepo.syncPublishedEndpoints(publish = isEnabled).exceptionOrNull()?.let {
                settingsStore.update { settings ->
                    settings.copy(sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints)
                }
                showSyncError(it)
            }
            _uiState.update { it.copy(isUpdatingPublicContacts = false) }
        }
    }

    private fun updatePaymentMethod(
        lightningEnabled: Boolean = _uiState.value.lightningEnabled,
        onchainEnabled: Boolean = _uiState.value.onchainEnabled,
    ) {
        if (_uiState.value.isUpdatingPaymentOptions) return
        if (!lightningEnabled && !onchainEnabled) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.common__error),
                    description = context.getString(R.string.settings__payment_pref_keep_one),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingPaymentOptions = true) }
            val previous = settingsStore.data.first()
            settingsStore.update {
                it.copy(
                    publicPaykitLightningEnabled = lightningEnabled,
                    publicPaykitOnchainEnabled = onchainEnabled,
                )
            }

            val result = refreshPublishedPreferences()
            result.exceptionOrNull()?.let {
                settingsStore.update { settings ->
                    settings.copy(
                        publicPaykitLightningEnabled = previous.publicPaykitLightningEnabled,
                        publicPaykitOnchainEnabled = previous.publicPaykitOnchainEnabled,
                    )
                }
                refreshPublishedPreferences()
                showSyncError(it)
            }
            _uiState.update { it.copy(isUpdatingPaymentOptions = false) }
        }
    }

    private suspend fun refreshPublishedPreferences(): Result<Unit> = runCatching {
        val settings = settingsStore.data.first()
        if (settings.sharesPublicPaykitEndpoints) {
            publicPaykitRepo.syncCurrentPublishedEndpoints(
                forceRefreshLightning = true,
                requireEndpoint = true,
            ).getOrThrow()
        }
        if (settings.sharesPrivatePaykitEndpoints) {
            privatePaykitRepo.prepareSavedContacts(contactPublicKeys(), requireImmediatePublication = true).getOrThrow()
        }
    }

    private fun contactPublicKeys(): List<String> =
        pubkyRepo.contacts.value.map { it.publicKey }

    private suspend fun showSyncError(error: Throwable) {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = when (error) {
                PublicPaykitError.InvalidPayload ->
                    context.getString(R.string.profile__pay_contacts_error_invalid_payload)

                PublicPaykitError.NoSupportedEndpoint ->
                    context.getString(R.string.profile__pay_contacts_error_no_endpoint)

                PublicPaykitError.SessionNotActive -> context.getString(R.string.profile__session_expired)
                PublicPaykitError.WalletNotReady -> context.getString(R.string.profile__pay_contacts_error_wallet)
                else -> context.getString(R.string.common__error_body)
            },
        )
    }
}

@Immutable
data class PaymentPreferenceUiState(
    val lightningEnabled: Boolean = true,
    val onchainEnabled: Boolean = true,
    val privateContactsEnabled: Boolean = false,
    val publicContactsEnabled: Boolean = false,
    val hasPubkyProfile: Boolean = false,
    val isUpdatingPaymentOptions: Boolean = false,
    val isUpdatingPrivateContacts: Boolean = false,
    val isUpdatingPublicContacts: Boolean = false,
)
