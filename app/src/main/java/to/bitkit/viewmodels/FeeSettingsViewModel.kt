package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.FeeRates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.repositories.BlocktankRepo
import javax.inject.Inject

@HiltViewModel
class FeeSettingsViewModel @Inject constructor(
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {

    val uiState: StateFlow<FeeSettingsUiState> = blocktankRepo.blocktankState
        .map { blocktankState ->
            FeeSettingsUiState(
                feeRates = blocktankState.info?.onchain?.feeRates,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FeeSettingsUiState(),
        )

    fun refreshFeeRates() {
        viewModelScope.launch {
            blocktankRepo.refreshInfo()
        }
    }
}

data class FeeSettingsUiState(
    val feeRates: FeeRates? = null,
)
