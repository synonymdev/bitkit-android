package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.networkUiText
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun AdvancedSettingsScreen(
    navController: NavController,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetSuggestionsDialog by remember { mutableStateOf(false) }

    Content(
        uiState = uiState,
        showResetSuggestionsDialog = showResetSuggestionsDialog,
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
        onAddressTypeClick = {
            // TODO: Navigate to AddressTypePreference
        },
        onCoinSelectionClick = {
            // TODO: Navigate to CoinSelectPreference
        },
        onPaymentPreferenceClick = {
            // TODO: Navigate to PaymentPreference
        },
        onGapLimitClick = {
            // TODO: Navigate to GapLimit
        },
        onLightningConnectionsClick = {
            // TODO: Navigate to Channels
        },
        onLightningNodeClick = {
            // TODO: Navigate to LightningNodeInfo
        },
        onElectrumServerClick = {
            // TODO: Navigate to ElectrumConfig
        },
        onRgsServerClick = {
            // TODO: Navigate to RGSServer
        },
        onWebRelayClick = {
            // TODO: Navigate to WebRelay
        },
        onBitcoinNetworkClick = {
            // TODO: Navigate to BitcoinNetworkSelection
        },
        onAddressViewerClick = {
            // TODO: Navigate to AddressViewer
        },
        onRescanClick = { viewModel.rescanAddresses() },
        onSuggestionsResetClick = { showResetSuggestionsDialog = true },
        onResetSuggestionsDialogConfirm = {
            viewModel.resetSuggestions()
            showResetSuggestionsDialog = false
            navController.navigateToHome()
        },
        onResetSuggestionsDialogCancel = { showResetSuggestionsDialog = false },
    )
}

@Composable
private fun Content(
    uiState: AdvancedSettingsUiState,
    showResetSuggestionsDialog: Boolean,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onAddressTypeClick: () -> Unit = {},
    onCoinSelectionClick: () -> Unit = {},
    onPaymentPreferenceClick: () -> Unit = {},
    onGapLimitClick: () -> Unit = {},
    onLightningConnectionsClick: () -> Unit = {},
    onLightningNodeClick: () -> Unit = {},
    onElectrumServerClick: () -> Unit = {},
    onRgsServerClick: () -> Unit = {},
    onWebRelayClick: () -> Unit = {},
    onBitcoinNetworkClick: () -> Unit = {},
    onAddressViewerClick: () -> Unit = {},
    onRescanClick: () -> Unit = {},
    onSuggestionsResetClick: () -> Unit = {},
    onResetSuggestionsDialogConfirm: () -> Unit = {},
    onResetSuggestionsDialogCancel: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__advanced_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Payments Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_payments))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_type),
                value = SettingsButtonValue.StringValue(uiState.addressType.addressTypeInfo().shortName),
                onClick = onAddressTypeClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__coin_selection),
                onClick = onCoinSelectionClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__payment_preference),
                onClick = onPaymentPreferenceClick,
            )

            if (uiState.isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__gap_limit),
                    onClick = onGapLimitClick,
                )
            }

            // Networks Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_networks))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__lightning_connections),
                onClick = onLightningConnectionsClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__lightning_node),
                onClick = onLightningNodeClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__electrum_server),
                onClick = onElectrumServerClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__rgs_server),
                onClick = onRgsServerClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__web_relay),
                onClick = onWebRelayClick,
            )

            if (uiState.isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__bitcoin_network),
                    value = SettingsButtonValue.StringValue(uiState.currentNetwork.networkUiText()),
                    onClick = onBitcoinNetworkClick,
                )
            }

            // Other Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_other))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_viewer),
                onClick = onAddressViewerClick,
            )

            SettingsTextButtonRow(
                title = stringResource(R.string.settings__adv__rescan),
                value = if (uiState.isRescanning) "Rescanning..." else "", // TODO add missing localized text
                enabled = !uiState.isRescanning,
                onClick = onRescanClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__suggestions_reset),
                onClick = onSuggestionsResetClick,
            )
        }

        if (showResetSuggestionsDialog) {
            AppAlertDialog(
                title = stringResource(R.string.settings__adv__reset_title),
                text = stringResource(R.string.settings__adv__reset_desc),
                confirmText = stringResource(R.string.settings__adv__reset_confirm),
                onConfirm = onResetSuggestionsDialogConfirm,
                onDismiss = onResetSuggestionsDialogCancel,
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AdvancedSettingsUiState(),
            showResetSuggestionsDialog = false,
        )
    }
}

@Preview
@Composable
private fun PreviewDev() {
    AppThemeSurface {
        Content(
            uiState = AdvancedSettingsUiState(
                isDevModeEnabled = true,
                isRescanning = true,
            ),
            showResetSuggestionsDialog = false,
        )
    }
}

@Preview
@Composable
private fun PreviewDialog() {
    AppThemeSurface {
        Content(
            uiState = AdvancedSettingsUiState(),
            showResetSuggestionsDialog = true,
        )
    }
}
