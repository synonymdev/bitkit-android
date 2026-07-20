package to.bitkit.ui.screens.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.filterOpen
import to.bitkit.ui.components.AmountSlider
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.SavingsSwapQuote
import to.bitkit.viewmodels.SavingsTransferMode

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

    val hasMultiple = openChannels.size > 1

    val selectedChannelIds by transfer.selectedChannelIdsState.collectAsStateWithLifecycle()
    val selectedChannels: List<ChannelDetails>? = selectedChannelIds
        .takeIf { it.isNotEmpty() }
        ?.let { list -> openChannels.filter { channel -> list.contains(channel.channelId) } }

    val hasSelected = selectedChannelIds.isNotEmpty()

    val channels = selectedChannels ?: openChannels

    val amount = channels.sumOf { it.amountOnClose }

    val swapState by transfer.savingsSwapState.collectAsStateWithLifecycle()

    // Pull the latest node balances so a just-received payment is reflected in the amounts below.
    LaunchedEffect(Unit) {
        wallet.refreshBalances()
    }

    // Present the swap fee before the user commits. Recomputed when the amount changes.
    LaunchedEffect(amount) {
        if (amount > 0uL) transfer.loadSavingsSwapQuote(amount)
    }

    Box {
        SavingsConfirmContent(
            fallbackAmount = amount,
            quote = swapState.quote,
            isQuoteLoading = swapState.isLoading,
            quoteError = swapState.error,
            amountTooLow = swapState.amountTooLow,
            minSat = swapState.minSat,
            maxSat = swapState.maxSat,
            onAmountChange = { transfer.onSwapAmountChange(it.toULong()) },
            hasMultiple = hasMultiple,
            hasSelected = hasSelected,
            onBackClick = onBackClick,
            onAmountClick = { currency.switchUnit() },
            onAdvancedClick = onAdvancedClick,
            onSelectAllClick = { transfer.setSelectedChannelIds(emptySet()) },
            onSwapConfirm = {
                transfer.setSavingsTransferMode(SavingsTransferMode.SWAP)
                transfer.onTransferToSavingsConfirm(channels)
                onConfirm()
            },
            onCloseConfirm = {
                transfer.setSavingsTransferMode(SavingsTransferMode.CLOSE)
                transfer.onTransferToSavingsConfirm(channels)
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

@Suppress("MagicNumber", "LongParameterList")
@Composable
private fun SavingsConfirmContent(
    fallbackAmount: ULong,
    quote: SavingsSwapQuote?,
    isQuoteLoading: Boolean,
    quoteError: String?,
    amountTooLow: Boolean,
    minSat: ULong,
    maxSat: ULong,
    onAmountChange: (Long) -> Unit,
    hasMultiple: Boolean,
    hasSelected: Boolean,
    onBackClick: () -> Unit = {},
    onAmountClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onSelectAllClick: () -> Unit = {},
    onSwapConfirm: () -> Unit = {},
    onCloseConfirm: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val headlineAmount = quote?.amountSat ?: fallbackAmount
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
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__transfer__confirm).withAccent())
            Spacer(modifier = Modifier.height(32.dp))

            Caption13Up(text = stringResource(R.string.lightning__savings_confirm__label), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            MoneyDisplay(sats = headlineAmount.toLong(), onClick = onAmountClick)

            if (quote != null) {
                Spacer(modifier = Modifier.height(24.dp))
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
                        amount = quote.amountSat.toLong(),
                    )
                    FeeInfo(
                        label = stringResource(R.string.lightning__savings_confirm__receive),
                        amount = quote.receiveSat.toLong(),
                    )
                }

                // Adjust how much to move to savings, bounded to a payable range.
                if (maxSat > minSat) {
                    Spacer(modifier = Modifier.height(28.dp))
                    AmountSlider(
                        value = quote.amountSat.toLong(),
                        min = minSat.toLong(),
                        max = maxSat.toLong(),
                        onValueChange = onAmountChange,
                    )
                }
            } else if (quoteError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                BodyM(text = quoteError, color = Colors.White64)
            }

            if (hasMultiple) {
                Spacer(modifier = Modifier.height(24.dp))
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

            // Flexible middle: the piggybank fills the remaining space, so it shrinks when the
            // fees/slider are shown (no squished buttons) and fills the gap while the quote loads.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (quote == null && isQuoteLoading) {
                    GradientCircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.piggybank),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = -1f),
                    )
                }
            }

            // Swapping funds out is the default; it only fires once the fee quote is ready.
            // Below the swap minimum we revert to the pre-swap behaviour: the swipe closes the
            // channel and the extra "close instead" action is hidden.
            var isLoading by remember { mutableStateOf(false) }
            SwipeToConfirm(
                text = stringResource(R.string.lightning__transfer__swipe),
                loading = isLoading || (quote == null && isQuoteLoading),
                color = Colors.Brand,
                onConfirm = {
                    if (!amountTooLow && quote == null) return@SwipeToConfirm
                    scope.launch {
                        isLoading = true
                        delay(300)
                        if (amountTooLow) onCloseConfirm() else onSwapConfirm()
                    }
                }
            )
            if (!amountTooLow) {
                Spacer(modifier = Modifier.height(12.dp))
                // Fallback: drain a whole channel on-chain by closing it instead of swapping.
                TertiaryButton(
                    text = stringResource(R.string.lightning__savings_confirm__close_instead),
                    onClick = onCloseConfirm,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsConfirmScreenPreview() {
    AppThemeSurface {
        SavingsConfirmContent(
            fallbackAmount = 50_123u,
            quote = SavingsSwapQuote(
                amountSat = 50_123u,
                networkFeeSat = 320u,
                swapFeeSat = 125u,
                receiveSat = 49_678u,
            ),
            isQuoteLoading = false,
            quoteError = null,
            amountTooLow = false,
            minSat = 25_000u,
            maxSat = 72_000u,
            onAmountChange = {},
            hasMultiple = true,
            hasSelected = false,
        )
    }
}
