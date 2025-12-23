package to.bitkit.ui.screens.transfer

import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

enum class SavingsProgressState { PROGRESS, SUCCESS, INTERRUPTED }

@Composable
fun SavingsProgressScreen(
    transfer: TransferViewModel,
    wallet: WalletViewModel,
    onContinueClick: () -> Unit = {},
    onForceTransfer: () -> Unit = {},
) {
    val window = LocalActivity.current?.window
    var progressState by remember { mutableStateOf(SavingsProgressState.PROGRESS) }

    // Effect to close channels & update UI
    // TODO move this logic to viewmodel so it can outlive the screen lifecycle
    LaunchedEffect(Unit) {
        val channelsFailedToCoopClose = transfer.closeSelectedChannels()

        if (channelsFailedToCoopClose.isEmpty()) {
            window?.clearFlags(FLAG_KEEP_SCREEN_ON)

            wallet.refreshState()
            delay(5000)
            progressState = SavingsProgressState.SUCCESS
        } else {
            transfer.startCoopCloseRetries(channelsFailedToCoopClose) {
                onForceTransfer()
            }
            delay(2500)
            progressState = SavingsProgressState.INTERRUPTED
        }
    }

    // Keeps screen on while this view is active
    DisposableEffect(Unit) {
        window?.addFlags(FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        }
    }

    Content(
        progressState = progressState,
        onContinueClick = { onContinueClick() },
    )
}

@Composable
private fun Content(
    progressState: SavingsProgressState,
    onContinueClick: () -> Unit = {},
) {
    val inProgress = progressState == SavingsProgressState.PROGRESS
    ScreenColumn(
        modifier = Modifier.testTag(if (inProgress) "TransferSettingUp" else "TransferSuccess")
    ) {
        AppTopBar(
            titleText = when (progressState) {
                SavingsProgressState.PROGRESS -> stringResource(R.string.lightning__transfer__nav_title)
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
            when (progressState) {
                SavingsProgressState.PROGRESS -> {
                    Display(
                        text = stringResource(R.string.lightning__savings_progress__title).withAccent(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BodyM(
                        text = stringResource(R.string.lightning__savings_progress__text).withAccentBoldBright(),
                        color = Colors.White64,
                    )
                }

                SavingsProgressState.SUCCESS -> {
                    Display(text = stringResource(R.string.lightning__transfer_success__title_savings).withAccent())
                    Spacer(modifier = Modifier.height(8.dp))
                    BodyM(
                        text = stringResource(R.string.lightning__transfer_success__text_savings),
                        color = Colors.White64,
                    )
                }

                SavingsProgressState.INTERRUPTED -> {
                    Display(text = stringResource(R.string.lightning__savings_interrupted__title).withAccent())
                    Spacer(modifier = Modifier.height(8.dp))
                    BodyM(
                        text = stringResource(R.string.lightning__savings_interrupted__text).withAccentBoldBright(),
                        color = Colors.White64,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (progressState == SavingsProgressState.PROGRESS) {
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
