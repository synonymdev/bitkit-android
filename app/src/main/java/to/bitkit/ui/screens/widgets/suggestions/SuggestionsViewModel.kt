package to.bitkit.ui.screens.widgets.suggestions

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
class SuggestionsViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo,
) : ViewModel() {

    companion object {
        private const val SUBSCRIBE_TIMEOUT = 5000L
    }

    val isSuggestionsWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.SUGGESTIONS }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT), false)

    fun addWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.addWidget(WidgetType.SUGGESTIONS)
            onComplete()
        }
    }

    fun removeWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.SUGGESTIONS)
            onComplete()
        }
    }
}
