package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.SheetIntro
import to.bitkit.ui.shared.modifiers.sheetHeight
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
    SheetIntro(
        navTitle = stringResource(R.string.lightning__force_nav_title),
        title = stringResource(R.string.lightning__force_title).withAccent(accentColor = Colors.Yellow),
        description = AnnotatedString(stringResource(R.string.lightning__force_text)),
        image = R.drawable.exclamation_mark,
        continueText = stringResource(R.string.lightning__force_button),
        onContinue = onForceTransfer,
        continueLoading = isLoading,
        cancelText = stringResource(R.string.common__cancel),
        onCancel = onCancel,
        testTag = "ForceTransfer",
        cancelTestTag = "CancelButton",
        continueTestTag = "ForceTransferButton",
        modifier = modifier.sheetHeight()
    )
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
