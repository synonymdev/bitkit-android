package to.bitkit.ui.utils

import androidx.compose.runtime.Composable
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.currencyViewModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// TODO review calculations
@Composable
fun getDefaultLspBalance(
    clientBalanceSat: Long,
    maxLspBalance: Long,
): Long {
    val currency = currencyViewModel ?: return 0
    val threshold1 = currency.convertFiatToBitcoin("225", currency = "EUR")
    val threshold2 = currency.convertFiatToBitcoin("495", currency = "EUR")
    val defaultLspBalance = currency.convertFiatToBitcoin("450", currency = "EUR")

    var lspBalance = defaultLspBalance - clientBalanceSat

    if (lspBalance > threshold1) {
        lspBalance = clientBalanceSat
    }
    if (lspBalance > threshold2) {
        lspBalance = maxLspBalance
    }

    return min(lspBalance, maxLspBalance)
}

fun getMinLspBalance(clientBalance: Long, minChannelSize: Long): Long {
    // LSP balance must be at least 2.5% of the channel size for LDK to accept (reserve balance)
    val ldkMinimum = (clientBalance * 0.025).roundToLong()
    // Channel size must be at least minChannelSize
    val lspMinimum = max(minChannelSize - clientBalance, 0)

    return max(ldkMinimum, lspMinimum)
}

fun getMaxClientBalance(maxChannelSize: Long): Long {
    // Remote balance must be at least 2.5% of the channel size for LDK to accept (reserve balance)
    val minRemoteBalance = (maxChannelSize * 0.025).toLong()
    return maxChannelSize - minRemoteBalance
}

@Composable
fun useTransfer(clientBalanceSat: Long): TransferValues {
    val blocktank = blocktankViewModel ?: return TransferValues()
    val blocktankInfo = blocktank.info ?: return TransferValues()
    val channelsSize = 0 // TODO get sum of all blocktank channels

    val (minChannelSizeSat, maxChannelSizeSat) = blocktankInfo.options.let {
        it.minChannelSizeSat to it.maxChannelSizeSat
    }

    // Add a 2% buffer to avoid fluctuations while making the order
    val maxChannelSize1 = (maxChannelSizeSat.toDouble() * 0.98).roundToLong()

    // The maximum channel size the user can open including existing channels
    val maxChannelSize2 = max(0, maxChannelSize1 - channelsSize)
    val maxChannelSize = min(maxChannelSize1, maxChannelSize2)

    val minLspBalance = getMinLspBalance(clientBalanceSat, minChannelSizeSat.toLong())
    val maxLspBalance = max(maxChannelSize - clientBalanceSat, 0)
    val defaultLspBalance = getDefaultLspBalance(clientBalanceSat, maxLspBalance)
    val maxClientBalance = getMaxClientBalance(maxChannelSize)

    return TransferValues(
        defaultLspBalance = defaultLspBalance,
        minLspBalance = minLspBalance,
        maxLspBalance = maxLspBalance,
        maxClientBalance = maxClientBalance
    )
}

data class TransferValues(
    val defaultLspBalance: Long = 0,
    val minLspBalance: Long = 0,
    val maxLspBalance: Long = 0,
    val maxClientBalance: Long = 0,
)
