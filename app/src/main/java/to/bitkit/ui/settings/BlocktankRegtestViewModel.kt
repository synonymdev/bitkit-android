package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BlocktankRegtestViewModel @Inject constructor(
) : ViewModel() {

    /**
     * Mines a number of blocks on the regtest network.
     * @param count Number of blocks to mine. Default is 1.
     */
    suspend fun regtestMine(count: Int = 1) {
        uniffi.bitkitcore.regtestMine(count = count.toUInt())
    }

    /**
     * Deposits a number of satoshis to an address on the regtest network.
     * @param address Address to deposit to.
     * @param amountSat Amount of satoshis to deposit. Default is 10,000,000.
     * @return Onchain transaction ID.
     */
    suspend fun regtestDeposit(address: String, amountSat: ULong = 10_000_000uL): String {
        return uniffi.bitkitcore.regtestDeposit(
            address = address,
            amountSat = amountSat,
        )
    }

    /**
     * Pays an invoice on the regtest network.
     * @param invoice Invoice to pay.
     * @param amountSat Amount of satoshis to pay (only for 0-amount invoices).
     * @return Blocktank payment ID.
     */
    suspend fun regtestPay(invoice: String, amountSat: ULong? = null): String {
        return uniffi.bitkitcore.regtestPay(
            invoice = invoice,
            amountSat = amountSat,
        )
    }

    /**
     * Closes a channel on the regtest network.
     * @param fundingTxId Funding transaction ID.
     * @param vout Funding transaction output index.
     * @param forceCloseAfterS Time in seconds to force-close the channel after. Default is 24 hours (86400). Set it to 0 for immediate force close.
     * @return Closing transaction ID.
     */
    suspend fun regtestCloseChannel(fundingTxId: String, vout: UInt, forceCloseAfterS: ULong = 86_400uL): String {
        return uniffi.bitkitcore.regtestCloseChannel(
            fundingTxId = fundingTxId,
            vout = vout,
            forceCloseAfterS = forceCloseAfterS,
        )
    }
}
