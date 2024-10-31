package to.bitkit.ui.screens.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.AppDb
import to.bitkit.data.entities.OrderEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.ext.toast
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.CJitEntry
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val blocktankService: BlocktankService,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReceiveUiState>(ReceiveUiState())
    val uiState = _uiState.asStateFlow()

    fun createCjit(sats: Int, description: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCreatingCjit = true,
            )
            runCatching { blocktankService.createCjit(sats, description) }
                .onSuccess { entry ->
                    // launch { db.cjitDao().upsert(OrderEntity(order.id)) } // TODO cache cjit entries in DB
                    _uiState.value = _uiState.value.copy(
                        cjitEntry = entry,
                        isCreatingCjit = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isCreatingCjit = false,
                    )
                    withContext(uiThread) { toast( "$it") }
                }
        }
    }
}

// region state
data class ReceiveUiState(
    val cjitEntry: CJitEntry? = null,
    val isCreatingCjit: Boolean = false,
)
// endregion
