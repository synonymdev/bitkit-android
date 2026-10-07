package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
    BalanceHeader(
        isBitcoinPrimary = false,
        smallRowText = "USDT · $network",
        largeRowText = amount,
        largeRowSymbol = "₮",
        largeRowPrefix = prefix,
        showSymbol = true,
        hideBalance = hideBalance,
        onClick = onClick,
        isSwipeToHideEnabled = onToggleHide != null,
        onToggleHideBalance = { onToggleHide?.invoke() },
        modifier = modifier.fillMaxWidth()
    )
}
