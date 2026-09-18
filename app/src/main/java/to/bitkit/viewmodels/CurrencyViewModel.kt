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
import to.bitkit.R
import to.bitkit.appwidget.ui.weather.WeatherGlanceWidget
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class CurrencyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {
    private companion object {
        const val TAG = "CurrencyViewModel"
    }

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

    fun switchBalanceUnit() {
        viewModelScope.launch {
            currencyRepo.switchBalanceUnit().onSuccess { switch ->
                if (switch == null) return@onSuccess
                val newUnit = if (switch.newDisplay == PrimaryDisplay.BITCOIN) {
                    context.getString(R.string.settings__general__unit_bitcoin)
                } else {
                    switch.selectedCurrency
                }
                val previousUnit = if (switch.previousDisplay == PrimaryDisplay.BITCOIN) {
                    context.getString(R.string.settings__general__unit_bitcoin)
                } else {
                    switch.selectedCurrency
                }
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = context.getString(R.string.wallet__balance_unit_switched_title, newUnit),
                    description = context.getString(R.string.wallet__balance_unit_switched_message, previousUnit),
                    visibilityTime = 5000L,
                    testTag = "BalanceUnitSwitchedToast",
                )
            }.onFailure {
                Logger.error("Failed to switch balance unit", it, context = TAG)
            }
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
