package to.bitkit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) : ViewModel() {
    var showNewTransaction by mutableStateOf(false)

    var newTransaction by mutableStateOf(
        NewTransactionSheetDetails(
            NewTransactionSheetType.LIGHTNING,
            NewTransactionSheetDirection.RECEIVED,
            0
        )
    )

    fun showNewTransactionSheet(details: NewTransactionSheetDetails) {
        newTransaction = details
        showNewTransaction = true
    }
}
