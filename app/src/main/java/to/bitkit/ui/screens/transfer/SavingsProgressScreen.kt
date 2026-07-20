package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SavingsSwapResult
import to.bitkit.viewmodels.SavingsTransferMode
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun SavingsProgressScreen(
    app: AppViewModel,
    transfer: TransferViewModel,
    wallet: WalletViewModel,
    onContinueClick: () -> Unit = {},
    onTransferUnavailable: () -> Unit = {},
) {
    val context = LocalContext.current
    var progressState by remember { mutableStateOf(SavingsProgressState.PROGRESS) }
    val swapResult by transfer.savingsSwapResult.collectAsStateWithLifecycle()

    // Effect to execute the transfer & update UI
    LaunchedEffect(Unit) {
        when (transfer.savingsTransferMode.value) {
            // The swap itself is owned by the viewmodel so it survives leaving this screen;
            // the outcome arrives via savingsSwapResult below. Ensure the updates stream is
            // running first so the new swap is tracked and auto-claimed once its lockup appears.
            SavingsTransferMode.SWAP -> {
                wallet.ensureSwapUpdatesRunning()
                transfer.startSavingsSwap()
            }

            SavingsTransferMode.CLOSE -> runChannelClose(
                transfer = transfer,
                wallet = wallet,
                onSuccess = { progressState = SavingsProgressState.SUCCESS },
                onInterrupted = { progressState = SavingsProgressState.INTERRUPTED },
                onGiveUp = { app.showSheet(Sheet.ForceTransfer) },
                onUnavailable = {
                    app.toast(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.lightning__close_error),
                        description = context.getString(R.string.lightning__close_error_msg),
                    )
                    onTransferUnavailable()
                },
            )
        }
    }

    LaunchedEffect(swapResult) {
        when (val result = swapResult) {
            is SavingsSwapResult.Success -> {
                wallet.refreshState()
                progressState = SavingsProgressState.SUCCESS
            }

            // The hold invoice is paid but the on-chain claim has not landed within the wait
            // window. The claim is auto-broadcast once the lockup appears, so the transfer is
            // committed and settling; show that honestly instead of a completed success.
            SavingsSwapResult.Pending -> {
                wallet.refreshState()
                progressState = SavingsProgressState.SETTLING
            }

            is SavingsSwapResult.Failure -> {
                app.toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = result.reason,
                )
                onTransferUnavailable()
            }

            null -> Unit
        }
    }

    Content(
        progressState = progressState,
        onContinueClick = { onContinueClick() },
        modifier = Modifier.keepScreenOn(),
    )
}

/** Legacy path: cooperatively close the selected channel(s), retrying on failure. */
@Suppress("MagicNumber", "LongParameterList")
private suspend fun runChannelClose(
    transfer: TransferViewModel,
    wallet: WalletViewModel,
    onSuccess: () -> Unit,
    onInterrupted: () -> Unit,
    onGiveUp: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val channelsFailedToCoopClose = transfer.closeSelectedChannels()

    if (channelsFailedToCoopClose.isEmpty()) {
        wallet.refreshState()
        delay(5000)
        onSuccess()
        return
    }

    // Check if any channels can be retried (filter out trusted peers)
    val (_, nonTrustedChannels) = transfer.separateTrustedChannels(channelsFailedToCoopClose)

    if (nonTrustedChannels.isEmpty()) {
        // All channels are trusted peers - show error and navigate back immediately
        onUnavailable()
    } else {
        transfer.startCoopCloseRetries(
            channels = nonTrustedChannels,
            onGiveUp = onGiveUp,
            onTransferUnavailable = onUnavailable,
        )
        delay(2500)
        onInterrupted()
    }
}

@Composable
private fun Content(
    progressState: SavingsProgressState,
    onContinueClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val inProgress = progressState == SavingsProgressState.PROGRESS
    val showAnimation = inProgress || progressState == SavingsProgressState.SETTLING
    ScreenColumn(
        modifier = modifier.testTag(
            when (progressState) {
                SavingsProgressState.PROGRESS -> "TransferSettingUp"
                SavingsProgressState.SETTLING -> "TransferSettling"
                else -> "TransferSuccess"
            }
        )
    ) {
        AppTopBar(
            titleText = when (progressState) {
                SavingsProgressState.PROGRESS,
                SavingsProgressState.SETTLING,
                -> stringResource(R.string.lightning__transfer__nav_title)

                SavingsProgressState.SUCCESS -> stringResource(R.string.lightning__transfer_success__nav_title)
                SavingsProgressState.INTERRUPTED -> stringResource(R.string.lightning__savings_interrupted__nav_title)
                    .removeAccentTags().replace("\n", " ")
            },
            onBackClick = null,
            actions = {
                DrawerNavIcon()
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            ProgressMessage(progressState = progressState)
            Spacer(modifier = Modifier.weight(1f))
            if (showAnimation) {
                TransferAnimationView(
                    largeCircleRes = R.drawable.onchain_sync_large,
                    smallCircleRes = R.drawable.onchain_sync_small,
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                ) {
                    Image(
                        painter = painterResource(
                            if (progressState == SavingsProgressState.SUCCESS) {
                                R.drawable.check
                            } else {
                                R.drawable.exclamation_mark
                            }
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(256.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!inProgress) {
                PrimaryButton(
                    text = stringResource(R.string.common__ok),
                    onClick = onContinueClick,
                    modifier = Modifier.testTag("TransferSuccess-button")
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProgressMessage(progressState: SavingsProgressState) {
    val (titleRes, textRes) = when (progressState) {
        SavingsProgressState.PROGRESS ->
            R.string.lightning__savings_progress__title to R.string.lightning__savings_progress__text

        SavingsProgressState.SETTLING ->
            R.string.lightning__savings_settling__title to R.string.lightning__savings_settling__text

        SavingsProgressState.SUCCESS ->
            R.string.lightning__transfer_success__title_savings to R.string.lightning__transfer_success__text_savings

        SavingsProgressState.INTERRUPTED ->
            R.string.lightning__savings_interrupted__title to R.string.lightning__savings_interrupted__text
    }
    Display(text = stringResource(titleRes).withAccent())
    Spacer(modifier = Modifier.height(8.dp))
    BodyM(
        text = stringResource(textRes).withAccentBoldBright(),
        color = Colors.White64,
    )
}

enum class SavingsProgressState { PROGRESS, SETTLING, SUCCESS, INTERRUPTED }

@Preview(showSystemUi = true)
@Composable
private fun PreviewProgress() {
    AppThemeSurface {
        Content(
            progressState = SavingsProgressState.PROGRESS,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSettling() {
    AppThemeSurface {
        Content(
            progressState = SavingsProgressState.SETTLING,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSuccess() {
    AppThemeSurface {
        Content(
            progressState = SavingsProgressState.SUCCESS,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewInterrupted() {
    AppThemeSurface {
        Content(
            progressState = SavingsProgressState.INTERRUPTED,
        )
    }
}
