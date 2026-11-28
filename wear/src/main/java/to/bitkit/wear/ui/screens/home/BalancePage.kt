package to.bitkit.wear.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.ext.openNfcSettings
import to.bitkit.wear.ui.animations.BalanceAnimations
import to.bitkit.wear.ui.animations.LARGE_ROW_SWAP_SCALE
import to.bitkit.wear.ui.animations.SMALL_ROW_SWAP_SCALE
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.LargeAmountRow
import to.bitkit.wear.ui.components.NfcHint
import to.bitkit.wear.ui.components.NfcHintState
import to.bitkit.wear.ui.components.SmallAmountRow
import to.bitkit.wear.ui.components.VerticalSpacer
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.amountRowGap
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.components.nfcHintState
import to.bitkit.wear.ui.modifiers.clickableAlpha
import to.bitkit.wear.ui.theme.Colors

/** The offline icon's distance from the top edge, which keeps it below the time. */
private val OFFLINE_ICON_TOP_PADDING = 40.dp

/** The offline icon's size: small, as a status mark. */
private val OFFLINE_ICON_SIZE = 18.dp

/** The extra touch area around the offline icon, which makes it a 48dp target without moving the icon. */
private val OFFLINE_TOUCH_PADDING = 15.dp

/** The NFC hint's distance from the bottom edge, which keeps it above the page indicator. */
private val NFC_HINT_BOTTOM_PADDING = 22.dp

/** The side margin of the balance, which sits at the round screen's widest point, as a share of the screen width. */
private const val BALANCE_SIDE_INSET_FRACTION = 0.05f

/** The opacity of the Bitkit mark that stands in for a balance that is hidden or not known yet. */
private const val MARK_ALPHA = 0.4f

/** The balance, large and centered, with the other unit below. A tap swaps the units. */
@Composable
fun BalancePage(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isAmbient = isAmbient()
    Content(
        uiState = uiState,
        isAmbient = isAmbient,
        nfcHint = nfcHintState(uiState.nfc, uiState.isWalletReady, isAmbient),
        onBalanceClick = viewModel::onUnitToggle,
        onRetryClick = viewModel::onRetryClick,
        onNfcSettingsClick = context::openNfcSettings,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: HomeUiState,
    isAmbient: Boolean,
    nfcHint: NfcHintState,
    onBalanceClick: () -> Unit,
    onRetryClick: () -> Unit,
    onNfcSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        sideInsetFraction = BALANCE_SIDE_INSET_FRACTION,
        overlay = {
            NfcHint(
                state = nfcHint,
                onDisabledClick = onNfcSettingsClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NFC_HINT_BOTTOM_PADDING)
            )
            if (uiState.isOffline && !isAmbient) {
                OfflineIcon(
                    onClick = onRetryClick,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = OFFLINE_ICON_TOP_PADDING - OFFLINE_TOUCH_PADDING)
                )
            }
            if (uiState.isLoading) BezelLoading()
        },
        modifier = modifier
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
        modifier = modifier
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
                    SmallAmountRow(
                        amount = it,
                        amountTag = "WatchHomeBalanceSecondary",
                        modifier = Modifier.swapScale(SMALL_ROW_ORIGIN) { scale }
                    )
                }
            }
            VerticalSpacer(amountRowGap())
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
                    LargeAmountRow(
                        amount = it,
                        maxWidth = maxRowWidth,
                        amountTag = "WatchHomeBalance",
                        symbolTag = "WatchHomeBalanceSymbol",
                        modifier = Modifier.swapScale(LARGE_ROW_ORIGIN) { scale }
                    )
                }
            }
        }
    }
}

/** Scales the large row from its top start, as on the phone. */
private val LARGE_ROW_ORIGIN = TransformOrigin(0f, 0f)

/**
 * Scales the small row from its bottom start, unlike the phone's top start: at the watch's tight row gap, a small row
 * growing downwards to 1.5x mid-swap would overlap the large row; growing upwards it stays clear.
 */
private val SMALL_ROW_ORIGIN = TransformOrigin(0f, 1f)

private fun Modifier.swapScale(origin: TransformOrigin, scale: () -> Float): Modifier = graphicsLayer {
    scaleX = scale()
    scaleY = scale()
    transformOrigin = origin
}

@Immutable
private data class RowState(
    val isBitcoinPrimary: Boolean,
    val amount: DisplayAmount?,
)

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
private fun OfflineIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(R.drawable.ic_cloud_off),
        contentDescription = stringResource(R.string.home__offline),
        tint = Colors.White64,
        modifier = modifier
            .clickableAlpha(onClickLabel = stringResource(R.string.common__retry), onClick = onClick)
            .padding(OFFLINE_TOUCH_PADDING)
            .size(OFFLINE_ICON_SIZE)
            .testTag("WatchHomeOffline")
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
            nfcHint = NfcHintState.Active,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewNfcOff() {
    WearPreview {
        Content(
            uiState = HomeUiState(primaryAmount = PreviewBitcoin, secondaryAmount = PreviewFiat),
            isAmbient = false,
            nfcHint = NfcHintState.Disabled,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
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
            nfcHint = NfcHintState.Active,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
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
            nfcHint = NfcHintState.Hidden,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
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
            nfcHint = NfcHintState.Hidden,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
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
            nfcHint = NfcHintState.Hidden,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewOffline() {
    WearPreview {
        Content(
            uiState = HomeUiState(isOffline = true, primaryAmount = PreviewBitcoin, secondaryAmount = PreviewFiat),
            isAmbient = false,
            nfcHint = NfcHintState.Hidden,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
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
            nfcHint = NfcHintState.Hidden,
            onBalanceClick = {},
            onRetryClick = {},
            onNfcSettingsClick = {},
        )
    }
}

private val PreviewBitcoin = DisplayAmount(symbol = "₿", text = "125 000")
private val PreviewFiat = DisplayAmount(symbol = "$", text = "121.38")
