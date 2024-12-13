package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.models.blocktank.BtInfo
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.CJitEntry
import to.bitkit.services.BlocktankService
import javax.inject.Inject

@HiltViewModel
class BlocktankViewModel @Inject constructor(
    private val blocktankService: BlocktankService,
) : ViewModel() {
    var orders = mutableListOf<BtOrder>() // TODO cache orders
        private set
    var cjitEntries = mutableListOf<CJitEntry>() // TODO cache cjitEntries
        private set
    var info by mutableStateOf<BtInfo?>(null) // TODO cache info
        private set

    suspend fun refreshInfo() {
        info = blocktankService.getInfo()
    }

    suspend fun createCjit(amountSats: Int, description: String): CJitEntry {
        val entry = blocktankService.createCjit(amountSats, description)
        cjitEntries.add(entry)
        return entry
    }
}
