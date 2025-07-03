package to.bitkit.ui.settings.advanced

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import to.bitkit.R
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServerPeer
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToQrScanner
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScanNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.scanner.SCAN_RESULT_KEY
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ElectrumConfigScreen(
    savedStateHandle: SavedStateHandle,
    navController: NavController,
    viewModel: ElectrumConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val app = appViewModel ?: return
    val context = LocalContext.current

    // Handle result from Scanner
    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<String?>(SCAN_RESULT_KEY, null)
            .filterNotNull()
            .collect { scannedData ->
                viewModel.onScan(scannedData)
                savedStateHandle.remove<String>(SCAN_RESULT_KEY)
            }
    }

    // Monitor connection results
    LaunchedEffect(uiState.connectionResult) {
        uiState.connectionResult?.let { result ->
            if (result.isSuccess) {
                app.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.settings__es__server_updated_title),
                    description = context.getString(R.string.settings__es__server_updated_message)
                        .replace("{host}", uiState.host)
                        .replace("{port}", uiState.port),
                )
            } else {
                app.toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.settings__es__server_error),
                    description = context.getString(R.string.settings__es__server_error_description),
                )
            }
            viewModel.clearConnectionResult()
        }
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onScan = { navController.navigateToQrScanner(isCalledForResult = true) },
        onChangeHost = viewModel::setHost,
        onChangePort = viewModel::setPort,
        onChangeProtocol = viewModel::setProtocol,
        onClickReset = viewModel::resetToDefault,
        onClickConnect = viewModel::onClickConnect,
    )
}

@Composable
private fun Content(
    uiState: ElectrumConfigUiState = ElectrumConfigUiState(),
    onBack: () -> Unit = {},
    onScan: () -> Unit = {},
    onChangeHost: (String) -> Unit = {},
    onChangePort: (String) -> Unit = {},
    onChangeProtocol: (ElectrumProtocol) -> Unit = {},
    onClickReset: () -> Unit = {},
    onClickConnect: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__electrum_server),
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
            BodyM(stringResource(R.string.settings__es__connected_to), color = Colors.White64)
            VerticalSpacer(4.dp)

            BodyM(
                text = if (uiState.isConnected && uiState.connectedPeer != null) {
                    "${uiState.connectedPeer.host}:${uiState.connectedPeer.port}"
                } else {
                    stringResource(R.string.settings__es__disconnected)
                },
                color = if (uiState.isConnected) Colors.Green else Colors.Red,
            )

            VerticalSpacer(32.dp)

            // Host Input
            Caption13Up(stringResource(R.string.settings__es__host), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.host,
                onValueChange = onChangeHost,
                placeholder = "127.0.0.1",
                modifier = Modifier.fillMaxWidth()
            )

            VerticalSpacer(16.dp)

            // Port Input
            Caption13Up(stringResource(R.string.settings__es__port), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.port,
                onValueChange = onChangePort,
                placeholder = "50001",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            VerticalSpacer(28.dp)

            // Protocol Selection
            Caption13Up(stringResource(R.string.settings__es__protocol), color = Colors.White64)
            VerticalSpacer(4.dp)
            SettingsButtonRow(
                title = "TCP",
                value = SettingsButtonValue.BooleanValue(uiState.protocol == ElectrumProtocol.TCP),
                enabled = !uiState.isLoading,
                onClick = { onChangeProtocol(ElectrumProtocol.TCP) }
            )
            SettingsButtonRow(
                title = "TLS",
                value = SettingsButtonValue.BooleanValue(uiState.protocol == ElectrumProtocol.SSL),
                enabled = !uiState.isLoading,
                onClick = { onChangeProtocol(ElectrumProtocol.SSL) }
            )

            FillHeight()
            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.settings__es__button_reset),
                    onClick = onClickReset,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )

                PrimaryButton(
                    text = stringResource(R.string.settings__es__button_connect),
                    onClick = onClickConnect,
                    enabled = !uiState.isLoading && uiState.hasEdited || !uiState.isConnected,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
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
        Content()
    }
}

@Suppress("SpellCheckingInspection")
@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = ElectrumConfigUiState(
                isConnected = true,
                connectedPeer = ElectrumServerPeer("testnet.hsmiths.com", "53012", ElectrumProtocol.SSL),
                host = "testnet.hsmiths.com",
                port = "53012",
                protocol = ElectrumProtocol.SSL,
                hasEdited = false,
            ),
        )
    }
}
