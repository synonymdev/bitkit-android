package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.Toast
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.usecases.RefreshContactPaykitLinkUseCase
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val refreshContactPaykitLink: RefreshContactPaykitLinkUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "AddContactViewModel"
    }

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(AddContactUiState(publicKeyInput = publicKey))
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AddContactEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var profileJob: Job? = null

    init {
        fetchProfile(publicKey)
    }

    fun onPublicKeyChange(value: String) {
        _uiState.update { it.copy(publicKeyInput = value, error = null) }
    }

    fun fetchProfile(publicKey: String) {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    fetchedProfile = null,
                    error = null,
                    publicKeyInput = publicKey,
                    hasPublicPaymentEndpoint = false,
                )
            }
            pubkyRepo.fetchContactProfile(publicKey)
                .onSuccess { profile ->
                    currentCoroutineContext().ensureActive()
                    _uiState.update {
                        it.copy(
                            fetchedProfile = profile,
                            isLoading = false,
                        )
                    }
                    val hasEndpoint = loadPaymentEndpoint(profile.publicKey)
                    currentCoroutineContext().ensureActive()
                    _uiState.update { it.copy(hasPublicPaymentEndpoint = hasEndpoint) }
                }
                .onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = when (error) {
                                PubkyContactError.AlreadyExists ->
                                    context.getString(R.string.contacts__add_error_existing)
                                PubkyContactError.CannotAddSelf ->
                                    context.getString(R.string.contacts__add_error_self)
                                PubkyContactError.InvalidFormat ->
                                    context.getString(R.string.contacts__add_error_invalid_key)
                                else ->
                                    context.getString(R.string.contacts__add_error_fetch)
                            },
                        )
                    }
                    if (error == PubkyContactError.AlreadyExists) {
                        refreshContactPaykitLink(publicKey)
                    }
                }
        }
    }

    private suspend fun loadPaymentEndpoint(publicKey: String): Boolean {
        return publicPaykitRepo.hasPayablePublicEndpoint(publicKey)
            .onFailure {
                Logger.warn(
                    "Failed to load public Paykit endpoint for '${PubkyPublicKeyFormat.redacted(publicKey)}'",
                    it,
                    context = TAG,
                )
            }
            .getOrDefault(false)
    }

    fun payContact() {
        val profile = _uiState.value.fetchedProfile ?: return
        viewModelScope.launch {
            publicPaykitRepo.beginPayment(profile.publicKey)
                .onSuccess { result ->
                    when (result) {
                        is PublicPaykitPaymentResult.Opened ->
                            _effects.emit(
                                AddContactEffect.OpenPayment(result.paymentRequest, profile.publicKey, result.endpoints)
                            )
                        PublicPaykitPaymentResult.NoEndpoint ->
                            showPayError(R.string.slashtags__error_pay_empty_msg)
                        PublicPaykitPaymentResult.NotOpened ->
                            showPayError(R.string.slashtags__error_pay_not_opened_msg)
                        PublicPaykitPaymentResult.PrivateLinkPending,
                        PublicPaykitPaymentResult.WaitingForUpdatedPaymentList,
                        ->
                            showPayError(R.string.slashtags__error_pay_waiting_msg)
                    }
                }
                .onFailure {
                    val redactedPublicKey = PubkyPublicKeyFormat.redacted(profile.publicKey)
                    Logger.warn(
                        "Failed to begin public Paykit payment for '$redactedPublicKey'",
                        it,
                        context = TAG,
                    )
                    showPayError(R.string.slashtags__error_pay_not_opened_msg)
                }
        }
    }

    private suspend fun showPayError(messageRes: Int) {
        ToastEventBus.send(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.slashtags__error_pay_title),
            description = context.getString(messageRes),
        )
    }

    fun saveContact() {
        if (_uiState.value.isLoading) return
        val profile = _uiState.value.fetchedProfile ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            pubkyRepo.addContact(profile.publicKey, profile)
                .onSuccess {
                    refreshContactPaykitLink(profile.publicKey)
                    _effects.emit(AddContactEffect.ContactSaved(profile.publicKey))
                }
                .onFailure {
                    Logger.error("Failed to save contact", it, context = TAG)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = context.getString(R.string.common__error_body),
                        )
                    }
                }
        }
    }
}

@Stable
data class AddContactUiState(
    val publicKeyInput: String = "",
    val fetchedProfile: PubkyProfile? = null,
    val isLoading: Boolean = false,
    val hasPublicPaymentEndpoint: Boolean = false,
    val error: String? = null,
)

sealed interface AddContactEffect {
    data class ContactSaved(val publicKey: String) : AddContactEffect
    data class OpenPayment(
        val paymentRequest: String,
        val publicKey: String,
        val endpoints: List<Endpoint> = emptyList()
    ) : AddContactEffect
}
