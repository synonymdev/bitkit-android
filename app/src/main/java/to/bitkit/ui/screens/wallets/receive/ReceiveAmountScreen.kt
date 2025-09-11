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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.AmountInputHandler
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.Keyboard
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.CurrencyUiState
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ReceiveAmountScreen(
    onCjitCreated: (CjitEntryDetails) -> Unit,
    onBack: () -> Unit,
) {
    val app = appViewModel ?: return
    val wallet = walletViewModel ?: return
    val blocktank = blocktankViewModel ?: return
    val walletState by wallet.uiState.collectAsStateWithLifecycle()
    val currencyVM = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    var input: String by remember { mutableStateOf("0") }
    var overrideSats: Long? by remember { mutableStateOf(null) }
    var satsAmount by remember { mutableLongStateOf(0L) }
    var isCreatingInvoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        blocktank.refreshMinCjitSats()
    }

    AmountInputHandler(
        input = input,
        overrideSats = overrideSats,
        primaryDisplay = currencies.primaryDisplay,
        displayUnit = currencies.displayUnit,
        onInputChanged = { newInput -> input = newInput },
        onAmountCalculated = { sats ->
            satsAmount = sats.toLongOrNull() ?: 0L
            overrideSats = null
        },
        currencyVM = currencyVM,
    )

    val minCjitSats by blocktank.minCjitSats.collectAsStateWithLifecycle()

    ReceiveAmountContent(
        input = input,
        satsAmount = satsAmount,
        minCjitSats = minCjitSats,
        currencyUiState = currencies,
        isCreatingInvoice = isCreatingInvoice,
        onInputChange = { input = it },
        onClickMin = { overrideSats = it },
        onClickAmount = { currencyVM.togglePrimaryDisplay() },
        onBack = onBack,
        onContinue = {
            val sats = satsAmount.toULong()
            scope.launch {
                isCreatingInvoice = true

                if (walletState.nodeLifecycleState == NodeLifecycleState.Starting) {
                    while (walletState.nodeLifecycleState == NodeLifecycleState.Starting && isActive) {
                        delay(5.milliseconds)
                    }
                }

                if (walletState.nodeLifecycleState == NodeLifecycleState.Running) {
                    runCatching {
                        val entry = blocktank.createCjit(amountSats = sats)
                        onCjitCreated(
                            CjitEntryDetails(
                                networkFeeSat = entry.networkFeeSat.toLong(),
                                serviceFeeSat = entry.serviceFeeSat.toLong(),
                                channelSizeSat = entry.channelSizeSat.toLong(),
                                feeSat = entry.feeSat.toLong(),
                                receiveAmountSats = satsAmount,
                                invoice = entry.invoice.request,
                            )
                        )
                    }.onFailure { e ->
                        app.toast(e)
                        Logger.error("Failed to create CJIT", e)
                    }

                    isCreatingInvoice = false
                } else {
                    // TODO add missing localized texts
                    app.toast(
                        type = Toast.ToastType.WARNING,
                        title = "Lightning not ready",
                        description = "Lightning node must be running to create an invoice",
                    )
                    isCreatingInvoice = false
                }
            }
        }
    )
}

@Composable
private fun ReceiveAmountContent(
    input: String,
    satsAmount: Long,
    minCjitSats: Int?,
    currencyUiState: CurrencyUiState,
    isCreatingInvoice: Boolean,
    modifier: Modifier = Modifier,
    onInputChange: (String) -> Unit = {},
    onClickMin: (Long) -> Unit = {},
    onClickAmount: () -> Unit = {},
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
                    input = input,
                    displayUnit = currencyUiState.displayUnit,
                    primaryDisplay = currencyUiState.primaryDisplay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableAlpha(onClick = onClickAmount)
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
                            MoneySSB(sats = minCjitSats.toLong())
                        }
                    } ?: CircularProgressIndicator(modifier = Modifier.size(18.dp))

                    FillWidth()
                    UnitButton(modifier = Modifier.testTag("ReceiveNumberPadUnit"))
                }

                VerticalSpacer(16.dp)
                HorizontalDivider()

                Keyboard(
                    onClick = { number ->
                        onInputChange(if (input == "0") number else input + number)
                    },
                    onClickBackspace = {
                        onInputChange(if (input.length > 1) input.dropLast(1) else "0")
                    },
                    isDecimal = currencyUiState.primaryDisplay == PrimaryDisplay.FIAT,
                    availableHeight = maxHeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReceiveNumberPad")
                )

                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    enabled = !isCreatingInvoice && satsAmount != 0L,
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
                input = "100",
                satsAmount = 10000L,
                minCjitSats = 5000,
                currencyUiState = CurrencyUiState(),
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
                input = "100",
                satsAmount = 10000L,
                minCjitSats = 5000,
                currencyUiState = CurrencyUiState(),
                isCreatingInvoice = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
