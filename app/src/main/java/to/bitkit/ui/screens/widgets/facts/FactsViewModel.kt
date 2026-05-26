package to.bitkit.ui.screens.widgets.facts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.models.WidgetType
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class FactsViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo,
) : ViewModel() {

    val isFactsWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.FACTS }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val currentFact: StateFlow<String> =
        widgetsRepo.factsFlow.map { facts -> facts.randomOrNull() ?: DEFAULT_FACT }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = DEFAULT_FACT
        )

    fun saveWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.addWidget(WidgetType.FACTS)
            onComplete()
        }
    }

    fun removeWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.FACTS)
            onComplete()
        }
    }

    fun refreshOnDisplay() {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(WidgetType.FACTS)
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L

        private const val DEFAULT_FACT = "If you lose your keys, you lose your coins."
    }
}
