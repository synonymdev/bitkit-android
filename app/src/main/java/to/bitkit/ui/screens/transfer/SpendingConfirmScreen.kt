package to.bitkit.ui.screens.transfer

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val SWIPE_ROTATION_DEGREES = 14f

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
    onConfirm: () -> Unit = {},
) {
    val swipeProgress = remember { mutableFloatStateOf(0f) }
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

                    SpendingFeeGrid(quote = quote, speed = state.speed)

                    VerticalSpacer(16.dp)

                    SpendingSpeedRows(quote = quote, speed = state.speed, onSpeedChange = onSpeedChange)

                    VerticalSpacer(16.dp)

                    PrimaryButton(
                        text = stringResource(R.string.common__learn_more),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = onLearnMoreClick,
                        modifier = Modifier.testTag("SpendingConfirmMore")
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.coin_stack_x),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .padding(horizontal = 44.dp, vertical = 8.dp)
                                .graphicsLayer { rotationZ = swipeProgress.floatValue * SWIPE_ROTATION_DEGREES }
                        )
                    }
                }
            }

            val canConfirm = quote != null && quote.toSpendingSats(state.speed) > 0 && !state.isQuoting
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = state.isSending || state.isQuoting || quote == null,
                confirmed = state.isSending,
                enabled = canConfirm,
                color = Colors.Purple,
                progress = swipeProgress,
                onConfirm = { if (canConfirm) onConfirm() },
            )
            VerticalSpacer(16.dp)
        }
    }
}

/**
 * The four fee tiles of a transfer to spending, at the selected claim [speed]. The claim fee is an estimate at either
 * speed, so the tiles that come from it, Spending fee and To spending, show `±`; Network fees and Total are exact.
 */
@Composable
internal fun SpendingFeeGrid(quote: Quote?, speed: SparkClaimSpeed, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        val estimatePrefix = stringResource(R.string.lightning__spending_confirm__about)
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
                label = stringResource(R.string.lightning__spending_confirm__spending_fee),
                amount = quote?.claimFeeSats(speed) ?: 0,
                prefix = estimatePrefix,
                modifier = Modifier.testTag("SpendingConfirmSpendingFee")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__amount),
                amount = quote?.toSpendingSats(speed) ?: 0,
                prefix = estimatePrefix,
                modifier = Modifier.testTag("SpendingConfirmAmount")
            )
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__total),
                amount = quote?.totalSats ?: 0,
                modifier = Modifier.testTag("SpendingConfirmTotal")
            )
        }
    }
}

/** The Instant and Later rows a transfer to spending picks its claim speed with. */
@Composable
internal fun SpendingSpeedRows(
    quote: Quote?,
    speed: SparkClaimSpeed,
    onSpeedChange: (SparkClaimSpeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SpeedItem(
            title = stringResource(R.string.fee__instant__title),
            description = stringResource(R.string.lightning__spending_confirm__instant_text),
            feePrefix = stringResource(R.string.lightning__spending_confirm__about),
            feeSats = quote?.instantFeeSats,
            icon = R.drawable.ic_speed_fast,
            iconColor = Colors.Purple,
            isSelected = speed == SparkClaimSpeed.Instant,
            onClick = { onSpeedChange(SparkClaimSpeed.Instant) },
            modifier = Modifier.testTag("SpendingConfirmInstant")
        )
        SpeedItem(
            title = stringResource(R.string.common__later),
            description = stringResource(R.string.lightning__spending_confirm__later_text),
            feePrefix = stringResource(R.string.lightning__spending_confirm__about),
            feeSats = quote?.laterFeeSats,
            icon = R.drawable.ic_speed_normal,
            iconColor = Colors.Purple,
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
    feePrefix: String,
    feeSats: Long?,
    @DrawableRes icon: Int,
    iconColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickableAlpha(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (isSelected) Modifier.background(Colors.White06) else Modifier)
                .padding(horizontal = 16.dp)
                .heightIn(min = 72.dp)
                .padding(vertical = 8.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp),
            )
            HorizontalSpacer(16.dp)
            // The text wraps, so the fee keeps its lines at the end of the row.
            Column(modifier = Modifier.weight(1f)) {
                BodyMSB(title, color = MaterialTheme.colorScheme.primary)
                BodySSB(description, color = MaterialTheme.colorScheme.secondary)
            }
            if (feeSats != null) {
                HorizontalSpacer(8.dp)
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        BodyMSB("$feePrefix ", color = Colors.White64)
                        MoneyMSB(sats = feeSats, showSymbol = true)
                    }
                    Row {
                        BodySSB("$feePrefix ", color = Colors.White64)
                        MoneySSB(sats = feeSats, unit = LocalCurrencies.current.primaryDisplay.not(), showSymbol = true)
                    }
                }
            }
        }
        HorizontalDivider()
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

@Preview(showSystemUi = true, showBackground = true, name = "Instant")
@Composable
private fun PreviewInstant() {
    AppThemeSurface {
        Content(state = UiState(amountSats = 50_000, quote = previewQuote))
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Later")
@Composable
private fun PreviewLater() {
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
