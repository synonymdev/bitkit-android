package to.bitkit.viewmodels

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.FeeRates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.repositories.LightningRepo
import javax.inject.Inject

@HiltViewModel
class FeeSettingsViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeeSettingsUiState())
    val uiState = _uiState.asStateFlow()

    fun refreshFeeRates() {
        viewModelScope.launch {
            lightningRepo.getFeeRates().onSuccess { rates ->
                _uiState.update { it.copy(feeRates = rates) }
            }
        }
    }
}

@Stable
data class FeeSettingsUiState(
    val feeRates: FeeRates? = null,
)
