package to.bitkit.ui.screens.widgets.calculator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {


    val isCalculatorWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.CALCULATOR }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )
    val calculatorValues: StateFlow<CalculatorValues> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.calculatorValues
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = CalculatorValues()
        )

    val showWidgetTitles: StateFlow<Boolean> = widgetsRepo.showWidgetTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = true
        )
    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.CALCULATOR)
        }
    }

    fun saveWidget() {
        viewModelScope.launch {
            widgetsRepo.addWidget(WidgetType.CALCULATOR)
        }
    }

    fun updateCalculatorValues(fiatValue: String, btcValue: String) {
        viewModelScope.launch {
            widgetsRepo.updateCalculatorValues(
                calculatorValues = CalculatorValues(
                    fiatValue = fiatValue,
                    btcValue = btcValue
                )
            )
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}
