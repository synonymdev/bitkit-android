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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel

enum class SavingsProgressState { PROGRESS, SUCCESS, INTERRUPTED }

@Composable
fun SavingsProgressScreen(
    onContinueClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val window = LocalActivity.current?.window
    val transfer = transferViewModel ?: return
    val wallet = walletViewModel ?: return
    var progressState by remember { mutableStateOf(SavingsProgressState.PROGRESS) }

    // Effect to close channels & update UI
    LaunchedEffect(Unit) {
        val channelsFailedToCoopClose = transfer.closeSelectedChannels()

        if (channelsFailedToCoopClose.isEmpty()) {
            window?.clearFlags(FLAG_KEEP_SCREEN_ON)

            wallet.refreshState()
            delay(5000)
            progressState = SavingsProgressState.SUCCESS
        } else {
            transfer.startCoopCloseRetries(channelsFailedToCoopClose, System.currentTimeMillis())
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

    SavingsProgressScreen(
        progressState = progressState,
        onContinueClick = { onContinueClick() },
        onCloseClick = onCloseClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavingsProgressScreen(
    progressState: SavingsProgressState,
    onContinueClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val inProgress = progressState == SavingsProgressState.PROGRESS

    ScreenColumn {
        CenterAlignedTopAppBar(
            title = {
                Title(
                    text = when (progressState) {
                        SavingsProgressState.PROGRESS -> stringResource(R.string.lightning__transfer__nav_title)
                        SavingsProgressState.SUCCESS -> stringResource(R.string.lightning__transfer_success__nav_title)
                        SavingsProgressState.INTERRUPTED -> stringResource(R.string.lightning__savings_interrupted__nav_title)
                            .removeAccentTags().replace("\n", " ")
                    }
                )
            },
            actions = {
                if (inProgress) {
                    CloseNavIcon(onCloseClick)
                }
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
                        text = stringResource(R.string.lightning__savings_progress__text)
                            .withAccent(accentStyle = SpanStyle(color = Colors.White, fontWeight = FontWeight.Bold)),
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
                        text = stringResource(R.string.lightning__savings_interrupted__text)
                            .withAccent(accentStyle = SpanStyle(color = Colors.White, fontWeight = FontWeight.Bold)),
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
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsProgressScreenProgressPreview() {
    AppThemeSurface {
        SavingsProgressScreen(
            progressState = SavingsProgressState.PROGRESS,
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsProgressScreenSuccessPreview() {
    AppThemeSurface {
        SavingsProgressScreen(
            progressState = SavingsProgressState.SUCCESS,
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SavingsProgressScreenInterruptedPreview() {
    AppThemeSurface {
        SavingsProgressScreen(
            progressState = SavingsProgressState.INTERRUPTED,
        )
    }
}
