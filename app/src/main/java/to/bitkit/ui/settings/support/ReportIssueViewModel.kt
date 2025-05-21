package to.bitkit.ui.settings.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import to.bitkit.repositories.SupportRepo
import javax.inject.Inject

@HiltViewModel
class ReportIssueViewModel @Inject constructor(
    private val supportRepo: SupportRepo
) : ViewModel() {

    private val _reportIssueEffect = MutableSharedFlow<ReportIssueEffects>()
    val reportIssueEffect = _reportIssueEffect.asSharedFlow()
    private fun setReportIssueEffect(effect: ReportIssueEffects) = viewModelScope.launch { _reportIssueEffect.emit(effect) }

    fun sendMessage(email: String, message: String) {
        viewModelScope.launch {
            supportRepo.postQuestion(email = email, message = message)
                .onSuccess {
                    setReportIssueEffect(ReportIssueEffects.NavigateSuccess)
                }.onFailure {
                    setReportIssueEffect(ReportIssueEffects.NavigateError)
                }
        }
    }
}

sealed interface ReportIssueEffects {
    data object NavigateSuccess: ReportIssueEffects
    data object NavigateError: ReportIssueEffects
}
