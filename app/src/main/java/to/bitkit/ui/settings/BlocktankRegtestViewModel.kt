package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.services.CoreService
import javax.inject.Inject

@HiltViewModel
class BlocktankRegtestViewModel @Inject constructor(
    private val coreService: CoreService,
) : ViewModel() {

    suspend fun regtestMine(count: UInt = 1u) {
        coreService.blocktank.regtestMine(count = count)
    }

    suspend fun regtestDeposit(address: String, amountSat: ULong = 10_000_000uL): String {
        return coreService.blocktank.regtestDeposit(
            address = address,
            amountSat = amountSat,
        )
    }

    suspend fun regtestPay(invoice: String, amountSat: ULong? = null): String {
        return coreService.blocktank.regtestPay(
            invoice = invoice,
            amountSat = amountSat,
        )
    }

    suspend fun regtestCloseChannel(fundingTxId: String, vout: UInt, forceCloseAfterS: ULong = 86_400uL): String {
        return coreService.blocktank.regtestCloseChannel(
            fundingTxId = fundingTxId,
            vout = vout,
            forceCloseAfterS = forceCloseAfterS,
        )
    }
}
