package to.bitkit.ui.screens.wallets

import to.bitkit.ext.nowMillis
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitExchangeRate
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.repositories.paykitRate
import to.bitkit.ui.components.usdtDisplaySats

internal fun usdtHeadlineSats(
    balance: ULong?,
    visible: Boolean,
    rate: PaykitExchangeRate?,
    now: Long = nowMillis(),
): Long? {
    if (!visible || balance == 0uL) return 0L
    if (balance == null || rate == null || rate.timestampMillis > now) return null
    // Home can show the last known value; payment conversions still require a fresh rate.
    return usdtDisplaySats(PaykitAmount(PaykitAsset.USDT, balance).value, rate, now = rate.timestampMillis)
}

internal fun usdtBalanceUpdating(usdt: UsdtWalletState, currency: CurrencyState, now: Long = nowMillis()): Boolean =
    usdt.balanceIsStale || usdt.refreshing ||
        (
            usdt.balance != 0uL && (
                currency.isRefreshing || runCatching { currency.paykitRate?.value(now) }.getOrNull() == null
                )
            )
