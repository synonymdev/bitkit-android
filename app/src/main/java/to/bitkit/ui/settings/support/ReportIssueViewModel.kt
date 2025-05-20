package to.bitkit.ui.settings.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.repositories.SupportRepo
import javax.inject.Inject

@HiltViewModel
class ReportIssueViewModel @Inject constructor(
    private val supportRepo: SupportRepo
) : ViewModel() {

    fun sendMessage(email: String, message: String) {
        viewModelScope.launch {
            supportRepo.postQuestion(email = email, message = message)
                .onSuccess {
                    //TODO NAVIGATE SUCCESS
                }.onFailure {
                    //TODO NAVIGATE FAILURE
                }
        }
    }
}
