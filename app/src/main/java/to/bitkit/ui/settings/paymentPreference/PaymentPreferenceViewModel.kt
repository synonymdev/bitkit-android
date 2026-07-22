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
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.runSuspendCatching
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
    private val privateContactsPendingValue = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch {
            combine(
                settingsStore.data,
                pubkyRepo.isAuthenticated,
                privateContactsPendingValue,
            ) { settings, isAuthenticated, pendingPrivateContactsEnabled ->
                val canUsePrivateContacts = isAuthenticated && pubkyRepo.hasSecretKey()
                if (!canUsePrivateContacts && settings.sharesPrivatePaykitEndpoints) {
                    settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                }
                PaymentPreferenceStateSource(
                    settings = settings,
                    isAuthenticated = isAuthenticated,
                    canUsePrivateContacts = canUsePrivateContacts,
                    pendingPrivateContactsEnabled = pendingPrivateContactsEnabled,
                )
            }.collect { stateSource ->
                _uiState.update { it.from(stateSource) }
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
        if (isEnabled && !_uiState.value.canUsePrivateContacts) {
            viewModelScope.launch { showSyncError(PublicPaykitError.SessionNotActive) }
            return
        }
        viewModelScope.launch {
            val previous = settingsStore.data.first()
            privateContactsPendingValue.update { _uiState.value.privateContactsEnabled }
            _uiState.update { it.copy(isUpdatingPrivateContacts = true) }
            val result = runSuspendCatching {
                settingsStore.update {
                    it.copy(
                        hasConfirmedPublicPaykitEndpoints = true,
                        sharesPrivatePaykitEndpoints = isEnabled,
                    )
                }
                if (isEnabled) {
                    privatePaykitRepo.enableSharingAndPrepareSavedContacts(
                        publicKeys = contactPublicKeys(),
                        requireImmediatePublication = true,
                    ).getOrThrow()
                } else {
                    privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contactPublicKeys()).getOrThrow()
                }
                if (!previous.sharesPublicPaykitEndpoints) {
                    publicPaykitRepo.syncLocalReceiverMarker(privateSharingEnabled = isEnabled).getOrThrow()
                }
            }

            result.exceptionOrNull()?.let {
                rollbackPrivateContactsPreference(
                    requestedEnabled = isEnabled,
                    previous = previous,
                    error = it,
                )
                showSyncError(it)
            }
            privateContactsPendingValue.update { null }
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

            publicPaykitRepo.syncPublishedEndpoints(publish = isEnabled).exceptionOrNull()?.let { error ->
                rollbackPublicContactsPreference(previous, error)
                showSyncError(error)
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

    private suspend fun refreshPublishedPreferences(): Result<Unit> = runSuspendCatching {
        val settings = settingsStore.data.first()
        if (settings.sharesPublicPaykitEndpoints) {
            publicPaykitRepo.syncCurrentPublishedEndpoints(
                forceRefreshLightning = true,
                requireEndpoint = true,
            ).getOrThrow()
        }
        if (settings.sharesPrivatePaykitEndpoints) {
            if (pubkyRepo.hasSecretKey()) {
                privatePaykitRepo.prepareSavedContacts(
                    publicKeys = contactPublicKeys(),
                    requireImmediatePublication = true,
                ).getOrThrow()
            } else {
                settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                publicPaykitRepo.syncLocalReceiverMarker(privateSharingEnabled = false).getOrThrow()
            }
        }
    }

    private fun contactPublicKeys(): List<String> =
        pubkyRepo.contacts.value.map { it.publicKey }

    private suspend fun rollbackPublicContactsPreference(previous: SettingsData, error: Throwable) {
        runSuspendCatching {
            settingsStore.update { settings ->
                settings.copy(sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints)
            }
        }.onFailure(error::addSuppressed)

        publicPaykitRepo.syncPublishedEndpoints(publish = previous.sharesPublicPaykitEndpoints)
            .onFailure { rollbackError ->
                error.addSuppressed(rollbackError)
                runSuspendCatching {
                    settingsStore.update { it.copy(publicPaykitCleanupPending = true) }
                }.onFailure(error::addSuppressed)
            }
    }

    private suspend fun rollbackPrivateContactsPreference(
        requestedEnabled: Boolean,
        previous: SettingsData,
        error: Throwable,
    ) {
        val contacts = contactPublicKeys()
        if (!requestedEnabled && previous.sharesPrivatePaykitEndpoints) {
            restorePrivateContactsPreference(contacts, error)
            return
        }

        runSuspendCatching {
            settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = previous.sharesPrivatePaykitEndpoints) }
        }.onFailure(error::addSuppressed)
        if (!previous.sharesPublicPaykitEndpoints) {
            publicPaykitRepo.syncLocalReceiverMarker(privateSharingEnabled = previous.sharesPrivatePaykitEndpoints)
                .onFailure(error::addSuppressed)
        }

        if (requestedEnabled && !previous.sharesPrivatePaykitEndpoints) {
            privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
                .onFailure(error::addSuppressed)
        }
    }

    private suspend fun restorePrivateContactsPreference(
        contacts: List<String>,
        error: Throwable,
    ) {
        val preferenceRestored = updatePrivateContactsPreference(isEnabled = true, error = error)
        if (!preferenceRestored) return

        privatePaykitRepo.prepareSavedContacts(
            publicKeys = contacts,
            requireImmediatePublication = true,
        ).exceptionOrNull()?.let {
            error.addSuppressed(it)
            updatePrivateContactsPreference(isEnabled = false, error = error)
            publicPaykitRepo.syncLocalReceiverMarker().onFailure(error::addSuppressed)
            return
        }

        privatePaykitRepo.setContactSharingCleanupPending(false).exceptionOrNull()?.let {
            error.addSuppressed(it)
            updatePrivateContactsPreference(isEnabled = false, error = error)
        }
        publicPaykitRepo.syncLocalReceiverMarker().onFailure(error::addSuppressed)
    }

    private suspend fun updatePrivateContactsPreference(
        isEnabled: Boolean,
        error: Throwable,
    ): Boolean = runSuspendCatching {
        settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = isEnabled) }
    }.onFailure(error::addSuppressed).isSuccess

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
    val canUsePrivateContacts: Boolean = false,
    val isUpdatingPaymentOptions: Boolean = false,
    val isUpdatingPrivateContacts: Boolean = false,
    val isUpdatingPublicContacts: Boolean = false,
)

private data class PaymentPreferenceStateSource(
    val settings: SettingsData,
    val isAuthenticated: Boolean,
    val canUsePrivateContacts: Boolean,
    val pendingPrivateContactsEnabled: Boolean?,
)

private fun PaymentPreferenceUiState.from(source: PaymentPreferenceStateSource) = copy(
    lightningEnabled = source.settings.publicPaykitLightningEnabled,
    onchainEnabled = source.settings.publicPaykitOnchainEnabled,
    privateContactsEnabled = source.pendingPrivateContactsEnabled
        ?: (source.settings.sharesPrivatePaykitEndpoints && source.canUsePrivateContacts),
    publicContactsEnabled = source.settings.sharesPublicPaykitEndpoints,
    hasPubkyProfile = source.isAuthenticated,
    canUsePrivateContacts = source.canUsePrivateContacts,
)
