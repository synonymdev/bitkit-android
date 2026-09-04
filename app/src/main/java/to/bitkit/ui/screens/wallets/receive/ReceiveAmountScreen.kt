package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.viewmodels.AmountInputEffect
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel

@Suppress("CyclomaticComplexMethod", "ViewModelForwarding")
@Composable
fun ReceiveAmountScreen(
    onCjitCreated: (CjitEntryDetails) -> Unit,
    onBack: () -> Unit,
    currencies: CurrencyState = LocalCurrencies.current,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val context = LocalContext.current
    val wallet = walletViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val lightningState by wallet.lightningState.collectAsStateWithLifecycle()
    val amountInputUiState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    var isCreatingInvoice by remember { mutableStateOf(false) }
    var maxCjitAmountSats by remember { mutableStateOf<ULong?>(null) }
    val scope = rememberCoroutineScope()

    fun showMaxExceededToast(max: ULong) {
        app.toast(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.wallet__receive_cjit_error_max__title),
            description = context.getString(R.string.wallet__receive_cjit_error_max__description)
                .replace("{amount}", max.formatToModernDisplay()),
            visibilityTime = Toast.VISIBILITY_TIME_SHORT,
            testTag = "ReceiveCjitAmountExceededToast",
        )
    }

    LaunchedEffect(Unit) {
        blocktank.refreshMinCjitSats()
        maxCjitAmountSats = runSuspendCatching { blocktank.maxCjitAmountSats() }.getOrNull()
    }

    LaunchedEffect(maxCjitAmountSats, amountInputUiState.sats) {
        val max = maxCjitAmountSats
        amountInputViewModel.setMaxAmount(maxCjitAmountSats?.toLong() ?: 0L)
        if (max != null && amountInputUiState.sats.toULong() > max) {
            amountInputViewModel.setSats(max.toLong(), currencies)
            showMaxExceededToast(max)
        }
    }

    LaunchedEffect(Unit) {
        amountInputViewModel.effect.collect {
            when (it) {
                AmountInputEffect.MaxExceeded -> {
                    val max = maxCjitAmountSats ?: return@collect
                    amountInputViewModel.setSats(max.toLong(), currencies)
                    showMaxExceededToast(max)
                }
            }
        }
    }

    val minCjitSats by blocktank.minCjitSats.collectAsStateWithLifecycle()

    ReceiveAmountContent(
        amountInputViewModel = amountInputViewModel,
        minCjitSats = minCjitSats,
        currencies = currencies,
        isCreatingInvoice = isCreatingInvoice,
        canContinue = amountInputUiState.sats >= (minCjitSats?.toLong() ?: 0) &&
            (maxCjitAmountSats?.let { amountInputUiState.sats.toULong() <= it } ?: true),
        onBack = onBack,
        onClickMin = { amountInputViewModel.setSats(it, currencies) },
        onContinue = {
            val sats = amountInputUiState.sats
            scope.launch {
                val max = maxCjitAmountSats
                if (max != null && sats.toULong() > max) {
                    amountInputViewModel.setSats(max.toLong(), currencies)
                    showMaxExceededToast(max)
                    return@launch
                }
                isCreatingInvoice = true
                runSuspendCatching {
                    require(lightningState.nodeLifecycleState == NodeLifecycleState.Running) {
                        "Should not be able to land on this screen if the node is not running."
                    }

                    val entry = blocktank.createCjit(amountSats = sats.toULong())
                    onCjitCreated(
                        CjitEntryDetails(
                            networkFeeSat = entry.networkFeeSat.toLong(),
                            serviceFeeSat = entry.serviceFeeSat.toLong(),
                            channelSizeSat = entry.channelSizeSat.toLong(),
                            feeSat = entry.feeSat.toLong(),
                            receiveAmountSats = sats,
                            invoice = entry.invoice.request,
                        )
                    )
                }.onFailure { e ->
                    Logger.error("Failed to create CJIT", e)
                    if (e is ServiceError.ChannelSizeExceedsMaximum) {
                        maxCjitAmountSats = runSuspendCatching { blocktank.maxCjitAmountSats() }.getOrNull()
                        maxCjitAmountSats?.let { showMaxExceededToast(it) }
                    } else {
                        app.toast(e)
                    }
                }
                isCreatingInvoice = false
            }
        }
    )
}

@Suppress("ViewModelForwarding")
@Composable
private fun ReceiveAmountContent(
    amountInputViewModel: AmountInputViewModel,
    minCjitSats: Int?,
    isCreatingInvoice: Boolean,
    canContinue: Boolean,
    modifier: Modifier = Modifier,
    currencies: CurrencyState = LocalCurrencies.current,
    onClickMin: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ReceiveAmount")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__receive_bitcoin),
            onBack = onBack,
        )

        BoxWithConstraints {
            val maxHeight = this.maxHeight

            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                VerticalSpacer(16.dp)
                NumberPadTextField(
                    viewModel = amountInputViewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPadTextField")
                )

                FillHeight(min = 12.dp)

                // Min amount row
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    minCjitSats?.let { minCjitSats ->
                        Column(
                            modifier = Modifier
                                .clickableAlpha { onClickMin(minCjitSats.toLong()) }
                                .testTag("ReceiveAmountMin")
                        ) {
                            Caption13Up(
                                text = stringResource(R.string.wallet__minimum),
                                color = Colors.White64,
                            )
                            VerticalSpacer(8.dp)
                            MoneySSB(sats = minCjitSats.toLong(), showSymbol = true)
                        }
                    } ?: CircularProgressIndicator(modifier = Modifier.size(18.dp))

                    FillWidth()
                    UnitButton(
                        onClick = { amountInputViewModel.switchUnit(currencies) },
                        modifier = Modifier.testTag("ReceiveNumberPadUnit")
                    )
                }

                VerticalSpacer(16.dp)
                HorizontalDivider()

                NumberPad(
                    viewModel = amountInputViewModel,
                    currencies = currencies,
                    availableHeight = maxHeight,
                    modifier = Modifier
                        .testTag("ReceiveNumberPad")
                )

                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    enabled = !isCreatingInvoice && canContinue,
                    isLoading = isCreatingInvoice,
                    onClick = onContinue,
                    modifier = Modifier.testTag("ContinueAmount")
                )

                VerticalSpacer(16.dp)
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveAmountContent(
                amountInputViewModel = previewAmountInputViewModel(),
                canContinue = true,
                minCjitSats = 5000,
                isCreatingInvoice = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmallScreen() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveAmountContent(
                amountInputViewModel = previewAmountInputViewModel(sats = 200),
                canContinue = true,
                minCjitSats = 5000,
                isCreatingInvoice = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
