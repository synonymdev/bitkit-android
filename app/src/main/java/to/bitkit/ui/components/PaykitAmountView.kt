package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.ui.theme.Colors
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

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
        val locale = LocalConfiguration.current.locales[0]
        val displayAmount = remember(amount, locale) {
            if (amount.asset == PaykitAsset.USDT) {
                usdtOverviewAmount(amount.atomic, locale)
            } else {
                usdDisplayAmount(amount.atomic, locale)
            }
        }
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
        val locale = LocalConfiguration.current.locales[0]
        val displayAmount = remember(amount, locale) {
            if (amount.asset == PaykitAsset.USDT) {
                usdtOverviewAmount(amount.atomic, locale)
            } else {
                usdDisplayAmount(amount.atomic, locale)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            BodyMSB(text = prefix + "$" + displayAmount)
            status?.let { BodyS(text = it, color = Colors.White64) }
        }
    }
}

internal fun usdDisplayAmount(cents: ULong, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = PaykitAsset.USD.decimals
        maximumFractionDigits = PaykitAsset.USD.decimals
    }
    return format.format(BigDecimal(cents.toString()).movePointLeft(PaykitAsset.USD.decimals))
}
