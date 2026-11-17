package to.bitkit.ui.screens.transfer.hardware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.HwWalletVendor
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.illustrationRes
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.screens.transfer.SparkFundingViewModel
import to.bitkit.ui.screens.transfer.SpendingFeeGrid
import to.bitkit.ui.screens.transfer.SpendingSpeedRows
import to.bitkit.ui.screens.wallets.send.HwSendRequest
import to.bitkit.ui.screens.wallets.send.HwSendViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SpendingHwSignScreen(
    walletId: String,
    viewModel: SparkFundingViewModel,
    onBackClick: () -> Unit,
    onSent: () -> Unit,
    hwSendViewModel: HwSendViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hwState by hwSendViewModel.uiState.collectAsStateWithLifecycle()
    val wallets by hwSendViewModel.wallets.collectAsStateWithLifecycle()
    val vendor = remember(wallets, walletId) {
        wallets.firstOrNull { it.id == walletId }?.vendor ?: HwWalletVendor.TREZOR
    }
    var request by remember { mutableStateOf<HwSendRequest?>(null) }
    val isBusy = state.isSending || hwState.isSigning
    val currency = currencyViewModel

    BackHandler(enabled = !hwState.canLeave || state.isSending) {}

    LaunchedEffect(walletId) {
        hwSendViewModel.warmUp(walletId)
        viewModel.onConfirmAppear()
    }

    LaunchedEffect(viewModel, hwSendViewModel) {
        viewModel.effects.collect {
            when (it) {
                is SideEffect.SignHw -> {
                    request = it.request
                    hwSendViewModel.signAndBroadcast(it.request)
                }

                SideEffect.Sent -> onSent()
                SideEffect.AmountReady -> Unit
                is SideEffect.FundingPending -> Unit
            }
        }
    }

    LaunchedEffect(hwSendViewModel) {
        hwSendViewModel.results.collect {
            viewModel.onHwBroadcast(it.txId, it.amountSats)
            hwSendViewModel.completeBroadcast()
        }
    }

    DisposableEffect(hwSendViewModel) {
        onDispose { hwSendViewModel.cancel() }
    }

    Content(
        state = state,
        vendor = vendor,
        isSigning = isBusy,
        hasPendingBroadcast = hwState.hasPendingBroadcast,
        onBackClick = { if (hwState.canLeave && !state.isSending) onBackClick() },
        onSpeedChange = { if (!isBusy) viewModel.onSpeedChange(it) },
        onFeesClick = { currency?.switchUnit() },
        onOpenConnect = {
            // A pending broadcast resumes only for the exact request it was signed for.
            val pending = request?.takeIf { hwState.hasPendingBroadcast }
            if (pending != null) hwSendViewModel.signAndBroadcast(pending) else viewModel.onConfirm()
        },
    )

    if (hwState.isPassphraseRequired) {
        HwPassphrasePromptSheet(
            isVerifying = hwState.isVerifyingPassphrase,
            onSubmit = { passphrase ->
                hwSendViewModel.submitPassphrase(
                    walletId = walletId,
                    passphrase = passphrase,
                    onReady = { request?.let { hwSendViewModel.signAndBroadcast(it) } },
                )
            },
            onDismiss = hwSendViewModel::dismissPassphrase,
        )
    }
}

@Composable
private fun Content(
    state: UiState,
    vendor: HwWalletVendor = HwWalletVendor.TREZOR,
    isSigning: Boolean = false,
    hasPendingBroadcast: Boolean = false,
    onBackClick: () -> Unit = {},
    onSpeedChange: (SparkClaimSpeed) -> Unit = {},
    onFeesClick: () -> Unit = {},
    onOpenConnect: () -> Unit = {},
) {
    val quote = state.quote
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { if (!isSigning) DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("HardwareTransferSign")
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
                        text = stringResource(
                            if (hasPendingBroadcast) {
                                R.string.lightning__transfer_hw__signed_title
                            } else {
                                R.string.lightning__transfer_hw__sign_title
                            }
                        ).withAccent(accentColor = Colors.Purple),
                        modifier = Modifier.fillMaxWidth()
                    )
                    VerticalSpacer(8.dp)

                    SpendingFeeGrid(
                        quote = quote,
                        speed = state.speed,
                        address = quote?.address,
                        onClick = onFeesClick,
                    )

                    VerticalSpacer(24.dp)

                    SpendingSpeedRows(quote = quote, speed = state.speed, onSpeedChange = onSpeedChange)

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = vendor.illustrationRes()),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            val canSign = quote != null && quote.toSpendingSats(state.speed) > 0 && !state.isQuoting
            PrimaryButton(
                text = stringResource(
                    if (hasPendingBroadcast) R.string.common__retry else R.string.lightning__transfer_hw__open_connect
                ),
                onClick = onOpenConnect,
                enabled = canSign && !isSigning,
                isLoading = isSigning || state.isQuoting,
                modifier = Modifier.testTag("HardwareTransferOpenTrezorConnect")
            )
            VerticalSpacer(16.dp)
        }
    }
}

private val previewQuote = Quote(
    address = "bcrt1q5pz8luhp0c7khjr27m2ct55qyr5s985fey9xh3c4kq0p7rnvzd3s2fz2tq",
    amountSats = 50_000,
    networkFeeSats = 1_141,
    instantFeeSats = 1_350,
    laterFeeSats = 198,
    spendableSats = 120_000,
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(state = UiState(quote = previewQuote, hwWalletId = "trezor"))
    }
}

@Preview(showSystemUi = true, device = NEXUS_5, name = "Small")
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        Content(state = UiState(quote = previewQuote, hwWalletId = "trezor"))
    }
}

@Preview(showSystemUi = true, name = "Signing")
@Composable
private fun PreviewSigning() {
    AppThemeSurface {
        Content(state = UiState(quote = previewQuote, hwWalletId = "trezor"), isSigning = true)
    }
}

@Preview(showSystemUi = true, name = "Pending broadcast")
@Composable
private fun PreviewPendingBroadcast() {
    AppThemeSurface {
        Content(state = UiState(quote = previewQuote, hwWalletId = "trezor"), hasPendingBroadcast = true)
    }
}
