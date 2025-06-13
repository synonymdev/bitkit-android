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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.networkUiText
import to.bitkit.ui.Routes
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToNodeState
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

object AdvancedSettingsTestTags {
    const val SCREEN = "advanced_settings_screen"
    const val ADDRESS_TYPE_BUTTON = "address_type_button"
    const val COIN_SELECTION_BUTTON = "coin_selection_button"
    const val PAYMENT_PREFERENCE_BUTTON = "payment_preference_button"
    const val GAP_LIMIT_BUTTON = "gap_limit_button"
    const val LIGHTNING_CONNECTIONS_BUTTON = "lightning_connections_button"
    const val LIGHTNING_NODE_BUTTON = "lightning_node_button"
    const val ELECTRUM_SERVER_BUTTON = "electrum_server_button"
    const val RGS_SERVER_BUTTON = "rgs_server_button"
    const val WEB_RELAY_BUTTON = "web_relay_button"
    const val BITCOIN_NETWORK_BUTTON = "bitcoin_network_button"
    const val ADDRESS_VIEWER_BUTTON = "address_viewer_button"
    const val RESCAN_BUTTON = "rescan_button"
    const val SUGGESTIONS_RESET_BUTTON = "suggestions_reset_button"
    const val RESET_SUGGESTIONS_DIALOG = "reset_suggestions_dialog"
}

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
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
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
            navController.navigate(Routes.LightningConnections)
        },
        onLightningNodeClick = {
            navController.navigateToNodeState()
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
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
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
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag(AdvancedSettingsTestTags.SCREEN)
        ) {
            // Payments Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_payments))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_type),
                value = SettingsButtonValue.StringValue(uiState.addressType.addressTypeInfo().shortName),
                onClick = onAddressTypeClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.ADDRESS_TYPE_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__coin_selection),
                onClick = onCoinSelectionClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.COIN_SELECTION_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__payment_preference),
                onClick = onPaymentPreferenceClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.PAYMENT_PREFERENCE_BUTTON),
            )

            if (uiState.isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__gap_limit),
                    onClick = onGapLimitClick,
                    modifier = Modifier.testTag(AdvancedSettingsTestTags.GAP_LIMIT_BUTTON),
                )
            }

            // Networks Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_networks))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__lightning_connections),
                onClick = onLightningConnectionsClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.LIGHTNING_CONNECTIONS_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__lightning_node),
                onClick = onLightningNodeClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.LIGHTNING_NODE_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__electrum_server),
                onClick = onElectrumServerClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.ELECTRUM_SERVER_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__rgs_server),
                onClick = onRgsServerClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.RGS_SERVER_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__web_relay),
                onClick = onWebRelayClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.WEB_RELAY_BUTTON),
            )

            if (uiState.isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__bitcoin_network),
                    value = SettingsButtonValue.StringValue(uiState.currentNetwork.networkUiText()),
                    onClick = onBitcoinNetworkClick,
                    modifier = Modifier.testTag(AdvancedSettingsTestTags.BITCOIN_NETWORK_BUTTON),
                )
            }

            // Other Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_other))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_viewer),
                onClick = onAddressViewerClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.ADDRESS_VIEWER_BUTTON),
            )

            SettingsTextButtonRow(
                title = stringResource(R.string.settings__adv__rescan),
                value = if (uiState.isRescanning) "Rescanning..." else "", // TODO add missing localized text
                enabled = !uiState.isRescanning,
                onClick = onRescanClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.RESCAN_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__suggestions_reset),
                onClick = onSuggestionsResetClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.SUGGESTIONS_RESET_BUTTON),
            )
        }

        if (showResetSuggestionsDialog) {
            AppAlertDialog(
                title = stringResource(R.string.settings__adv__reset_title),
                text = stringResource(R.string.settings__adv__reset_desc),
                confirmText = stringResource(R.string.settings__adv__reset_confirm),
                onConfirm = onResetSuggestionsDialogConfirm,
                onDismiss = onResetSuggestionsDialogCancel,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.RESET_SUGGESTIONS_DIALOG),
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
