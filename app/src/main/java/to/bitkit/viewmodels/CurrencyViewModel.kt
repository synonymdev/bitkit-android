package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import javax.inject.Inject

@HiltViewModel
class CurrencyViewModel @Inject constructor(
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {

    val uiState: StateFlow<CurrencyState> = currencyRepo.currencyState

    fun triggerRefresh() {
        viewModelScope.launch {
            currencyRepo.triggerRefresh()
        }
    }

    fun togglePrimaryDisplay() {
        viewModelScope.launch {
            currencyRepo.togglePrimaryDisplay()
        }
    }

    fun setPrimaryDisplayUnit(unit: PrimaryDisplay) {
        viewModelScope.launch {
            currencyRepo.setPrimaryDisplayUnit(unit)
        }
    }

    fun setBtcDisplayUnit(unit: BitcoinDisplayUnit) {
        viewModelScope.launch {
            currencyRepo.setBtcDisplayUnit(unit)
        }
    }

    fun setSelectedCurrency(currency: String) {
        viewModelScope.launch {
            currencyRepo.setSelectedCurrency(currency)
        }
    }

    fun getCurrencySymbol(): String = currencyRepo.getCurrencySymbol()

    // UI Helpers
    fun convert(sats: Long, currency: String? = null): ConvertedAmount? {
        return currencyRepo.convertSatsToFiat(sats, currency)
    }

    fun convertFiatToSats(fiatAmount: Double, currency: String? = null): Long {
        return currencyRepo.convertFiatToSats(fiatAmount, currency)
    }
}

// For backward compatibility, keeping the original data class name
typealias CurrencyUiState = CurrencyState
