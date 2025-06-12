package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.animations.BalanceAnimations
import to.bitkit.ui.shared.modifiers.swipeToHide
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
    forceShowBalance: Boolean = false,
    showEyeIcon: Boolean = false,
) {
    val isPreview = LocalInspectionMode.current

    if (isPreview) {
        BalanceHeader(
            modifier = modifier,
            smallRowSymbol = "$",
            smallRowText = "12.34",
            largeRowPrefix = prefix,
            largeRowText = "$sats",
            largeRowSymbol = BITCOIN_SYMBOL,
            showSymbol = showBitcoinSymbol,
            hideBalance = false,
            isSwipeToHideEnabled = false,
            showEyeIcon = showEyeIcon,
            onClick = {},
            onToggleHideBalance = {},
        )
        return
    }

    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return
    val (_, _, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = currency.convert(sats = sats)

    val isSwipeToHideEnabled by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()
    val shouldHideBalance = hideBalance && !forceShowBalance

    converted?.let { converted ->
        val btcComponents = converted.bitcoinDisplay(displayUnit)

        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            BalanceHeader(
                modifier = modifier,
                smallRowSymbol = converted.symbol,
                smallRowText = converted.formatted,
                largeRowPrefix = prefix,
                largeRowText = btcComponents.value,
                largeRowSymbol = btcComponents.symbol,
                showSymbol = showBitcoinSymbol,
                hideBalance = shouldHideBalance,
                isSwipeToHideEnabled = isSwipeToHideEnabled,
                showEyeIcon = showEyeIcon,
                onClick = { currency.togglePrimaryDisplay() },
                onToggleHideBalance = { settings.setHideBalance(!hideBalance) },
            )
        } else {
            BalanceHeader(
                modifier = modifier,
                smallRowSymbol = btcComponents.symbol,
                smallRowText = btcComponents.value,
                largeRowPrefix = prefix,
                largeRowText = converted.formatted,
                largeRowSymbol = converted.symbol,
                showSymbol = true,
                hideBalance = shouldHideBalance,
                isSwipeToHideEnabled = isSwipeToHideEnabled,
                showEyeIcon = showEyeIcon,
                onClick = { currency.togglePrimaryDisplay() },
                onToggleHideBalance = { settings.setHideBalance(!hideBalance) },
            )
        }
    }
}

@Composable
fun BalanceHeader(
    modifier: Modifier = Modifier,
    smallRowSymbol: String? = null,
    smallRowText: String,
    largeRowPrefix: String? = null,
    largeRowText: String,
    largeRowSymbol: String,
    showSymbol: Boolean,
    hideBalance: Boolean = false,
    isSwipeToHideEnabled: Boolean = false,
    showEyeIcon: Boolean = false,
    onClick: () -> Unit,
    onToggleHideBalance: () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .swipeToHide(
                enabled = isSwipeToHideEnabled,
                onSwipe = onToggleHideBalance,
            )
            .clickableAlpha { onClick() }
    ) {
        SmallRow(
            symbol = smallRowSymbol,
            text = smallRowText,
            hideBalance = hideBalance
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LargeRow(
                prefix = largeRowPrefix,
                text = largeRowText,
                symbol = largeRowSymbol,
                showSymbol = showSymbol,
                hideBalance = hideBalance
            )

            if (showEyeIcon) {
                Spacer(modifier = Modifier.weight(1f))
                AnimatedContent(
                    targetState = hideBalance,
                    transitionSpec = { BalanceAnimations.eyeIconTransition },
                    label = "eyeIconAnimation",
                    modifier = Modifier.size(24.dp)
                ) { isHidden ->
                    if (isHidden) {
                        Icon(
                            painter = painterResource(R.drawable.ic_eye),
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier
                                .size(24.dp)
                                .clickableAlpha { onToggleHideBalance() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LargeRow(
    prefix: String?,
    text: String,
    symbol: String,
    showSymbol: Boolean,
    hideBalance: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hideBalance && prefix != null) {
            Display(
                text = prefix,
                color = Colors.White64,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (showSymbol) {
            Display(
                text = symbol,
                color = Colors.White64,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        AnimatedContent(
            targetState = hideBalance,
            transitionSpec = { BalanceAnimations.mainBalanceTransition },
            label = "largeRowTextAnimation"
        ) { isHidden ->
            Display(text = if (isHidden) UiConstants.HIDE_BALANCE_LONG else text)
        }
    }
}

@Composable
private fun SmallRow(symbol: String?, text: String, hideBalance: Boolean = false) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (symbol != null) {
            Caption13Up(
                text = symbol,
                color = Colors.White64,
            )
        }
        AnimatedContent(
            targetState = hideBalance,
            transitionSpec = { BalanceAnimations.secondaryBalanceTransition },
            label = "smallRowTextAnimation"
        ) { isHidden ->
            Caption13Up(
                text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else text,
                color = Colors.White64,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BalanceHeader(
            smallRowSymbol = "$",
            smallRowText = "27.36",
            largeRowPrefix = "+",
            largeRowText = "136 825",
            largeRowSymbol = "₿",
            showSymbol = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHidden() {
    AppThemeSurface {
        BalanceHeader(
            smallRowSymbol = "$",
            smallRowText = "27.36",
            largeRowPrefix = "+",
            largeRowText = "136 825",
            largeRowSymbol = "₿",
            showSymbol = true,
            hideBalance = true,
            isSwipeToHideEnabled = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = {},
            onToggleHideBalance = {}
        )
    }
}
