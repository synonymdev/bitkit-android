package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.animations.BalanceAnimations
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun RowScope.WalletBalanceView(
    title: String,
    sats: Long,
    icon: Painter,
    modifier: Modifier = Modifier,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        Content(
            modifier = modifier,
            title = title,
            converted = ConvertedAmount(
                value = sats.toBigDecimal(),
                formatted = sats.formatToModernDisplay(),
                symbol = BITCOIN_SYMBOL,
                currency = "USD",
                flag = "",
                sats = sats,
            ),
            icon = icon,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            displayUnit = BitcoinDisplayUnit.MODERN,
            hideBalance = false,
        )
    }

    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return

    val displayUnit = currencies.displayUnit
    val primaryDisplay = currencies.primaryDisplay
    val converted: ConvertedAmount? = currency.convert(sats = sats)

    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()

    Content(
        modifier = modifier,
        title = title,
        converted = converted,
        icon = icon,
        primaryDisplay = primaryDisplay,
        displayUnit = displayUnit,
        hideBalance = hideBalance,
    )
}

@Composable
private fun RowScope.Content(
    modifier: Modifier,
    title: String,
    converted: ConvertedAmount?,
    icon: Painter,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    hideBalance: Boolean,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .then(modifier)
    ) {
        Text13Up(
            text = title,
            color = Colors.White64,
        )
        Spacer(modifier = Modifier.height(8.dp))

        converted?.let { converted ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painter = icon,
                    contentDescription = title,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(24.dp)
                )

                if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    AnimatedContent(
                        targetState = hideBalance,
                        transitionSpec = { BalanceAnimations.walletBalanceTransition },
                        label = "bitcoinBalanceAnimation"
                    ) { isHidden ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BodyMSB(text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else btcComponents.value)
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = hideBalance,
                        transitionSpec = { BalanceAnimations.walletBalanceTransition },
                        label = "fiatBalanceAnimation"
                    ) { isHidden ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BodyMSB(text = converted.symbol)
                            BodyMSB(text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else converted.formatted)
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
        ) {
            WalletBalanceView(
                title = stringResource(R.string.wallet__savings__title),
                sats = 1_250_000,
                icon = painterResource(R.drawable.ic_btc_circle),
            )
            VerticalDivider()
            WalletBalanceView(
                title = stringResource(R.string.wallet__spending__title),
                sats = 250_000,
                icon = painterResource(R.drawable.ic_ln_circle),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTransferToSpending() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
        ) {
            WalletBalanceView(
                title = stringResource(R.string.wallet__savings__title),
                sats = 1_150_000,
                icon = painterResource(R.drawable.ic_btc_circle),
            )
            VerticalDivider()
            WalletBalanceView(
                title = stringResource(R.string.wallet__spending__title),
                sats = 250_000,
                icon = painterResource(R.drawable.ic_ln_circle),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTransferToSavings() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
        ) {
            WalletBalanceView(
                title = stringResource(R.string.wallet__savings__title),
                sats = 1_250_000,
                icon = painterResource(R.drawable.ic_btc_circle),
            )
            VerticalDivider()
            WalletBalanceView(
                title = stringResource(R.string.wallet__spending__title),
                sats = 150_000,
                icon = painterResource(R.drawable.ic_ln_circle),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewTransfers() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
        ) {
            WalletBalanceView(
                title = stringResource(R.string.wallet__savings__title),
                sats = 1_150_000,
                icon = painterResource(R.drawable.ic_btc_circle),
            )
            VerticalDivider()
            WalletBalanceView(
                title = stringResource(R.string.wallet__spending__title),
                sats = 150_000,
                icon = painterResource(R.drawable.ic_ln_circle),
            )
        }
    }
}
