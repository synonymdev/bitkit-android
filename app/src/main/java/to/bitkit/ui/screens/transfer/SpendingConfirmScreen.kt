package to.bitkit.ui.screens.transfer

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Gap above each fee grid row after the first, as the Review frame draws it. */
private val FEE_GRID_ROW_GAP = 24.dp

@Composable
fun SpendingConfirmScreen(
    viewModel: SparkFundingViewModel,
    isOffline: Boolean,
    onBackClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onSent: () -> Unit = {},
    onFundingPending: (OnchainSendAttempt) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currency = currencyViewModel

    BackHandler(enabled = state.isSending) {}

    LaunchedEffect(Unit) {
        viewModel.onConfirmAppear()
    }

    LaunchedEffect(viewModel, onSent) {
        viewModel.effects.collect {
            when (it) {
                SideEffect.Sent -> onSent()
                is SideEffect.FundingPending -> onFundingPending(it.attempt)
                else -> Unit
            }
        }
    }

    Box {
        Content(
            state = state,
            onBackClick = { if (!state.isSending) onBackClick() },
            onLearnMoreClick = { if (!state.isSending) onLearnMoreClick() },
            onSpeedChange = viewModel::onSpeedChange,
            onFeesClick = { currency?.switchUnit() },
            onConfirm = viewModel::onConfirm,
        )
        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(
                titleText = stringResource(R.string.lightning__transfer__nav_title),
                modifier = Modifier.statusBarsPadding()
            )
        }
    }
}

@Composable
private fun Content(
    state: UiState,
    onBackClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onSpeedChange: (SparkClaimSpeed) -> Unit = {},
    onFeesClick: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val quote = state.quote

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { if (!state.isSending) DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("SpendingConfirm")
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                ) {
                    VerticalSpacer(32.dp)
                    Display(
                        stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple)
                    )
                    VerticalSpacer(8.dp)

                    SpendingFeeGrid(quote = quote, speed = state.speed, onClick = onFeesClick)

                    VerticalSpacer(24.dp)

                    SpendingSpeedRows(quote = quote, speed = state.speed, onSpeedChange = onSpeedChange)

                    VerticalSpacer(24.dp)

                    PrimaryButton(
                        text = stringResource(R.string.common__learn_more),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = onLearnMoreClick,
                        modifier = Modifier.testTag("SpendingConfirmMore")
                    )
                    VerticalSpacer(16.dp)
                }
            }

            val canConfirm = quote != null && quote.toSpendingSats(state.speed) > 0 && !state.isQuoting
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = state.isSending || state.isQuoting || quote == null,
                confirmed = state.isSending,
                enabled = canConfirm,
                color = Colors.Purple,
                onConfirm = { if (canConfirm) onConfirm() },
            )
            VerticalSpacer(16.dp)
        }
    }
}

/**
 * The four fee tiles of a transfer to spending, at the selected claim [speed], plus the deposit [address] a hardware
 * wallet confirms on the device, with [rowGap] above each row after the first. With [onClick], a tap anywhere on the
 * grid switches the amounts between bitcoin and fiat.
 */
@Composable
internal fun SpendingFeeGrid(
    quote: Quote?,
    speed: SparkClaimSpeed,
    modifier: Modifier = Modifier,
    address: String? = null,
    rowGap: Dp = FEE_GRID_ROW_GAP,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clickableAlpha(onClick = onClick)
            .testTag("SpendingConfirmFees")
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__network_fee),
                amount = quote?.networkFeeSats ?: 0,
                modifier = Modifier.testTag("SpendingConfirmNetworkFee")
            )
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                amount = quote?.claimFeeSats(speed) ?: 0,
                modifier = Modifier.testTag("SpendingConfirmServiceFee")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__amount),
                amount = quote?.toSpendingSats(speed) ?: 0,
                topPadding = rowGap,
                modifier = Modifier.testTag("SpendingConfirmAmount")
            )
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__total),
                amount = quote?.totalSats ?: 0,
                topPadding = rowGap,
                modifier = Modifier.testTag("SpendingConfirmTotal")
            )
        }
        if (address != null) {
            Column(
                modifier = Modifier
                    .padding(top = rowGap)
                    .testTag("SpendingConfirmAddress")
            ) {
                Caption13Up(text = stringResource(R.string.lightning__transfer_hw__address), color = Colors.White64)
                VerticalSpacer(8.dp)
                BodySSB(
                    text = address,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTextStyles.BodySSB.copy(fontFeatureSettings = NO_CONTEXTUAL_ALTERNATES),
                )
                VerticalSpacer(16.dp)
                HorizontalDivider()
            }
        }
    }
}

