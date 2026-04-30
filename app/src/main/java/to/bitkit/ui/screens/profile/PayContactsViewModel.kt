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
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PayContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val publicPaykitRepo: PublicPaykitRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "PayContactsViewModel"
    }

    private val _uiState = MutableStateFlow(PayContactsUiState())
    val uiState: StateFlow<PayContactsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PayContactsEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            _uiState.update {
                it.copy(
                    isPaymentSharingEnabled = settings.sharesPublicPaykitEndpoints ||
                        !settings.hasConfirmedPublicPaykitEndpoints,
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
            _uiState.update { it.copy(isLoading = true) }

            publicPaykitRepo.syncPublishedEndpoints(shouldPublish)
                .onSuccess {
                    settingsStore.update {
                        it.copy(
                            hasConfirmedPublicPaykitEndpoints = true,
                            sharesPublicPaykitEndpoints = shouldPublish,
                        )
                    }
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.emit(PayContactsEffect.Continue)
                }
                .onFailure {
                    val settings = settingsStore.data.first()
                    val persistedValue = settings.sharesPublicPaykitEndpoints ||
                        !settings.hasConfirmedPublicPaykitEndpoints
                    Logger.error("Failed to sync public Paykit endpoints", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message ?: context.getString(R.string.common__error_body),
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
}

@Immutable
data class PayContactsUiState(
    val isPaymentSharingEnabled: Boolean = true,
    val isLoading: Boolean = false,
)

sealed interface PayContactsEffect {
    data object Continue : PayContactsEffect
}
