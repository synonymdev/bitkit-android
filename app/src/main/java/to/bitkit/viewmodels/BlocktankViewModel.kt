package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import dagger.hilt.android.lifecycle.HiltViewModel
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

    val orders: StateFlow<List<IBtOrder>> = blocktankRepo.blocktankState
        .map { it.orders }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cJitEntries: StateFlow<List<IcJitEntry>> = blocktankRepo.blocktankState
        .map { it.cjitEntries }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val info: StateFlow<IBtInfo?> = blocktankRepo.blocktankState
        .map { it.info }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val minCjitSats: StateFlow<Int?> = blocktankRepo.blocktankState
        .map { it.minCjitSats }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshInfo() {
        viewModelScope.launch {
            blocktankRepo.refreshInfo()
        }
    }

    fun refreshOrders() {
        viewModelScope.launch {
            blocktankRepo.refreshOrders()
        }
    }

    suspend fun createCjit(amountSats: ULong): IcJitEntry {
        return blocktankRepo.createCjit(amountSats).getOrThrow()
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
    ): IBtOrder {
        return blocktankRepo.createOrder(spendingBalanceSats, receivingBalanceSats).getOrThrow()
    }

    suspend fun estimateOrderFee(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong,
    ): IBtEstimateFeeResponse2 {
        return blocktankRepo.estimateOrderFee(spendingBalanceSats, receivingBalanceSats).getOrThrow()
    }

    suspend fun openChannel(orderId: String): IBtOrder {
        return blocktankRepo.openChannel(orderId).getOrThrow()
    }

    suspend fun refreshMinCjitSats() {
        blocktankRepo.refreshMinCjitSats()
    }
}
