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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PrimaryNodeScreen(
    navController: NavController,
    initialUri: String = "",
    viewModel: PrimaryNodeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val app = appViewModel ?: return
    LaunchedEffect(initialUri) {
        if (initialUri.isNotBlank()) viewModel.onScan(initialUri)
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onInputChange = viewModel::setInput,
        onClickScan = { app.showScannerSheet(onResult = viewModel::onScan) },
        onClickPaste = viewModel::paste,
        onClickConnect = viewModel::connect,
    )
}

@Composable
private fun Content(
    uiState: PrimaryNodeUiState = PrimaryNodeUiState(),
    onBack: () -> Unit = {},
    onInputChange: (String) -> Unit = {},
    onClickScan: () -> Unit = {},
    onClickPaste: () -> Unit = {},
    onClickConnect: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(titleText = stringResource(R.string.primary_node__title), onBackClick = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .testTag("PrimaryNodeScreen")
        ) {
            VerticalSpacer(16.dp)
            BodyM(stringResource(R.string.primary_node__description), color = Colors.White64)
            if (uiState.isFundingPaused) {
                VerticalSpacer(16.dp)
                BodyM(
                    stringResource(R.string.primary_node__funding_paused),
                    color = Colors.White64,
                    modifier = Modifier.testTag("PrimaryNodeFundingPaused")
                )
            }
            VerticalSpacer(24.dp)
            if (uiState.configuredUri.isNotEmpty()) {
                Caption13Up(stringResource(R.string.primary_node__current), color = Colors.White64)
                VerticalSpacer(8.dp)
                BodyM(uiState.configuredUri, modifier = Modifier.testTag("PrimaryNodeCurrent"))
                VerticalSpacer(24.dp)
            }
            Caption13Up(stringResource(R.string.primary_node__address), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.input,
                onValueChange = onInputChange,
                enabled = !uiState.isLoading,
                isError = uiState.error != null,
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().testTag("PrimaryNodeInput")
            )
            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.other__camera_scan_qr),
                    onClick = onClickScan,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f).testTag("PrimaryNodeScan")
                )
                SecondaryButton(
                    text = stringResource(R.string.wallet__payment_request_paste),
                    onClick = onClickPaste,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f).testTag("PrimaryNodePaste")
                )
            }
            VerticalSpacer(24.dp)
            BodyM(stringResource(R.string.primary_node__trust), color = Colors.White64)
            uiState.error?.let {
                VerticalSpacer(16.dp)
                BodyM(it, color = Colors.Red, modifier = Modifier.testTag("PrimaryNodeError"))
            }
            if (uiState.isConnected) {
                VerticalSpacer(16.dp)
                BodyM(
                    stringResource(R.string.primary_node__connected),
                    color = Colors.Green,
                    modifier = Modifier.testTag("PrimaryNodeConnected")
                )
            }
            VerticalSpacer(24.dp)
            PrimaryButton(
                text = stringResource(R.string.primary_node__connect),
                onClick = onClickConnect,
                enabled = !uiState.isLoading && uiState.input.isNotBlank(),
                isLoading = uiState.isLoading,
                modifier = Modifier.fillMaxWidth().testTag("PrimaryNodeConnect")
            )
            VerticalSpacer(24.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface { Content() }
}
