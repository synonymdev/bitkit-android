package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import to.bitkit.ext.nowMillis
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitExchangeRate
import to.bitkit.models.formatMoney
import to.bitkit.repositories.paykitRate
import to.bitkit.ui.LocalCurrencies
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun UsdtAmountHeader(
    amount: String,
    network: String,
    modifier: Modifier = Modifier,
    hideBalance: Boolean = false,
    prefix: String? = null,
    onToggleHide: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val rate = LocalCurrencies.current.paykitRate
    val sats = remember(amount, rate) { usdtDisplaySats(amount, rate) }
    val btc = sats?.formatMoney(LocalCurrencies.current.displayUnit)
    BalanceHeader(
        isBitcoinPrimary = false,
        smallRowText = btc ?: "USDT · $network",
        smallRowSymbol = if (btc != null) BITCOIN_SYMBOL else null,
        largeRowText = amount,
        largeRowSymbol = "$",
        largeRowPrefix = prefix,
        showSymbol = true,
        hideBalance = hideBalance,
        onClick = onClick,
        isSwipeToHideEnabled = onToggleHide != null,
        onToggleHideBalance = { onToggleHide?.invoke() },
        modifier = modifier.fillMaxWidth()
    )
}

private const val USDT_CENT = 10_000uL

internal fun usdtOverviewAmount(amount: ULong): String {
    if (amount > 0uL && amount < USDT_CENT) return "<0.01"
    return BigDecimal(amount.toString()).movePointLeft(PaykitAsset.USDT.decimals).setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

internal fun usdtDisplaySats(amount: String, rate: PaykitExchangeRate?, now: Long = nowMillis()): Long? {
    val dollars = amount.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: return null
    val price = runCatching { rate?.value(now) }.getOrNull() ?: return null
    return runCatching {
        dollars.divide(
            price,
            PaykitAsset.BTC.decimals,
            RoundingMode.DOWN
        ).movePointRight(PaykitAsset.BTC.decimals).longValueExact()
    }.getOrNull()
}
