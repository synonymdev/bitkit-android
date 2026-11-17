package to.bitkit.ui.screens.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AmountInputEffect
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

/** Number pad row height when the screen has room, as iOS and Figma draw it; rows shrink to fit below that. */
private val PAD_ROW_HEIGHT = 78.dp

/** Shortest number pad row, reached only when four 50 dp rows don't fit, as on a 640 dp tall phone. */
private val PAD_ROW_MIN_HEIGHT = 40.dp

/** Gap between the amount and the Available row; it gives way only once the pad rows are at their shortest. */
private val AVAILABLE_ROW_GAP = 32.dp

/** Number pad rows; the layout reserves room for at least this many rows of [PAD_ROW_MIN_HEIGHT]. */
private const val PAD_ROWS = 4

@Suppress("ViewModelForwarding")
@Composable
fun SpendingAmountScreen(
    viewModel: SparkFundingViewModel,
    isOffline: Boolean,
    onBackClick: () -> Unit = {},
    onQuoteReady: () -> Unit = {},
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    val currentMaxSats by rememberUpdatedState(uiState.maxSats)
    val currentCurrencies by rememberUpdatedState(currencies)

    LaunchedEffect(isOffline) {
        viewModel.refreshMax()
    }

    LaunchedEffect(amountUiState.sats) {
        viewModel.onAmountChange(amountUiState.sats)
    }

    // A pricing that lands after the screen starts leaving must not open confirm over the screen behind it.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onAmountLeave() }

    LaunchedEffect(viewModel, onQuoteReady) {
        viewModel.effects.collect {
            if (it == SideEffect.AmountReady) onQuoteReady()
        }
    }

    LaunchedEffect(Unit) {
        amountInputViewModel.effect.collect {
            when (it) {
                AmountInputEffect.MaxExceeded -> {
                    amountInputViewModel.setSats(currentMaxSats, currentCurrencies)
                    viewModel.onMaxExceeded()
                }
            }
        }
    }

    Box {
        Content(
            uiState = uiState,
            amountInputViewModel = amountInputViewModel,
            currencies = currencies,
            onBackClick = onBackClick,
            onClickQuarter = { amountInputViewModel.setSats(viewModel.quarterSats(), currencies) },
            onClickMaxAmount = { amountInputViewModel.setSats(uiState.maxSats, currencies) },
            onConfirmAmount = viewModel::onAmountContinue,
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

@Suppress("ViewModelForwarding")
@Composable
internal fun Content(
    uiState: UiState,
    amountInputViewModel: AmountInputViewModel,
    onBackClick: () -> Unit,
    onClickQuarter: () -> Unit,
    onClickMaxAmount: () -> Unit,
    onConfirmAmount: () -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        if (uiState.isNodeRunning) {
            SpendingAmountNodeRunning(
                uiState = uiState,
                amountInputViewModel = amountInputViewModel,
                currencies = currencies,
                onClickQuarter = onClickQuarter,
                onClickMaxAmount = onClickMaxAmount,
                onConfirmAmount = onConfirmAmount,
            )
        } else {
            SyncNodeView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Suppress("ViewModelForwarding")
@Composable
private fun SpendingAmountNodeRunning(
    uiState: UiState,
    amountInputViewModel: AmountInputViewModel,
    currencies: CurrencyState,
    onClickQuarter: () -> Unit,
    onClickMaxAmount: () -> Unit,
    onConfirmAmount: () -> Unit,
) {
    LaunchedEffect(uiState.maxSats, uiState.isMaxLoading) {
        if (!uiState.isMaxLoading) amountInputViewModel.setMaxAmount(uiState.maxSats)
    }

    // Review's fee grid switches the unit while this screen waits in the back stack, so redraw the amount in it.
    LaunchedEffect(currencies.primaryDisplay) {
        val sats = amountInputViewModel.uiState.value.sats
        if (sats > 0) amountInputViewModel.setSats(sats, currencies)
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxSize()
            .testTag(if (uiState.hwWalletId != null) "HardwareTransferAmount" else "SpendingAmount")
    ) {
        val amountUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
        val isHardware = uiState.hwWalletId != null
        val tag = if (isHardware) "HardwareTransferAmount" else "SpendingAmount"
        val isReady = !uiState.isLoading && !uiState.isMaxLoading
        val hasFunds = uiState.maxSats > 0

        VerticalSpacer(32.dp)

        Display(
            text = stringResource(R.string.lightning__spending_amount__title)
                .withAccent(accentColor = Colors.Purple),
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(32.dp)

        NumberPadTextField(
            viewModel = amountInputViewModel,
            currencies = currencies,
            showSecondaryField = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${tag}NumberField")
        )

        AvailableRowLayout(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag(
                        if (isHardware) "HardwareTransferAmountNumberPad" else "SendAmountNumberPad"
                    )
                ) {
                    Column {
                        Text13Up(
                            text = stringResource(R.string.wallet__send_available),
                            color = Colors.White64,
                            modifier = Modifier.testTag("${tag}Available")
                        )
                        VerticalSpacer(4.dp)
                        MoneySSB(
                            sats = uiState.maxSats,
                            showSymbol = true,
                            modifier = Modifier.testTag("${tag}Unit")
                        )
                    }
                    FillWidth()
                    UnitButton(
                        color = Colors.Purple,
                        onClick = { amountInputViewModel.switchUnit(currencies) },
                        modifier = Modifier.testTag(
                            if (isHardware) "HardwareTransferAmountUnitButton" else "SpendingNumberPadUnit"
                        )
                    )
                    NumberPadActionButton(
                        text = stringResource(R.string.lightning__spending_amount__quarter),
                        color = Colors.Purple,
                        enabled = isReady && hasFunds,
                        onClick = onClickQuarter,
                        modifier = Modifier.testTag("${tag}Quarter")
                    )
                    NumberPadActionButton(
                        text = stringResource(R.string.common__max),
                        color = Colors.Purple,
                        enabled = isReady && hasFunds,
                        onClick = onClickMaxAmount,
                        modifier = Modifier.testTag("${tag}Max")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }

            NumberPad(
                viewModel = amountInputViewModel,
                currencies = currencies,
                enabled = isReady && hasFunds,
                idealRowHeight = PAD_ROW_HEIGHT,
                minRowHeight = PAD_ROW_MIN_HEIGHT,
                modifier = Modifier.fillMaxWidth()
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onConfirmAmount,
                    enabled = isReady && amountUiState.sats > 0 && amountUiState.sats <= uiState.maxSats,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.testTag("${tag}Continue")
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

/**
 * Lays out the Available row, the number pad and Continue: the row [AVAILABLE_ROW_GAP] under the amount, the pad and
 * Continue pinned to the bottom. The pad rows shrink first; the gap gives way only once they are at their shortest.
 */
@Composable
private fun AvailableRowLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val (rowMeasurable, padMeasurable, footerMeasurable) = measurables
        val loose = constraints.copy(minHeight = 0)
        val row = rowMeasurable.measure(loose)
        val footer = footerMeasurable.measure(loose)
        val gap = AVAILABLE_ROW_GAP.roundToPx()
        val room = (constraints.maxHeight - row.height - footer.height).coerceAtLeast(0)
        val minPad = (PAD_ROW_MIN_HEIGHT * PAD_ROWS).roundToPx().coerceAtMost(room)
        val pad = padMeasurable.measure(loose.copy(maxHeight = (room - gap).coerceAtLeast(minPad)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            row.place(0, gap.coerceAtMost(room - pad.height))
            footer.place(0, constraints.maxHeight - footer.height)
            pad.place(0, constraints.maxHeight - footer.height - pad.height)
        }
    }
}

@Preview(showSystemUi = true)
@Preview(showSystemUi = true, device = "id:pixel_9_pro_xl", name = "Large")
@Preview(showSystemUi = true, device = NEXUS_5, name = "Small")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = UiState(isNodeRunning = true, isMaxLoading = false, maxSats = 158_234),
            amountInputViewModel = previewAmountInputViewModel(),
            currencies = CurrencyState(),
            onBackClick = {},
            onClickQuarter = {},
            onClickMaxAmount = {},
            onConfirmAmount = {},
        )
    }
}

@Preview(showSystemUi = true, name = "No funds")
@Composable
private fun PreviewNoFunds() {
    AppThemeSurface {
        Content(
            uiState = UiState(isNodeRunning = true, isMaxLoading = false, maxSats = 0),
            amountInputViewModel = previewAmountInputViewModel(),
            currencies = CurrencyState(),
            onBackClick = {},
            onClickQuarter = {},
            onClickMaxAmount = {},
            onConfirmAmount = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewInitializing() {
    AppThemeSurface {
        Content(
            uiState = UiState(),
            amountInputViewModel = previewAmountInputViewModel(),
            currencies = CurrencyState(),
            onBackClick = {},
            onClickQuarter = {},
            onClickMaxAmount = {},
            onConfirmAmount = {},
        )
    }
}
