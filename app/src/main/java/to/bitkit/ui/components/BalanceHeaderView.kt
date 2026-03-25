package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.animations.BalanceAnimations
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.swipeToHide
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Suppress("CyclomaticComplexMethod")
@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    currencies: CurrencyState = LocalCurrencies.current,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
    useSwipeToHide: Boolean = true,
    showEyeIcon: Boolean = false,
    testTag: String = "",
) {
    val isPreview = LocalInspectionMode.current

    if (isPreview) {
        BalanceHeader(
            isBitcoinPrimary = true,
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
            modifier = modifier,
        )
        return
    }

    val settings = settingsViewModel ?: return
    val currency = currencyViewModel ?: return

    val displayUnit = currencies.displayUnit
    val primaryDisplay = currencies.primaryDisplay

    val converted: ConvertedAmount? = currency.convert(sats = sats)

    val isSwipeToHideEnabled by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalance by settings.hideBalance.collectAsStateWithLifecycle()
    val shouldHideBalance = useSwipeToHide && hideBalance
    val allowSwipeToHide = useSwipeToHide && isSwipeToHideEnabled

    converted?.let { fiat ->
        val btc = fiat.bitcoinDisplay(displayUnit)
        val isBitcoinPrimary = primaryDisplay == PrimaryDisplay.BITCOIN

        BalanceHeader(
            isBitcoinPrimary = isBitcoinPrimary,
            smallRowSymbol = if (isBitcoinPrimary) fiat.symbol else btc.symbol,
            smallRowText = if (isBitcoinPrimary) fiat.formatted else btc.value,
            smallRowIsSymbolSuffix = if (isBitcoinPrimary) fiat.isSymbolSuffix else false,
            smallRowModifier = Modifier.testTag("$testTag-secondary"),
            largeRowPrefix = prefix,
            largeRowText = if (isBitcoinPrimary) btc.value else fiat.formatted,
            largeRowSymbol = if (isBitcoinPrimary) btc.symbol else fiat.symbol,
            largeRowIsSymbolSuffix = if (isBitcoinPrimary) false else fiat.isSymbolSuffix,
            largeRowModifier = Modifier.testTag("$testTag-primary"),
            showSymbol = if (isBitcoinPrimary) showBitcoinSymbol else true,
            hideBalance = shouldHideBalance,
            isSwipeToHideEnabled = allowSwipeToHide,
            showEyeIcon = showEyeIcon,
            onClick = onClick ?: { currency.switchUnit() },
            onToggleHideBalance = { settings.setHideBalance(!hideBalance) },
            testTag = testTag,
            modifier = modifier,
        )
    }
}

