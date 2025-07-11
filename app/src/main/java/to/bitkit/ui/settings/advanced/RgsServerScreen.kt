package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToQrScanner
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScanNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.scanner.SCAN_RESULT_KEY
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun RgsServerScreen(
    savedStateHandle: SavedStateHandle,
    navController: NavController,
    viewModel: RgsServerViewModel = hiltViewModel(),
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
                    title = context.getString(R.string.settings__rgs__update_success_title),
                    description = context.getString(R.string.settings__rgs__update_success_description),
                )
            } else {
                app.toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.wallet__ldk_start_error_title),
                    description = result.exceptionOrNull()?.message ?: "Unknown error",
                )
            }
            viewModel.clearConnectionResult()
        }
    }

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onScan = { navController.navigateToQrScanner(isCalledForResult = true) },
        onChangeUrl = viewModel::setRgsUrl,
        onClickReset = viewModel::resetToDefault,
        onClickConnect = viewModel::onClickConnect,
    )
}

@Composable
private fun Content(
    uiState: RgsServerUiState = RgsServerUiState(),
    onBack: () -> Unit = {},
    onScan: () -> Unit = {},
    onChangeUrl: (String) -> Unit = {},
    onClickReset: () -> Unit = {},
    onClickConnect: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__rgs_server),
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
                text = uiState.connectedRgsUrl ?: "P2P",
                color = if (uiState.connectedRgsUrl != null) Colors.Green else Colors.White,
            )

            VerticalSpacer(32.dp)

            // RGS Server URL Input
            Caption13Up(stringResource(R.string.settings__rgs__server_url), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.rgsUrl,
                onValueChange = onChangeUrl,
                modifier = Modifier.fillMaxWidth()
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
                    enabled = !uiState.isLoading && uiState.canReset,
                    modifier = Modifier.weight(1f)
                )

                PrimaryButton(
                    text = stringResource(R.string.settings__rgs__button_connect),
                    onClick = onClickConnect,
                    enabled = !uiState.isLoading && uiState.canConnect,
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

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = RgsServerUiState(
                connectedRgsUrl = "https://rgs.blocktank.to/snapshot/",
                rgsUrl = "https://rgs.blocktank.to/snapshot/",
            ),
        )
    }
}
