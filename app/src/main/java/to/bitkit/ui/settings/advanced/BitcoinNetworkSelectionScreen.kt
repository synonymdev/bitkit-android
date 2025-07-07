package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.models.networkUiText
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun BitcoinNetworkSelectionScreen(
    navController: NavController,
    viewModel: BitcoinNetworkSelectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onSelectNetwork = { network ->
            viewModel.selectNetwork(network)
            // navController.popBackStack()
        },
    )
}

@Composable
private fun Content(
    uiState: BitcoinNetworkSelectionUiState = BitcoinNetworkSelectionUiState(),
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onSelectNetwork: (Network) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__bitcoin_network),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            uiState.availableNetworks.forEach { network ->
                SettingsButtonRow(
                    title = "Bitcoin ${network.networkUiText()}",
                    value = SettingsButtonValue.BooleanValue(network == uiState.selectedNetwork),
                    // TODO remove `network != Network.BITCOIN && ` for mainnet
                    enabled = network != Network.BITCOIN && !uiState.isLoading,
                    loading = uiState.isLoading,
                    onClick = { onSelectNetwork(network) },
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = BitcoinNetworkSelectionUiState(
                selectedNetwork = Network.REGTEST,
            ),
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = BitcoinNetworkSelectionUiState(
                selectedNetwork = Network.TESTNET,
                isLoading = true,
            ),
        )
    }
}
