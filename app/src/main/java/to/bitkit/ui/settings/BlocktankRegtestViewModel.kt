package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.data.BlocktankHttpClient
import javax.inject.Inject

@HiltViewModel
class BlocktankRegtestViewModel @Inject constructor(
    private val blocktankHttpClient: BlocktankHttpClient
) : ViewModel() {

    suspend fun regtestMine(count: Int = 1) {
        blocktankHttpClient.regtestMine(count)
    }

    suspend fun regtestDeposit(address: String, amountSat: Int = 10_000_000): String {
        return blocktankHttpClient.regtestDeposit(address, amountSat)
    }

    suspend fun regtestPay(invoice: String, amountSat: Int? = null): String {
        return blocktankHttpClient.regtestPay(invoice, amountSat)
    }

    suspend fun regtestCloseChannel(fundingTxId: String, vout: Int, forceCloseAfterS: Int = 86400): String {
        return blocktankHttpClient.regtestCloseChannel(fundingTxId, vout, forceCloseAfterS)
    }
}
