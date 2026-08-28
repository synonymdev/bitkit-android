package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HardwareTransferIllustration
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.transfer.hardware.HwPassphrasePromptSheet
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendUiState

private const val SEND_SIGN_VISUAL_TOP_RATIO = 0.54f

@Composable
fun HwSendSignScreen(
    walletId: String,
    sendUiState: SendUiState,
    satsPerVByte: ULong,
    viewModel: HwSendViewModel,
    prepareContactPayment: suspend () -> Boolean,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val request = HwSendRequest(
        walletId = walletId,
        address = sendUiState.address,
        amountSats = sendUiState.amount,
        satsPerVByte = satsPerVByte,
        tags = sendUiState.selectedTags,
    )

    LaunchedEffect(walletId) {
        viewModel.warmUp(walletId)
    }
    DisposableEffect(viewModel) {
        onDispose(viewModel::cancel)
    }

    HwSendSignContent(
        amountSats = sendUiState.amount,
        address = sendUiState.address,
        isSigning = uiState.isSigning,
        hasPendingBroadcast = uiState.hasPendingBroadcast,
        onBack = { if (!uiState.isSigning && !uiState.isBroadcastUnresolved) onBack() },
        onOpenConnect = { viewModel.signAndBroadcast(request, prepareContactPayment) },
    )

    if (uiState.isPassphraseRequired) {
        HwPassphrasePromptSheet(
            isVerifying = uiState.isVerifyingPassphrase,
            onSubmit = { passphrase ->
                viewModel.submitPassphrase(request, passphrase, prepareContactPayment)
            },
            onDismiss = viewModel::dismissPassphrase,
        )
    }
}

@Composable
private fun HwSendSignContent(
    amountSats: ULong,
    address: String,
    isSigning: Boolean,
    hasPendingBroadcast: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenConnect: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        HardwareTransferIllustration(
            drawableRes = R.drawable.trezor,
            topRatio = SEND_SIGN_VISUAL_TOP_RATIO,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            SheetTopBar(
                titleText = stringResource(R.string.hardware__send_sign_title),
                onBack = onBack,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                VerticalSpacer(16.dp)
                BalanceHeaderView(
                    sats = amountSats.toLong(),
                    useSwipeToHide = false,
                    testTag = "HardwareSendAmount",
                    modifier = Modifier.fillMaxWidth()
                )
                VerticalSpacer(40.dp)
                Caption13Up(
                    text = stringResource(R.string.hardware__send_confirm_address),
                    color = Colors.White64,
                )
                VerticalSpacer(8.dp)
                BodySSB(
                    text = address,
                    modifier = Modifier.testTag("HardwareSendAddress")
                )
                VerticalSpacer(24.dp)
                HorizontalDivider()
                FillHeight()
                PrimaryButton(
                    text = stringResource(
                        if (hasPendingBroadcast) R.string.common__retry else R.string.hardware__send_open_connect
                    ),
                    enabled = !isSigning,
                    isLoading = isSigning,
                    onClick = onOpenConnect,
                    modifier = Modifier.testTag("HardwareSendOpenTrezorConnect")
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
            HwSendSignContent(
                amountSats = 100_000u,
                address = "bc1qexampleaddressforconfirmingonthedevice",
                isSigning = false,
                hasPendingBroadcast = false,
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
