package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun ForceTransferSheet(
    appViewModel: AppViewModel,
    transferViewModel: TransferViewModel,
) {
    val isLoading by transferViewModel.isForceTransferLoading.collectAsStateWithLifecycle()
    val connectivityState by appViewModel.isOnline.collectAsStateWithLifecycle()
    val isOffline = connectivityState != ConnectivityState.CONNECTED

    Box {
        Content(
            isLoading = isLoading,
            onForceTransfer = {
                transferViewModel.forceTransfer {
                    appViewModel.hideSheet()
                }
            },
            onCancel = { appViewModel.hideSheet() },
        )

        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(titleText = stringResource(R.string.lightning__transfer__nav_title))
        }
    }
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onForceTransfer: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .sheetHeight()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("ForceTransfer")
    ) {
        SheetTopBar(titleText = stringResource(R.string.lightning__force_nav_title))

        Image(
            painter = painterResource(R.drawable.exclamation_mark),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .aspectRatio(1.0f)
                .weight(1f)
        )

        Display(text = stringResource(R.string.lightning__force_title).withAccent(accentColor = Colors.Yellow))

        VerticalSpacer(8.dp)

        BodyM(
            text = stringResource(R.string.lightning__force_text),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(32.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("CancelButton")
            )
            PrimaryButton(
                text = stringResource(R.string.lightning__force_button),
                onClick = onForceTransfer,
                isLoading = isLoading,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ForceTransferButton")
            )
        }

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(isLoading = true)
        }
    }
}
