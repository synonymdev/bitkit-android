package to.bitkit.ui.settings.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.removeSpaces
import to.bitkit.repositories.SupportRepo
import to.bitkit.ui.utils.isValidEmail
import javax.inject.Inject

@HiltViewModel
class ReportIssueViewModel @Inject constructor(
    private val supportRepo: SupportRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportIssueUiState())
    val uiState = _uiState.asStateFlow()

    private val _reportIssueEffect = MutableSharedFlow<ReportIssueEffects>()
    val reportIssueEffect = _reportIssueEffect.asSharedFlow()
    private fun setReportIssueEffect(effect: ReportIssueEffects) =
        viewModelScope.launch { _reportIssueEffect.emit(effect) }

    fun sendMessage() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            supportRepo.postQuestion(email = _uiState.value.emailInput, message = _uiState.value.messageInput)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    setReportIssueEffect(ReportIssueEffects.NavigateSuccess)
                }.onFailure {
                    _uiState.update { it.copy(isLoading = false) }
                    setReportIssueEffect(ReportIssueEffects.NavigateError)
                }
        }
    }

    fun updateEmail(text: String) {
        _uiState.update {
            it.copy(
                emailInput = text,
                errorEmail = text.isNotBlank() && !text.removeSpaces().isValidEmail()
            )
        }
        updateSendButton()
    }

    fun updateMessage(text: String) {
        _uiState.update { it.copy(messageInput = text) }
        updateSendButton()
    }

    private fun updateSendButton() {
        _uiState.update { it.copy(
            isSendEnabled = !it.errorEmail && it.emailInput.isNotBlank() && it.messageInput.isNotBlank()
        ) }
    }
}

sealed interface ReportIssueEffects {
    data object NavigateSuccess : ReportIssueEffects
    data object NavigateError : ReportIssueEffects
}

data class ReportIssueUiState(
    val emailInput: String = "",
    val messageInput: String = "",
    val isLoading: Boolean = false,
    val isSendEnabled: Boolean = false,
    val errorEmail: Boolean = false
)
