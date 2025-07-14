package to.bitkit.ui.screens.transfer.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.models.LnPeer
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.ExternalNodeContract
import to.bitkit.viewmodels.ExternalNodeContract.SideEffect
import to.bitkit.viewmodels.ExternalNodeViewModel

@Composable
fun ExternalConnectionScreen(
    route: Routes.ExternalConnection,
    viewModel: ExternalNodeViewModel,
    onNodeConnected: () -> Unit,
    onScanClick: () -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(route.scannedNodeUri) {
        if (route.scannedNodeUri != null) {
            viewModel.parseNodeUri(route.scannedNodeUri)
        }
    }

    LaunchedEffect(viewModel, onNodeConnected) {
        viewModel.effects.collect {
            when (it) {
                SideEffect.ConnectionSuccess -> onNodeConnected()
                else -> Unit
            }
        }
    }

    ExternalConnectionContent(
        uiState = uiState,
        onContinueClick = { peer -> viewModel.onConnectionContinue(peer) },
        onPasteClick = { viewModel.parseNodeUri(context.getClipboardText().orEmpty()) },
        onScanClick = onScanClick,
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
    )
}

@Composable
private fun ExternalConnectionContent(
    uiState: ExternalNodeContract.UiState,
    onContinueClick: (LnPeer) -> Unit = {},
    onScanClick: () -> Unit = {},
    onPasteClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    var nodeId by remember(uiState.peer) { mutableStateOf(uiState.peer?.nodeId.orEmpty()) }
    var host by remember(uiState.peer) { mutableStateOf(uiState.peer?.host.orEmpty()) }
    var port by remember(uiState.peer) { mutableStateOf(uiState.peer?.port.orEmpty()) }

    val isValid = nodeId.length == 66 && host.isNotBlank() && port.isNotBlank()

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__external__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Display(stringResource(R.string.lightning__external_manual__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(stringResource(R.string.lightning__external_manual__text), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__node_id), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextInput(
                placeholder = "00000000000000000000000000000000000000000000000000000000000000",
                value = nodeId,
                onValueChange = { nodeId = it },
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__host), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextInput(
                placeholder = "00.00.00.00",
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.lightning__external_manual__port), color = Colors.White64)
            Spacer(modifier = Modifier.height(8.dp))
            TextInput(
                placeholder = "9735",
                value = port,
                onValueChange = { port = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.lightning__external_manual__paste),
                size = ButtonSize.Small,
                onClick = onPasteClick,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard_text_simple),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                fullWidth = false,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__external_manual__scan),
                    onClick = onScanClick,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = { onContinueClick(LnPeer(nodeId = nodeId, host = host, port = port)) },
                    enabled = isValid,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ExternalConnectionContent(
            uiState = ExternalNodeContract.UiState(),
        )
    }
}
