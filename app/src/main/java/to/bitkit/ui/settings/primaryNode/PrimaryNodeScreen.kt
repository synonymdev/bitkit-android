package to.bitkit.ui.settings.primaryNode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScanNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PrimaryNodeScreen(
    navController: NavController,
    viewModel: PrimaryNodeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val app = appViewModel ?: return
    val context = LocalContext.current

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onScan = { app.showScannerSheet { viewModel.onScan(it) } },
        onPaste = { context.getClipboardText()?.let(viewModel::setInput) },
        onChangeInput = viewModel::setInput,
        onClickReset = viewModel::resetToDefault,
        onClickConnect = viewModel::connect,
    )
}

@Composable
private fun Content(
    uiState: PrimaryNodeUiState = PrimaryNodeUiState(),
    onBack: () -> Unit = {},
    onScan: () -> Unit = {},
    onPaste: () -> Unit = {},
    onChangeInput: (String) -> Unit = {},
    onClickReset: () -> Unit = {},
    onClickConnect: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.primary_node__title),
            onBackClick = onBack,
            actions = { ScanNavIcon(onScan) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            VerticalSpacer(16.dp)
            BodyM(stringResource(R.string.primary_node__description), color = Colors.White64)

            if (uiState.currentUri.isNotEmpty()) {
                VerticalSpacer(24.dp)
                Caption13Up(stringResource(R.string.primary_node__current), color = Colors.White64)
                VerticalSpacer(8.dp)
                BodyS(uiState.currentUri, modifier = Modifier.testTag("PrimaryNodeCurrent"))
                VerticalSpacer(4.dp)
                BodyM(
                    text = stringResource(
                        if (uiState.isConnected) R.string.primary_node__status_connected else R.string.primary_node__status_offline
                    ),
                    color = if (uiState.isConnected) Colors.Green else Colors.Red,
                    modifier = Modifier.testTag(if (uiState.isConnected) "PrimaryNodeOnline" else "PrimaryNodeOffline")
                )
            }

            if (uiState.isFundingPaused) {
                VerticalSpacer(16.dp)
                BodyM(
                    text = stringResource(R.string.primary_node__funding_paused),
                    color = Colors.White64,
                    modifier = Modifier.testTag("PrimaryNodeFundingPaused")
                )
            }

            VerticalSpacer(32.dp)
            Caption13Up(stringResource(R.string.primary_node__address), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.input,
                onValueChange = onChangeInput,
                placeholder = stringResource(R.string.primary_node__address_placeholder),
                enabled = !uiState.isLoading,
                isError = uiState.error != null,
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("PrimaryNodeInput")
            )
            VerticalSpacer(16.dp)
            SecondaryButton(
                text = stringResource(R.string.wallet__payment_request_paste),
                onClick = onPaste,
                enabled = !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("PrimaryNodePaste")
            )

            uiState.error?.let {
                VerticalSpacer(16.dp)
                BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryNodeError"))
            }

            VerticalSpacer(16.dp)
            BodyS(stringResource(R.string.primary_node__trust), color = Colors.White64)

            FillHeight()
            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.settings__es__button_reset),
                    onClick = onClickReset,
                    enabled = !uiState.isLoading && !uiState.isDefault,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PrimaryNodeReset")
                )
                PrimaryButton(
                    text = stringResource(R.string.primary_node__connect),
                    onClick = onClickConnect,
                    enabled = !uiState.isLoading && uiState.input.isNotBlank(),
                    isLoading = uiState.isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PrimaryNodeConnect")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = PrimaryNodeUiState(
                currentUri = "024a6cc632544189896cca8e06e375bdb1d1f34a71a4c25d09a39cf0ed920a299c@bore.pub:39101",
                input = "024a6cc632544189896cca8e06e375bdb1d1f34a71a4c25d09a39cf0ed920a299c@bore.pub:39101",
                isConnected = true,
                isDefault = true,
            ),
        )
    }
}
