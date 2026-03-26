package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.repositories.BlocktankRepo
import javax.inject.Inject

@HiltViewModel
class BlocktankViewModel @Inject constructor(
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {

    val orders: StateFlow<ImmutableList<IBtOrder>> = blocktankRepo.blocktankState
        .map { it.orders }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    val cJitEntries: StateFlow<ImmutableList<IcJitEntry>> = blocktankRepo.blocktankState
        .map { it.cjitEntries }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    val info: StateFlow<IBtInfo?> = blocktankRepo.blocktankState
        .map { it.info }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val minCjitSats: StateFlow<Int?> = blocktankRepo.blocktankState
        .map { it.minCjitSats }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshOrders() {
        viewModelScope.launch {
            blocktankRepo.refreshOrders()
        }
    }

    suspend fun createCjit(amountSats: ULong): IcJitEntry {
        return blocktankRepo.createCjit(amountSats).getOrThrow()
    }

    suspend fun openChannel(orderId: String): IBtOrder {
        return blocktankRepo.openChannel(orderId).getOrThrow()
    }

    suspend fun refreshMinCjitSats() {
        blocktankRepo.refreshMinCjitSats()
    }
}
