package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class PayContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PayContactsUiState())
    val uiState: StateFlow<PayContactsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PayContactsEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            val hasLocalSecretKey = pubkyRepo.hasSecretKey()
            _uiState.update {
                it.copy(
                    isPaymentSharingEnabled = resolvedSharingDefault(settings, hasLocalSecretKey),
                )
            }
        }
    }

    fun setPaymentSharingEnabled(isEnabled: Boolean) {
        _uiState.update { it.copy(isPaymentSharingEnabled = isEnabled) }
    }

    fun continueToProfile() {
        viewModelScope.launch {
            val shouldPublish = _uiState.value.isPaymentSharingEnabled
            val contacts = pubkyRepo.contacts.value.map { it.publicKey }
            _uiState.update { it.copy(isLoading = true) }

            val result = if (shouldPublish) {
                enableContactPayments(contacts)
            } else {
                disableContactPayments(contacts)
            }

            result
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.emit(PayContactsEffect.Continue)
                }
                .onFailure {
                    val settings = settingsStore.data.first()
                    val persistedValue = resolvedSharingDefault(settings, pubkyRepo.hasSecretKey())
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = syncErrorMessage(it),
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isPaymentSharingEnabled = persistedValue,
                        )
                    }
                }
        }
    }

    private suspend fun enableContactPayments(contacts: List<String>): Result<Unit> {
        publicPaykitRepo.syncPublishedEndpoints(publish = true)
            .onFailure { return Result.failure(it) }

        val canUsePrivateContactPayments = pubkyRepo.hasSecretKey()
        if (canUsePrivateContactPayments) {
            privatePaykitRepo.setContactSharingCleanupPending(false)
                .onFailure {
                    publicPaykitRepo.syncPublishedEndpoints(publish = false)
                    return Result.failure(it)
                }
        }

        runCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = true,
                    sharesPrivatePaykitEndpoints = canUsePrivateContactPayments,
                )
            }
        }.onFailure {
            return Result.failure(it)
        }

        if (canUsePrivateContactPayments) {
            privatePaykitRepo.prepareSavedContacts(contacts)
        }

        return Result.success(Unit)
    }

    private suspend fun disableContactPayments(contacts: List<String>): Result<Unit> {
        val previous = settingsStore.data.first()
        runCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = false,
                    sharesPrivatePaykitEndpoints = false,
                )
            }
        }.onFailure {
            return Result.failure(it)
        }

        var publicCleanupError: Throwable? = null
        var privateCleanupError: Throwable? = null
        publicPaykitRepo.syncPublishedEndpoints(publish = false)
            .onFailure { publicCleanupError = it }

        privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
            .onFailure { privateCleanupError = it }

        publicCleanupError?.let { error ->
            runCatching {
                settingsStore.update { settings ->
                    settings.copy(sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints)
                }
            }.onFailure { rollbackError ->
                error.addSuppressed(rollbackError)
            }
        }

        val cleanupError = publicCleanupError ?: privateCleanupError
        publicCleanupError?.let { publicError ->
            privateCleanupError?.let { privateError -> publicError.addSuppressed(privateError) }
        }
        cleanupError?.let {
            if (privateCleanupError != null) {
                privatePaykitRepo.setContactSharingCleanupPending(true)
                    .onFailure { markerError ->
                        it.addSuppressed(markerError)
                        return Result.failure(it)
                    }
            }
            return Result.failure(it)
        }

        privatePaykitRepo.setContactSharingCleanupPending(false)
            .onFailure { return Result.failure(it) }

        return Result.success(Unit)
    }

    private fun syncErrorMessage(error: Throwable): String = when (error) {
        PublicPaykitError.InvalidPayload -> context.getString(R.string.profile__pay_contacts_error_invalid_payload)
        PublicPaykitError.NoSupportedEndpoint -> context.getString(R.string.profile__pay_contacts_error_no_endpoint)
        PublicPaykitError.SessionNotActive -> context.getString(R.string.profile__pay_contacts_error_session)
        PublicPaykitError.WalletNotReady -> context.getString(R.string.profile__pay_contacts_error_wallet)
        else -> context.getString(R.string.common__error_body)
    }

    private fun resolvedSharingDefault(settings: SettingsData, hasLocalSecretKey: Boolean): Boolean =
        settings.sharesPublicPaykitEndpoints ||
            (settings.sharesPrivatePaykitEndpoints && hasLocalSecretKey) ||
            !settings.hasConfirmedPublicPaykitEndpoints
}

@Immutable
data class PayContactsUiState(
    val isPaymentSharingEnabled: Boolean = true,
    val isLoading: Boolean = false,
)

sealed interface PayContactsEffect {
    data object Continue : PayContactsEffect
}
