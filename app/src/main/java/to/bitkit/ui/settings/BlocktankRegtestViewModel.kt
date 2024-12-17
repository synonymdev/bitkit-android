package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.data.BlocktankClient
import javax.inject.Inject

@HiltViewModel
class BlocktankRegtestViewModel @Inject constructor(
    private val blocktankClient: BlocktankClient
) : ViewModel() {

    suspend fun regtestMine(count: Int = 1) {
        blocktankClient.regtestMine(count)
    }

    suspend fun regtestDeposit(address: String, amountSat: Int = 10_000_000): String {
        return blocktankClient.regtestDeposit(address, amountSat)
    }

    suspend fun regtestPay(invoice: String, amountSat: Int? = null): String {
        return blocktankClient.regtestPay(invoice, amountSat)
    }

    suspend fun regtestCloseChannel(fundingTxId: String, vout: Int, forceCloseAfterS: Int = 86400): String {
        return blocktankClient.regtestCloseChannel(fundingTxId, vout, forceCloseAfterS)
    }
}
