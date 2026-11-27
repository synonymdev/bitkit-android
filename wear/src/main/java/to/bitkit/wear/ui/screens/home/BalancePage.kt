package to.bitkit.wear.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.animations.BalanceAnimations
import to.bitkit.wear.ui.animations.LARGE_ROW_SWAP_SCALE
import to.bitkit.wear.ui.animations.SMALL_ROW_SWAP_SCALE
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.NfcHint
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.modifiers.clickableAlpha
import to.bitkit.wear.ui.theme.AppTextStyles
import to.bitkit.wear.ui.theme.Colors

/** The NFC hint's distance from the bottom edge, which keeps it above the page indicator. */
private val NFC_HINT_BOTTOM_PADDING = 22.dp

/** The space between the large balance and its symbol, as on the phone. */
private val SYMBOL_SPACING = 8.dp

/** The side margin of the balance, which sits at the round screen's widest point, as a share of the screen width. */
private const val BALANCE_SIDE_INSET_FRACTION = 0.05f

/** The large balance's size on a [SMALL_SCREEN_DP] screen. */
private const val LARGE_SP_SMALL_SCREEN = 26f

/** The large balance's size on a [LARGE_SCREEN_DP] screen; sizes in between are interpolated. */
private const val LARGE_SP_LARGE_SCREEN = 30f

/** The width of the small round screen. */
private const val SMALL_SCREEN_DP = 192f

/** The width of the large round screen. */
private const val LARGE_SCREEN_DP = 227f

/** The smallest size of the secondary row: Wear's minimum for essential text (WO-V14). */
private const val MIN_SMALL_ROW_SP = 12f

/**
 * The share of the phone's text sizes the balance uses on this screen: the large amount is about 26sp on a 192dp
 * screen and 30sp on a 227dp screen, with the phone's proportions between the rows.
 */
@Composable
private fun balanceScale(): Float {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val progress = ((widthDp - SMALL_SCREEN_DP) / (LARGE_SCREEN_DP - SMALL_SCREEN_DP)).coerceIn(0f, 1f)
    val largeSp = LARGE_SP_SMALL_SCREEN + (LARGE_SP_LARGE_SCREEN - LARGE_SP_SMALL_SCREEN) * progress
    return largeSp / AppTextStyles.Display.fontSize.value
}

/** The opacity of the Bitkit mark that stands in for a balance that is hidden or not known yet. */
private const val MARK_ALPHA = 0.4f

/** The balance, large and centered, with the other unit below. A tap swaps the units. */
@Composable
fun BalancePage(
    onSeedKeyLoss: () -> Unit,
    onWalletMissing: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.isRemoved, uiState.isMissing) {
        if (uiState.isRemoved) onSeedKeyLoss()
        if (uiState.isMissing) onWalletMissing()
    }
    Content(
        uiState = uiState,
        isAmbient = isAmbient(),
        onBalanceClick = viewModel::onUnitToggle,
        onRetryClick = viewModel::onRetryClick,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: HomeUiState,
    isAmbient: Boolean,
    onBalanceClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        sideInsetFraction = BALANCE_SIDE_INSET_FRACTION,
        overlay = {
            NfcHint(
                visible = !isAmbient,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NFC_HINT_BOTTOM_PADDING)
            )
            if (uiState.isLoading) BezelLoading()
        },
        modifier = modifier,
    ) {
        if (uiState.isError && !isAmbient) {
            Error(onRetryClick = onRetryClick)
            return@CenteredScreen
        }
        Balance(
            uiState = uiState,
            isAmbient = isAmbient,
            onClick = onBalanceClick,
        )
    }
}

@Composable
private fun Balance(
    uiState: HomeUiState,
    isAmbient: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAmbient) {
        val hidden = stringResource(R.string.home__balance_hidden)
        Mark(
            modifier = modifier
                .semantics { contentDescription = hidden }
                .testTag("WatchHomeBalanceHidden")
        )
        return
    }
    val large = uiState.primaryAmount
    if (large == null) {
        Mark(modifier = modifier.testTag("WatchHomeBalancePending"))
        return
    }
    BalanceHeader(
        isBitcoinPrimary = uiState.isBitcoinPrimary,
        large = large,
        small = uiState.secondaryAmount,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun BalanceHeader(
    isBitcoinPrimary: Boolean,
    large: DisplayAmount,
    small: DisplayAmount?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val smallRowState = remember(isBitcoinPrimary, small) { RowState(isBitcoinPrimary, small) }
    val largeRowState = remember(isBitcoinPrimary, large) { RowState(isBitcoinPrimary, large) }
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth()
    ) {
        val maxRowWidth = maxWidth
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .clickableAlpha(onClickLabel = stringResource(R.string.home__toggle_unit), onClick = onClick)
                .testTag("WatchHomeBalanceToggle")
        ) {
            AnimatedContent(
                targetState = smallRowState,
                transitionSpec = { BalanceAnimations.swapSmallRowTransition using SizeTransform(clip = false) },
                contentKey = { it.isBitcoinPrimary },
                contentAlignment = Alignment.TopStart,
                label = "smallRowSwapAnimation",
            ) { state ->
                val scale by transition.animateFloat(label = "smallRowScale") {
                    if (it == EnterExitState.Visible) 1f else SMALL_ROW_SWAP_SCALE
                }
                state.amount?.let {
                    SmallRow(amount = it, modifier = Modifier.swapScale { scale })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            AnimatedContent(
                targetState = largeRowState,
                transitionSpec = { BalanceAnimations.swapLargeRowTransition using SizeTransform(clip = false) },
                contentKey = { it.isBitcoinPrimary },
                contentAlignment = Alignment.TopStart,
                label = "largeRowSwapAnimation",
            ) { state ->
                val scale by transition.animateFloat(label = "largeRowScale") {
                    if (it == EnterExitState.Visible) 1f else LARGE_ROW_SWAP_SCALE
                }
                state.amount?.let {
                    LargeRow(amount = it, maxWidth = maxRowWidth, modifier = Modifier.swapScale { scale })
                }
            }
        }
    }
}

/** Scales a row from its top start, as on the phone. */
private fun Modifier.swapScale(scale: () -> Float): Modifier = graphicsLayer {
    scaleX = scale()
    scaleY = scale()
    transformOrigin = TransformOrigin(0f, 0f)
}

@Composable
private fun LargeRow(
    amount: DisplayAmount,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val style = rememberFittedDisplayStyle(amount, maxWidth)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (!amount.isSymbolSuffix) {
            LargeSymbol(symbol = amount.symbol, style = style, modifier = Modifier.padding(end = SYMBOL_SPACING))
        }
        Text(
            text = amount.text.uppercase(),
            style = style,
            maxLines = 1,
            modifier = Modifier.testTag("WatchHomeBalance")
        )
        if (amount.isSymbolSuffix) {
            LargeSymbol(symbol = amount.symbol, style = style, modifier = Modifier.padding(start = SYMBOL_SPACING))
        }
    }
}

