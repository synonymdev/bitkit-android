package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.animations.BalanceAnimations
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.swipeToHide
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
    useSwipeToHide: Boolean = true,
    showEyeIcon: Boolean = false,
    testTag: String = "",
) {
    val isPreview = LocalInspectionMode.current

    if (isPreview) {
        BalanceHeader(
            modifier = modifier,
            smallRowSymbol = "$",
            smallRowText = "12.34",
            largeRowPrefix = prefix,
            largeRowText = sats.formatToModernDisplay(),
            largeRowSymbol = BITCOIN_SYMBOL,
            showSymbol = showBitcoinSymbol,
            hideBalance = false,
            isSwipeToHideEnabled = false,
            showEyeIcon = showEyeIcon,
            onClick = {},
            onToggleHideBalance = {},
            testTag = testTag,
        )
        return
    }

    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return
    val (_, _, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = currency.convert(sats = sats)

    val isSwipeToHideEnabled by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()
    val shouldHideBalance = useSwipeToHide && hideBalance
    val allowSwipeToHide = useSwipeToHide && isSwipeToHideEnabled

    converted?.let { fiat ->
        val btc = fiat.bitcoinDisplay(displayUnit)
        val isBitcoinPrimary = primaryDisplay == PrimaryDisplay.BITCOIN

        BalanceHeader(
            modifier = modifier,
            smallRowSymbol = if (isBitcoinPrimary) fiat.symbol else btc.symbol,
            smallRowText = if (isBitcoinPrimary) fiat.formatted else btc.value,
            smallRowModifier = Modifier.testTag("$testTag-secondary"),
            largeRowPrefix = prefix,
            largeRowText = if (isBitcoinPrimary) btc.value else fiat.formatted,
            largeRowSymbol = if (isBitcoinPrimary) btc.symbol else fiat.symbol,
            largeRowModifier = Modifier.testTag("$testTag-primary"),
            showSymbol = if (isBitcoinPrimary) showBitcoinSymbol else true,
            hideBalance = shouldHideBalance,
            isSwipeToHideEnabled = allowSwipeToHide,
            showEyeIcon = showEyeIcon,
            onClick = onClick ?: { currency.switchUnit() },
            onToggleHideBalance = { settings.setHideBalance(!hideBalance) },
            testTag = testTag,
        )
    }
}

@Composable
fun BalanceHeader(
    modifier: Modifier = Modifier,
    smallRowSymbol: String? = null,
    smallRowText: String,
    smallRowModifier: Modifier = Modifier,
    largeRowPrefix: String? = null,
    largeRowText: String,
    largeRowSymbol: String,
    largeRowModifier: Modifier = Modifier,
    showSymbol: Boolean,
    hideBalance: Boolean = false,
    isSwipeToHideEnabled: Boolean = false,
    showEyeIcon: Boolean = false,
    onClick: () -> Unit,
    onToggleHideBalance: () -> Unit = {},
    testTag: String? = null,
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
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
    ) {
        SmallRow(
            symbol = smallRowSymbol,
            text = smallRowText,
            hideBalance = hideBalance,
            modifier = smallRowModifier,
        )

        VerticalSpacer(12.dp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LargeRow(
                prefix = largeRowPrefix,
                text = largeRowText,
                symbol = largeRowSymbol,
                showSymbol = showSymbol,
                hideBalance = hideBalance,
                modifier = largeRowModifier,
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
                                .testTag("ShowBalance")
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
    modifier: Modifier = Modifier,
    hideBalance: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (!hideBalance && prefix != null) {
            Display(
                text = prefix,
                color = Colors.White64,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("MoneySign")
            )
        }
        if (showSymbol) {
            Display(
                text = symbol,
                color = Colors.White64,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("MoneyFiatSymbol")
            )
        }
        AnimatedContent(
            targetState = hideBalance,
            transitionSpec = { BalanceAnimations.mainBalanceTransition },
            label = "largeRowTextAnimation",
        ) { isHidden ->
            Display(
                text = if (isHidden) UiConstants.HIDE_BALANCE_LONG else text,
                modifier = Modifier.testTag("MoneyText")
            )
        }
    }
}

@Composable
private fun SmallRow(
    symbol: String?,
    text: String,
    modifier: Modifier = Modifier,
    hideBalance: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        if (symbol != null) {
            Caption13Up(
                text = symbol,
                color = Colors.White64,
                modifier = Modifier.testTag("MoneyFiatSymbol")
            )
        }
        AnimatedContent(
            targetState = hideBalance,
            transitionSpec = { BalanceAnimations.secondaryBalanceTransition },
            label = "smallRowTextAnimation",
        ) { isHidden ->
            Caption13Up(
                text = if (isHidden) UiConstants.HIDE_BALANCE_SHORT else text,
                color = Colors.White64,
                modifier = Modifier.testTag("MoneyText")
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
