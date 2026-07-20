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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class PayContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactPaymentSettingsRepo: ContactPaymentSettingsRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PayContactsUiState())
    val uiState: StateFlow<PayContactsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PayContactsEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    fun continueToProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            contactPaymentSettingsRepo.setEnabled(true)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.emit(PayContactsEffect.Continue)
                }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = syncErrorMessage(it),
                    )
                    _uiState.update { it.copy(isLoading = false) }
                }
        }
    }

    private fun syncErrorMessage(error: Throwable): String = when (error) {
        PublicPaykitError.InvalidPayload -> context.getString(R.string.profile__pay_contacts_error_invalid_payload)
        PublicPaykitError.NoSupportedEndpoint -> context.getString(R.string.profile__pay_contacts_error_no_endpoint)
        PublicPaykitError.SessionNotActive -> context.getString(R.string.profile__pay_contacts_error_session)
        PublicPaykitError.WalletNotReady -> context.getString(R.string.profile__pay_contacts_error_wallet)
        else -> context.getString(R.string.common__error_body)
    }
}

@Immutable
data class PayContactsUiState(
    val isLoading: Boolean = false,
)

sealed interface PayContactsEffect {
    data object Continue : PayContactsEffect
}
