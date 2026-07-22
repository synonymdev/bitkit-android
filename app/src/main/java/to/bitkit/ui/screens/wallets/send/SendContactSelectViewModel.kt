package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PrivatePaykitPaymentContext
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SendContactSelectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendContactSelectUiState())
    val uiState: StateFlow<SendContactSelectUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SendContactSelectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            pubkyRepo.contacts.collect { contacts ->
                _uiState.update {
                    it.copy(
                        contacts = contacts.sortedBy { contact -> contact.name.lowercase() }.toImmutableList(),
                        isLoading = pubkyRepo.isLoadingContacts.value,
                    )
                }
            }
        }
        viewModelScope.launch {
            pubkyRepo.isLoadingContacts.collect { isLoading ->
                _uiState.update { it.copy(isLoading = isLoading) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { pubkyRepo.loadContacts() }
    }

    fun payContact(publicKey: String) {
        if (_uiState.value.isResolvingPayment) return
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingPayment = true) }
            privatePaykitRepo.beginSavedContactPayment(publicKey)
                .onSuccess { result ->
                    when (result) {
                        is PublicPaykitPaymentResult.Opened ->
                            _effects.emit(
                                SendContactSelectEffect.OpenPayment(
                                    result.paymentRequest,
                                    publicKey,
                                    result.privatePaymentContext,
                                )
                            )
                        PublicPaykitPaymentResult.NoEndpoint ->
                            showPayError(R.string.slashtags__error_pay_empty_msg)
                        PublicPaykitPaymentResult.NotOpened ->
                            showPayError(R.string.slashtags__error_pay_not_opened_msg)
                        PublicPaykitPaymentResult.WaitingForUpdatedPaymentList ->
                            showPayError(R.string.slashtags__error_pay_empty_msg)
                    }
                }
                .onFailure {
                    Logger.warn("Failed to begin contact payment", it, context = TAG)
                    showPayError(R.string.slashtags__error_pay_not_opened_msg)
                }
            _uiState.update { it.copy(isResolvingPayment = false) }
        }
    }

    private suspend fun showPayError(messageRes: Int) {
        ToastEventBus.send(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.slashtags__error_pay_title),
            description = context.getString(messageRes),
        )
    }

    private companion object {
        const val TAG = "SendContactSelectViewModel"
    }
}

@Stable
data class SendContactSelectUiState(
    val contacts: ImmutableList<PubkyProfile> = persistentListOf(),
    val isLoading: Boolean = false,
    val isResolvingPayment: Boolean = false,
)

sealed interface SendContactSelectEffect {
    data class OpenPayment(
        val paymentRequest: String,
        val publicKey: String,
        val privatePaymentContext: PrivatePaykitPaymentContext?,
    ) : SendContactSelectEffect
}
