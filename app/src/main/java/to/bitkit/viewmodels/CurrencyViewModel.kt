package to.bitkit.viewmodels

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.bitkit.appwidget.ui.weather.WeatherGlanceWidget
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import javax.inject.Inject

@HiltViewModel
class CurrencyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {

    val uiState: StateFlow<CurrencyState> = currencyRepo.currencyState

    fun triggerRefresh() {
        viewModelScope.launch {
            currencyRepo.triggerRefresh()
        }
    }

    fun switchUnit() {
        viewModelScope.launch {
            currencyRepo.switchUnit()
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
            currencyRepo.currencyState.first { it.selectedCurrency == currency }
            WeatherGlanceWidget().updateAll(context)
        }
    }

    // UI Helpers
    fun convert(sats: Long, currency: String? = null): ConvertedAmount? {
        return currencyRepo.convertSatsToFiat(sats, currency).getOrNull()
    }

    fun convertFiatToSats(fiatAmount: Double, currency: String? = null): Long {
        val uLongSats = currencyRepo.convertFiatToSats(fiatAmount, currency).getOrNull() ?: 0UL
        return uLongSats.toLong()
    }
}