/** The Fast and Normal rows a transfer to spending picks its claim speed with. */
@Composable
internal fun SpendingSpeedRows(
    quote: Quote?,
    speed: SparkClaimSpeed,
    onSpeedChange: (SparkClaimSpeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SpeedItem(
            title = stringResource(R.string.fee__fast__title),
            description = stringResource(R.string.lightning__spending_confirm__instant_text),
            feeSats = quote?.instantFeeSats,
            feeCaption = stringResource(R.string.lightning__spending_confirm__fee_max),
            icon = R.drawable.ic_speed_fast,
            isSelected = speed == SparkClaimSpeed.Instant,
            onClick = { onSpeedChange(SparkClaimSpeed.Instant) },
            modifier = Modifier.testTag("SpendingConfirmInstant")
        )
        HorizontalDivider()
        SpeedItem(
            title = stringResource(R.string.fee__normal__title),
            description = stringResource(R.string.lightning__spending_confirm__normal_text),
            feeSats = quote?.laterFeeSats,
            feeCaption = stringResource(R.string.lightning__spending_confirm__fee_est),
            icon = R.drawable.ic_clock,
            isSelected = speed == SparkClaimSpeed.Later,
            onClick = { onSpeedChange(SparkClaimSpeed.Later) },
            modifier = Modifier.testTag("SpendingConfirmLater")
        )
    }
}

@Composable
private fun SpeedItem(
    title: String,
    description: String,
    feeSats: Long?,
    feeCaption: String,
    @DrawableRes icon: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clickableAlpha(onClick = onClick)
            .semantics { selected = isSelected }
            .padding(vertical = 16.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Colors.Purple,
            modifier = Modifier.size(32.dp),
        )
        HorizontalSpacer(16.dp)
        Column(modifier = Modifier.weight(1f)) {
            BodyMSB(title, color = MaterialTheme.colorScheme.primary)
            BodySSB(description, color = MaterialTheme.colorScheme.secondary)
        }
        if (feeSats != null) {
            HorizontalSpacer(12.dp)
            Column(horizontalAlignment = Alignment.End) {
                MoneyMSB(sats = feeSats, showSymbol = true)
                BodySSB(feeCaption, color = Colors.White64)
            }
        }
        HorizontalSpacer(12.dp)
        Icon(
            painter = painterResource(R.drawable.ic_checkmark),
            contentDescription = null,
            tint = Colors.Purple,
            modifier = Modifier
                .size(22.dp)
                .alpha(if (isSelected) 1f else 0f)
        )
    }
}

private val previewQuote = Quote(
    address = "bcrt1q",
    amountSats = 50_000,
    networkFeeSats = 577,
    instantFeeSats = 1_350,
    laterFeeSats = 198,
    spendableSats = 158_234,
)

@Preview(showSystemUi = true, showBackground = true, name = "Fast")
@Composable
private fun PreviewFast() {
    AppThemeSurface {
        Content(state = UiState(amountSats = 50_000, quote = previewQuote))
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Normal")
@Composable
private fun PreviewNormal() {
    AppThemeSurface {
        Content(state = UiState(amountSats = 50_000, quote = previewQuote, speed = SparkClaimSpeed.Later))
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Loading")
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        Content(state = UiState(amountSats = 50_000, isQuoting = true))
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Small screen", device = NEXUS_5)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        Content(state = UiState(amountSats = 50_000, quote = previewQuote))
    }
}

/** Keeps the font from drawing an "x" between digits as "×", so an address reads exactly as the device shows it. */
private const val NO_CONTEXTUAL_ALTERNATES = "calt 0"
