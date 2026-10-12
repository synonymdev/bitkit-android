package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.ui.theme.Colors

@Composable
fun PaykitAmountDisplay(amount: PaykitAmount, modifier: Modifier = Modifier, prefix: String = "") {
    if (amount.asset == PaykitAsset.BTC) {
        MoneyDisplay(
            sats = amount.atomic.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            showSymbol = true,
            prefix = prefix,
            modifier = modifier
        )
    } else {
        val displayAmount = if (amount.asset == PaykitAsset.USDT) usdtOverviewAmount(amount.atomic) else amount.value
        NumberPadAmountText(
            value = prefix + displayAmount,
            symbol = "$",
            modifier = modifier
        )
    }
}

@Composable
fun PaykitAmountCell(
    amount: PaykitAmount,
    prefix: String = "",
    status: String? = null,
    showBitcoinSymbol: Boolean = true
) {
    if (amount.asset == PaykitAsset.BTC) {
        MoneyCell(
            sats = amount.atomic.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            prefix = prefix,
            showBitcoinSymbol = showBitcoinSymbol,
            fiatReplacement = status
        )
    } else {
        val displayAmount = if (amount.asset == PaykitAsset.USDT) usdtOverviewAmount(amount.atomic) else amount.value
        Column(horizontalAlignment = Alignment.End) {
            BodyMSB(text = prefix + "$" + displayAmount)
            status?.let { BodyS(text = it, color = Colors.White64) }
        }
    }
}
