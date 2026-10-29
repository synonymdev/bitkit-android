package to.bitkit.ui.screens.transfer.primary

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Toast
import to.bitkit.repositories.PrimaryFundingPlan
import to.bitkit.repositories.PrimaryNodeRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.primaryErrorMessageRes
import javax.inject.Inject

@HiltViewModel
class PrimaryTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val primaryNodeRepo: PrimaryNodeRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrimaryTransferUiState())
    val uiState: StateFlow<PrimaryTransferUiState> = _uiState.asStateFlow()

    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(bgDispatcher) {
            if (!primaryNodeRepo.hasPrimaryNode) {
                _uiState.update { PrimaryTransferUiState(hasPrimaryNode = false) }
                return@launch
            }
            primaryNodeRepo.fundingPlan()
                .onSuccess { plan -> _uiState.update { PrimaryTransferUiState(plan = plan) } }
                .onFailure { error ->
                    _uiState.update {
                        PrimaryTransferUiState(error = context.getString(error.primaryErrorMessageRes()))
                    }
                }
        }
    }

    fun confirm() {
        val plan = _uiState.value.plan ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(bgDispatcher) {
            primaryNodeRepo.transferToSpending(plan)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.primary_transfer__started_title),
                        description = context.getString(R.string.primary_transfer__started),
                        testTag = "PrimaryTransferStartedToast",
                    )
                    _done.emit(Unit)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = context.getString(error.primaryErrorMessageRes()))
                    }
                }
        }
    }
}

@Immutable
data class PrimaryTransferUiState(
    val hasPrimaryNode: Boolean = true,
    val plan: PrimaryFundingPlan? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