@Composable
fun BalanceHeader(
    isBitcoinPrimary: Boolean,
    smallRowSymbol: String? = null,
    smallRowText: String,
    smallRowIsSymbolSuffix: Boolean = false,
    smallRowModifier: Modifier = Modifier,
    largeRowPrefix: String? = null,
    largeRowText: String,
    largeRowSymbol: String,
    largeRowIsSymbolSuffix: Boolean = false,
    largeRowModifier: Modifier = Modifier,
    showSymbol: Boolean,
    hideBalance: Boolean = false,
    isSwipeToHideEnabled: Boolean = false,
    showEyeIcon: Boolean = false,
    onClick: () -> Unit,
    onToggleHideBalance: () -> Unit = {},
    testTag: String? = null,
    modifier: Modifier = Modifier,
) {
    val smallRowState = remember(
        isBitcoinPrimary, smallRowSymbol, smallRowText, smallRowIsSymbolSuffix,
    ) {
        SmallRowState(isBitcoinPrimary, smallRowSymbol, smallRowText, smallRowIsSymbolSuffix)
    }

    val largeRowState = remember(
        isBitcoinPrimary, largeRowPrefix, largeRowText, largeRowSymbol,
        largeRowIsSymbolSuffix, showSymbol,
    ) {
        LargeRowState(
            isBitcoinPrimary, largeRowPrefix, largeRowText, largeRowSymbol,
            largeRowIsSymbolSuffix, showSymbol,
        )
    }

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
        AnimatedContent(
            targetState = smallRowState,
            transitionSpec = {
                BalanceAnimations.swapSmallRowTransition using
                    SizeTransform(clip = false)
            },
            contentKey = { it.isBitcoinPrimary },
            label = "smallRowSwapAnimation",
        ) { state ->
            val scale by transition.animateFloat(label = "smallRowScale") {
                if (it == EnterExitState.Visible) 1f else SMALL_ROW_SWAP_SCALE
            }
            SmallRow(
                symbol = state.symbol,
                text = state.text,
                isSymbolSuffix = state.isSymbolSuffix,
                hideBalance = hideBalance,
                modifier = smallRowModifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            )
        }

        VerticalSpacer(12.dp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = largeRowState,
                transitionSpec = {
                    BalanceAnimations.swapLargeRowTransition using
                        SizeTransform(clip = false)
                },
                contentKey = { it.isBitcoinPrimary },
                label = "largeRowSwapAnimation",
            ) { state ->
                val scale by transition.animateFloat(label = "largeRowScale") {
                    if (it == EnterExitState.Visible) 1f else LARGE_ROW_SWAP_SCALE
                }
                LargeRow(
                    prefix = state.prefix,
                    text = state.text,
                    symbol = state.symbol,
                    showSymbol = state.showSymbol,
                    isSymbolSuffix = state.isSymbolSuffix,
                    hideBalance = hideBalance,
                    modifier = largeRowModifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                )
            }

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

// Matches iOS: .scale(scale: 1.5) for small row, .scale(scale: 0.5) for large row
private const val SMALL_ROW_SWAP_SCALE = 1.5f
private const val LARGE_ROW_SWAP_SCALE = 0.5f

@Immutable
private data class SmallRowState(
    val isBitcoinPrimary: Boolean,
    val symbol: String?,
    val text: String,
    val isSymbolSuffix: Boolean,
)

@Immutable
private data class LargeRowState(
    val isBitcoinPrimary: Boolean,
    val prefix: String?,
    val text: String,
    val symbol: String,
    val isSymbolSuffix: Boolean,
    val showSymbol: Boolean,
)

@Composable
fun LargeRow(
    prefix: String?,
    text: String,
    symbol: String,
    showSymbol: Boolean,
    modifier: Modifier = Modifier,
    isSymbolSuffix: Boolean = false,
    hideBalance: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (!hideBalance && prefix != null) {
            SecondaryDisplay(
                text = prefix,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("MoneySign")
            )
        }
        if (showSymbol && !isSymbolSuffix) {
            SecondaryDisplay(
                text = symbol,
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
        if (showSymbol && isSymbolSuffix) {
            SecondaryDisplay(
                text = symbol,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("MoneyFiatSymbol")
            )
        }
    }
}

@Composable
private fun SecondaryDisplay(text: String, modifier: Modifier = Modifier) {
    Display(
        text = text,
        fontWeight = FontWeight.ExtraBold,
        color = Colors.White64,
        modifier = modifier,
    )
}

@Composable
private fun SmallRow(
    symbol: String?,
    text: String,
    modifier: Modifier = Modifier,
    isSymbolSuffix: Boolean = false,
    hideBalance: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        if (symbol != null && !isSymbolSuffix) {
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
        if (symbol != null && isSymbolSuffix) {
            Caption13Up(
                text = symbol,
                color = Colors.White64,
                modifier = Modifier.testTag("MoneyFiatSymbol")
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BalanceHeader(
            isBitcoinPrimary = true,
            smallRowSymbol = "$",
            smallRowText = "27.36",
            largeRowPrefix = "+",
            largeRowText = "136 825",
            largeRowSymbol = "₿",
            showSymbol = true,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHidden() {
    AppThemeSurface {
        BalanceHeader(
            isBitcoinPrimary = true,
            smallRowSymbol = "$",
            smallRowText = "27.36",
            largeRowPrefix = "+",
            largeRowText = "136 825",
            largeRowSymbol = "₿",
            showSymbol = true,
            hideBalance = true,
            isSwipeToHideEnabled = true,
            onClick = {},
            onToggleHideBalance = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
