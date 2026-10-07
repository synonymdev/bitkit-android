package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.ui.theme.Colors

@Composable
fun PaykitAmountDisplay(amount: PaykitAmount, prefix: String = "") {
    if (amount.asset == PaykitAsset.BTC) {
        MoneyDisplay(
            sats = amount.atomic.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            showSymbol = true,
            prefix = prefix,
        )
    } else {
        NumberPadAmountText(value = prefix + amount.value, symbol = if (amount.asset == PaykitAsset.USD) "$" else "₮")
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
        Column(horizontalAlignment = Alignment.End) {
            BodyMSB(text = prefix + (if (amount.asset == PaykitAsset.USD) "$" else "₮") + amount.value)
            status?.let { BodyS(text = it, color = Colors.White64) }
        }
    }
}
