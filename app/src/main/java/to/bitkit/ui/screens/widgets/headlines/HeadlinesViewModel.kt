package to.bitkit.ui.screens.widgets.headlines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import to.bitkit.data.WidgetsData
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class HeadlinesViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
): ViewModel() {

    private val widgetsData: StateFlow<WidgetsData> = widgetsRepo.widgetsDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WidgetsData())



}
