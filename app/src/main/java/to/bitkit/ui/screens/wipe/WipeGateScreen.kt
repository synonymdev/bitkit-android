package to.bitkit.ui.screens.wipe

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.shared.util.shareZipFile
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun WipeGateScreen(
    modifier: Modifier = Modifier,
    viewModel: WipeGateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onClickRetry = viewModel::onRetry,
        onConfirmReset = viewModel::onReset,
        onClickExportLogs = { viewModel.zipLogsForSharing { context.shareZipFile(it) } },
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: WipeGateUiState,
    onClickRetry: () -> Unit,
    onConfirmReset: () -> Unit,
    onClickExportLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The launch check is quick, so the gate stays blank rather than flash a message
    if (uiState.mode == WipeGateMode.CHECKING) {
        Box(
            modifier = modifier
                .screen()
                .testTag("WipeGate")
        )
        return
    }

    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .screen()
            .keepScreenOn()
            .padding(horizontal = 32.dp)
            .testTag("WipeGate")
    ) {
        AppTopBar(titleText = null, onBackClick = null)

        Image(
            painter = painterResource(
                if (uiState.mode == WipeGateMode.RUNNING) R.drawable.restore else R.drawable.exclamation_mark
            ),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.0f)
                .weight(1f)
        )

        Display(
            text = stringResource(
                when (uiState.mode) {
                    WipeGateMode.BLOCKED -> R.string.security__wipe_blocked_title
                    WipeGateMode.CHECKING, WipeGateMode.RUNNING, WipeGateMode.FAILED -> R.string.security__wipe_title
                }
            ),
            color = Colors.White,
        )

        BodyM(
            text = stringResource(
                when (uiState.mode) {
                    WipeGateMode.CHECKING, WipeGateMode.RUNNING -> R.string.security__wipe_running_text
                    WipeGateMode.FAILED -> R.string.security__wipe_failed_text
                    WipeGateMode.BLOCKED -> R.string.security__wipe_blocked_text
                }
            ),
            color = Colors.White64,
        )

        uiState.failedStep?.let {
            VerticalSpacer(8.dp)
            Caption(text = it, color = Colors.White32, modifier = Modifier.testTag("WipeGateFailedStep"))
        }

        if (uiState.mode != WipeGateMode.RUNNING) {
            VerticalSpacer(8.dp)
            Caption(
                text = stringResource(R.string.security__wipe_clear_storage_text),
                color = Colors.White64,
                modifier = Modifier.testTag("WipeGateClearStorage")
            )
        }

        VerticalSpacer(32.dp)

        if (uiState.mode == WipeGateMode.RUNNING) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Colors.White32,
                    strokeWidth = 3.dp,
                )
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.common__retry),
                onClick = onClickRetry,
                modifier = Modifier.testTag("WipeGateRetry")
            )
        }

        if (uiState.mode == WipeGateMode.BLOCKED) {
            VerticalSpacer(16.dp)
            SecondaryButton(
                text = stringResource(R.string.security__reset_button_reset),
                onClick = { showResetDialog = true },
                testTag = "WipeGateReset",
            )
        }

        VerticalSpacer(16.dp)

        SecondaryButton(
            text = stringResource(R.string.lightning__export_logs),
            onClick = onClickExportLogs,
            testTag = "WipeGateExportLogs",
        )

        VerticalSpacer(16.dp)
    }

    if (showResetDialog) {
        AppAlertDialog(
            title = stringResource(R.string.security__reset_dialog_title),
            text = stringResource(R.string.security__reset_dialog_desc),
            confirmText = stringResource(R.string.security__reset_confirm),
            onConfirm = {
                showResetDialog = false
                onConfirmReset()
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = WipeGateUiState(), onClickRetry = {}, onConfirmReset = {}, onClickExportLogs = {})
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = WipeGateUiState(mode = WipeGateMode.FAILED, failedStep = "LDK_STORAGE"),
            onClickRetry = {},
            onConfirmReset = {},
            onClickExportLogs = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview3() {
    AppThemeSurface {
        Content(
            uiState = WipeGateUiState(mode = WipeGateMode.BLOCKED),
            onClickRetry = {},
            onConfirmReset = {},
            onClickExportLogs = {},
        )
    }
}
