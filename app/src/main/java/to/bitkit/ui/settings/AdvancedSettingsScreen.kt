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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun AdvancedSettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val isDevModeEnabled by settings.isDevModeEnabled.collectAsStateWithLifecycle()
    var isRescanning by remember { mutableStateOf(false) }

    AdvancedSettingsContent(
        isDevModeEnabled = isDevModeEnabled,
        isRescanning = isRescanning,
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
        onRescanClick = {
            if (!isRescanning) {
                isRescanning = true
                // TODO: Implement rescan functionality
                // After rescan completes, set isRescanning = false
                app.toast(
                    type = Toast.ToastType.INFO,
                    title = "Coming Soon",
                    description = "Rescan functionality coming soon",
                )
                isRescanning = false
            }
        },
        onSuggestionsResetClick = {
            // TODO: Show reset suggestions dialog
            app.toast(
                type = Toast.ToastType.INFO,
                title = "Coming Soon",
                description = "Suggestions reset coming soon",
            )
        },
    )
}

@Composable
private fun AdvancedSettingsContent(
    isDevModeEnabled: Boolean = false,
    isRescanning: Boolean = false,
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
            SectionHeader(title = stringResource(R.string.settings__adv__section_payments))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_type),
                value = SettingsButtonValue.StringValue("P2WPKH"), // TODO: Get from state
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

            if (isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__gap_limit),
                    onClick = onGapLimitClick,
                )
            }

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

            if (isDevModeEnabled) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__adv__bitcoin_network),
                    value = SettingsButtonValue.StringValue("Regtest"), // TODO: Get from state
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
                value = if (isRescanning) "Rescanning..." else "",
                enabled = !isRescanning,
                onClick = onRescanClick,
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__suggestions_reset),
                onClick = onSuggestionsResetClick,
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        AdvancedSettingsContent()
    }
}

@Composable
private fun PreviewDev() {
    AppThemeSurface {
        AdvancedSettingsContent(
            isDevModeEnabled = true,
            isRescanning = true,
        )
    }
}