@Composable
private fun LargeSymbol(
    symbol: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = symbol.uppercase(),
        style = style.merge(fontWeight = FontWeight.ExtraBold, color = Colors.White64),
        maxLines = 1,
        modifier = modifier.testTag("WatchHomeBalanceSymbol")
    )
}

/** The phone's large balance style at this screen's size, scaled down further only if [amount] would not fit. */
@Composable
private fun rememberFittedDisplayStyle(amount: DisplayAmount, maxWidth: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scale = balanceScale()
    val base = AppTextStyles.Display.merge(
        color = Colors.White,
        fontSize = AppTextStyles.Display.fontSize * scale,
        lineHeight = AppTextStyles.Display.lineHeight * scale,
        letterSpacing = AppTextStyles.Display.letterSpacing * scale,
    )
    return remember(amount, maxWidth, density, base) {
        val symbolStyle = base.merge(fontWeight = FontWeight.ExtraBold)
        val width = measurer.measure(amount.text.uppercase(), base, maxLines = 1).size.width +
            measurer.measure(amount.symbol.uppercase(), symbolStyle, maxLines = 1).size.width +
            with(density) { SYMBOL_SPACING.toPx() }
        val fit = with(density) { maxWidth.toPx() } / width
        if (fit >= 1f) {
            base
        } else {
            base.merge(
                fontSize = base.fontSize * fit,
                lineHeight = base.lineHeight * fit,
                letterSpacing = base.letterSpacing * fit,
            )
        }
    }
}

@Composable
private fun SmallRow(
    amount: DisplayAmount,
    modifier: Modifier = Modifier,
) {
    val scale = balanceScale()
    val fontSize = (AppTextStyles.CaptionM.fontSize.value * scale).coerceAtLeast(MIN_SMALL_ROW_SP)
    val style = AppTextStyles.CaptionM.merge(
        color = Colors.White64,
        fontSize = fontSize.sp,
        lineHeight = AppTextStyles.CaptionM.lineHeight * (fontSize / AppTextStyles.CaptionM.fontSize.value),
        letterSpacing = AppTextStyles.CaptionM.letterSpacing * scale,
    )
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        if (!amount.isSymbolSuffix) Text(text = amount.symbol.uppercase(), style = style)
        Text(
            text = amount.text.uppercase(),
            style = style,
            modifier = Modifier.testTag("WatchHomeBalanceSecondary")
        )
        if (amount.isSymbolSuffix) Text(text = amount.symbol.uppercase(), style = style)
    }
}

@Immutable
private data class RowState(
    val isBitcoinPrimary: Boolean,
    val amount: DisplayAmount?,
)

/** A small dimmed Bitkit mark, shown instead of a balance that is hidden or not known yet. */
@Composable
private fun Mark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.bitkit_logo),
        contentDescription = null,
        alpha = MARK_ALPHA,
        modifier = modifier.fillMaxWidth(0.4f)
    )
}

@Composable
private fun Error(
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.home__error),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("WatchHomeError")
        )
        FilledTonalIconButton(
            onClick = onRetryClick,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("WatchHomeRetry")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.common__retry),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = HomeUiState(primaryAmount = PreviewBitcoin, secondaryAmount = PreviewFiat),
            isAmbient = false,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewWide() {
    WearPreview {
        Content(
            uiState = HomeUiState(
                primaryAmount = DisplayAmount(symbol = "₿", text = "21 000 000"),
                secondaryAmount = DisplayAmount(symbol = "$", text = "21,543,210,987.65"),
            ),
            isAmbient = false,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewSyncing() {
    WearPreview {
        Content(
            uiState = HomeUiState(isLoading = true, primaryAmount = PreviewBitcoin, secondaryAmount = PreviewFiat),
            isAmbient = false,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewFirstConnect() {
    WearPreview {
        Content(
            uiState = HomeUiState(isLoading = true),
            isAmbient = false,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewError() {
    WearPreview {
        Content(
            uiState = HomeUiState(isError = true),
            isAmbient = false,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmbient() {
    WearPreview {
        Content(
            uiState = HomeUiState(primaryAmount = PreviewBitcoin, secondaryAmount = PreviewFiat),
            isAmbient = true,
            onBalanceClick = {},
            onRetryClick = {},
        )
    }
}

private val PreviewBitcoin = DisplayAmount(symbol = "₿", text = "125 000")
private val PreviewFiat = DisplayAmount(symbol = "$", text = "121.38")
