package to.bitkit.ui.screens.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.filterOpen
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.safe
import to.bitkit.ui.components.AmountSlider
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.screenWidth
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.SavingsSelection
import to.bitkit.viewmodels.SavingsSwapQuote
import to.bitkit.viewmodels.SavingsTransferMode

/** The piggybank's width against the screen's, as iOS draws its 256 pt piggybank on a 402 pt screen. */
private const val PIGGY_SCREEN_SHARE = 256f / 402f

@Composable
fun SavingsConfirmScreen(
    isOffline: Boolean,
    onConfirm: () -> Unit,
    onAdvancedClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val currency = currencyViewModel ?: return
    val transfer = transferViewModel ?: return
    val wallet = walletViewModel ?: return

    val lightningState by wallet.lightningState.collectAsStateWithLifecycle()
    val openChannels = lightningState.channels.filterOpen()

    val sparkState by wallet.sparkState.collectAsStateWithLifecycle()
    val sparkSats = sparkState.balanceSats

    // Spark counts as one more item: the selection list is offered once there are two to choose from.
    val hasMultiple = openChannels.size + (if (sparkSats > 0uL) 1 else 0) > 1

    val selection by transfer.savingsSelection.collectAsStateWithLifecycle()
    val hasSelected = !selection.isAll

    val isSparkTooSmall by transfer.isSparkWithdrawTooSmall.collectAsStateWithLifecycle()
    val channels = selection.channels(openChannels)
    // A Spark balance too small to move is left out, so the channels picked with it still move.
    val pricesSpark = selection.withdrawsSpark(sparkSats)
    val withdrawsSpark = selection.withdrawsSpark(sparkSats, isSparkTooSmall)
    val isOnlySparkTooSmall = pricesSpark && isSparkTooSmall && channels.isEmpty()

    val channelsAmount = channels.sumOf { it.amountOnClose }
    val sparkAmount = if (withdrawsSpark) sparkSats else 0uL

    val swapState by transfer.savingsSwapState.collectAsStateWithLifecycle()
    val sparkFeeSats by transfer.sparkWithdrawFeeSats.collectAsStateWithLifecycle()
    val isSparkFeePricing by transfer.isSparkFeePricing.collectAsStateWithLifecycle()

    // Pull the latest node balances so a just-received payment is reflected in the amounts below.
    LaunchedEffect(Unit) {
        wallet.refreshBalances()
    }

    // Present the swap fee before the user commits. Recomputed when the amount changes.
    // A zero amount clears an earlier quote, so a Spark-only transfer never swaps channel funds.
    LaunchedEffect(channelsAmount) {
        transfer.loadSavingsSwapQuote(channelsAmount, channels.totalNextOutboundHtlcLimitSats())
    }

    LaunchedEffect(pricesSpark, sparkSats) {
        if (pricesSpark) transfer.loadSparkWithdrawFee(sparkSats)
    }

    Box {
        SavingsConfirmContent(
            fallbackAmount = channelsAmount,
            sparkAmount = sparkAmount,
            sparkFeeSats = sparkFeeSats.takeIf { withdrawsSpark },
            // Spark's part moves only at the fee shown, so nothing is confirmed before it is priced.
            isSparkFeePricing = withdrawsSpark && isSparkFeePricing,
            isSparkUnpriced = withdrawsSpark && sparkFeeSats == null || isOnlySparkTooSmall,
            isNothingSelected = channels.isEmpty() && !withdrawsSpark,
            isSparkTooSmall = isOnlySparkTooSmall,
            isSwapEnabled = swapState.isSwapEnabled,
            quote = swapState.quote,
            isQuoteLoading = swapState.isLoading,
            minSat = swapState.minSat,
            maxSat = swapState.maxSat,
            onAmountChange = { transfer.onSwapAmountChange(it.toULong()) },
            hasMultiple = hasMultiple,
            hasSelected = hasSelected,
            onBackClick = onBackClick,
            onAmountClick = { currency.switchUnit() },
            onAdvancedClick = onAdvancedClick,
            onSelectAllClick = { transfer.setSavingsSelection(SavingsSelection()) },
            onSparkFeeRetry = { transfer.loadSparkWithdrawFee(sparkSats) },
            onSwipeConfirm = { shownSparkSats, shownFeeSats ->
                transfer.confirmSavingsSwipe(channels, withdrawsSpark, shownSparkSats, shownFeeSats)
                    .also { if (it) onConfirm() }
            },
            onCloseConfirm = {
                transfer.onTransferToSavingsConfirm(channels, withdrawsSpark, SavingsTransferMode.CLOSE, sparkFeeSats)
                onConfirm()
            },
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
internal fun SavingsConfirmContent(
    fallbackAmount: ULong,
    sparkAmount: ULong,
    sparkFeeSats: ULong?,
    isSparkFeePricing: Boolean,
    isSparkUnpriced: Boolean,
    isNothingSelected: Boolean = false,
    isSparkTooSmall: Boolean,
    isSwapEnabled: Boolean,
    quote: SavingsSwapQuote?,
    isQuoteLoading: Boolean,
    minSat: ULong,
    maxSat: ULong,
    onAmountChange: (Long) -> Unit,
    hasMultiple: Boolean,
    hasSelected: Boolean,
    onBackClick: () -> Unit = {},
    onAmountClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onSelectAllClick: () -> Unit = {},
    onSparkFeeRetry: () -> Unit = {},
    onSwipeConfirm: (shownSparkSats: ULong, shownFeeSats: ULong?) -> Boolean = { _, _ -> true },
    onCloseConfirm: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val headlineAmount = (quote?.amountSat ?: fallbackAmount).safe() + sparkAmount.safe()
    val sparkReceive = sparkAmount.safe() - (sparkFeeSats ?: 0uL).safe()
    val receiveAmount = (quote?.receiveSat ?: 0uL).safe() + sparkReceive.safe()
    // Only show the swap-pricing spinner while swaps are enabled and a quote is still being fetched;
    // otherwise the swipe just falls back to closing the channel and there is nothing to wait for.
    val isPricingSwap = isSwapEnabled && quote == null && isQuoteLoading
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
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
                    Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent())
                    VerticalSpacer(32.dp)

                    Caption13Up(
                        text = stringResource(R.string.lightning__savings_confirm__label),
                        color = Colors.White64,
                    )
                    VerticalSpacer(16.dp)
                    MoneyDisplay(sats = headlineAmount.toLong(), onClick = onAmountClick)

                    if (quote != null) {
                        VerticalSpacer(24.dp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.height(IntrinsicSize.Min),
                        ) {
                            FeeInfo(
                                label = stringResource(R.string.lightning__savings_confirm__network_fee),
                                amount = quote.networkFeeSat.toLong(),
                            )
                            FeeInfo(
                                label = stringResource(R.string.lightning__savings_confirm__service_fee),
                                amount = quote.swapFeeSat.toLong(),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.height(IntrinsicSize.Min),
                        ) {
                            FeeInfo(
                                label = stringResource(R.string.lightning__savings_confirm__amount),
                                amount = headlineAmount.toLong(),
                            )
                            FeeInfo(
                                label = stringResource(R.string.lightning__savings_confirm__receive),
                                amount = receiveAmount.toLong(),
                            )
                        }

                        // Adjust how much to move to savings, bounded to a payable range.
                        if (maxSat > minSat) {
                            VerticalSpacer(28.dp)
                            AmountSlider(
                                value = quote.amountSat.toLong(),
                                min = minSat.toLong(),
                                max = maxSat.toLong(),
                                onValueChange = onAmountChange,
                            )
                        }
                    }

                    if (sparkFeeSats != null || isSparkFeePricing) {
                        VerticalSpacer(if (quote != null) 0.dp else 24.dp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.height(IntrinsicSize.Min),
                        ) {
                            FeeInfo(
                                label = stringResource(R.string.lightning__savings_confirm__spark_fee),
                                amount = (sparkFeeSats ?: 0uL).toLong(),
                                isLoading = isSparkFeePricing,
                            )
                        }
                    }

                    if (isSparkUnpriced && !isSparkFeePricing) {
                        VerticalSpacer(16.dp)
                        if (isSparkTooSmall) {
                            BodyM(
                                text = stringResource(R.string.lightning__savings_confirm__amount_too_low),
                                color = Colors.Red,
                            )
                        } else {
                            SparkFeeError(onRetry = onSparkFeeRetry)
                        }
                    }

                    if (hasMultiple) {
                        VerticalSpacer(32.dp)
                        if (hasSelected) {
                            PrimaryButton(
                                text = stringResource(R.string.lightning__savings_confirm__transfer_all),
                                size = ButtonSize.Small,
                                fullWidth = false,
                                onClick = { onSelectAllClick() },
                            )
                        } else {
                            PrimaryButton(
                                text = stringResource(R.string.common__advanced),
                                size = ButtonSize.Small,
                                fullWidth = false,
                                onClick = { onAdvancedClick() },
                            )
                        }
                    }

                    // The piggybank keeps its size: when the fees, slider and Spark row make the content tall,
                    // it scrolls above the swipe instead of shrinking.
                    FillHeight()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) {
                        val piggySize = screenWidth() * PIGGY_SCREEN_SHARE
                        if (isPricingSwap) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(piggySize)) {
                                GradientCircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 3.dp,
                                )
                            }
                        } else {
                            Image(
                                painter = painterResource(R.drawable.piggybank),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(piggySize)
                                    .graphicsLayer(scaleX = -1f)
                                    .testTag("SavingsConfirmPiggybank"),
                            )
                        }
                    }
                    FillHeight()
                }
            }

            // The swipe always commits the transfer: it swaps when a quote is ready and otherwise
            // falls back to the pre-swap behaviour of closing the channel, so it is never inert.
            var isLoading by remember { mutableStateOf(false) }
            // Read at swipe time, as the swipe's gesture keeps the lambda from the composition it started in.
            val shownSpark by rememberUpdatedState(sparkAmount to sparkFeeSats)
            val commitSwipe by rememberUpdatedState(onSwipeConfirm)
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = isLoading || isPricingSwap || isSparkFeePricing,
                enabled = !isSparkUnpriced && !isNothingSelected,
                confirmed = isLoading,
                color = Colors.Brand,
                onConfirm = {
                    val (shownSparkSats, shownFeeSats) = shownSpark
                    scope.launch {
                        isLoading = true
                        delay(300)
                        // Spark changed since the swipe began: reset the swipe so the new fee is approved first.
                        if (!commitSwipe(shownSparkSats, shownFeeSats)) isLoading = false
                    }
                }
            )
            if (quote != null) {
                VerticalSpacer(12.dp)
                // Fallback: drain a whole channel on-chain by closing it instead of swapping.
                TertiaryButton(
                    text = stringResource(R.string.lightning__savings_confirm__close_instead),
                    enabled = !isSparkUnpriced && !isNothingSelected,
                    onClick = onCloseConfirm,
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

/** Spark's withdrawal fee could not be priced; the swipe waits for it, so it can be retried here. */
@Composable
private fun SparkFeeError(onRetry: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BodyM(
            text = stringResource(R.string.lightning__spending_confirm__error_quote),
            color = Colors.Red,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = stringResource(R.string.common__retry),
            size = ButtonSize.Small,
            fullWidth = false,
            onClick = onRetry,
            modifier = Modifier.testTag("SavingsSparkFeeRetry"),
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsConfirmScreenPreview() {
    AppThemeSurface {
        SavingsConfirmContent(
            fallbackAmount = 50_123u,
            sparkAmount = 12_000u,
            sparkFeeSats = 1_470u,
            isSparkFeePricing = false,
            isSparkUnpriced = false,
            isSparkTooSmall = false,
            isSwapEnabled = true,
            quote = SavingsSwapQuote(
                amountSat = 50_123u,
                networkFeeSat = 320u,
                swapFeeSat = 125u,
                receiveSat = 49_678u,
            ),
            isQuoteLoading = false,
            minSat = 25_000u,
            maxSat = 72_000u,
            onAmountChange = {},
            hasMultiple = true,
            hasSelected = false,
        )
    }
}
