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
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

object AdvancedSettingsTestTags {
    const val SCREEN = "advanced_settings_screen"
    const val COIN_SELECTION_BUTTON = "coin_selection_button"
    const val LIGHTNING_CONNECTIONS_BUTTON = "lightning_connections_button"
    const val LIGHTNING_NODE_BUTTON = "lightning_node_button"
    const val ELECTRUM_SERVER_BUTTON = "electrum_server_button"
    const val RGS_SERVER_BUTTON = "rgs_server_button"
    const val ADDRESS_VIEWER_BUTTON = "address_viewer_button"
    const val SUGGESTIONS_RESET_BUTTON = "suggestions_reset_button"
    const val RESET_SUGGESTIONS_DIALOG = "reset_suggestions_dialog"
}

@Composable
fun AdvancedSettingsScreen(
    navController: NavController,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
) {
    var showResetSuggestionsDialog by remember { mutableStateOf(false) }

    Content(
        showResetSuggestionsDialog = showResetSuggestionsDialog,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onCoinSelectionClick = {
            navController.navigate(Routes.CoinSelectPreference)
        },
        onLightningConnectionsClick = {
            navController.navigate(Routes.LightningConnections)
        },
        onLightningNodeClick = {
            navController.navigate(Routes.NodeInfo)
        },
        onElectrumServerClick = {
            navController.navigate(Routes.ElectrumConfig)
        },
        onRgsServerClick = {
            navController.navigate(Routes.RgsServer)
        },
        onAddressViewerClick = {
            navController.navigate(Routes.AddressViewer)
        },
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
    showResetSuggestionsDialog: Boolean,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onCoinSelectionClick: () -> Unit = {},
    onLightningConnectionsClick: () -> Unit = {},
    onLightningNodeClick: () -> Unit = {},
    onElectrumServerClick: () -> Unit = {},
    onRgsServerClick: () -> Unit = {},
    onAddressViewerClick: () -> Unit = {},
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
                title = stringResource(R.string.settings__adv__coin_selection),
                onClick = onCoinSelectionClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.COIN_SELECTION_BUTTON),
            )

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

            // Other Section
            SectionHeader(title = stringResource(R.string.settings__adv__section_other))

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__address_viewer),
                onClick = onAddressViewerClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.ADDRESS_VIEWER_BUTTON),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__adv__suggestions_reset),
                onClick = onSuggestionsResetClick,
                modifier = Modifier.testTag(AdvancedSettingsTestTags.SUGGESTIONS_RESET_BUTTON),
            )

            VerticalSpacer(32.dp)
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
            showResetSuggestionsDialog = false,
        )
    }
}

@Preview
@Composable
private fun PreviewDialog() {
    AppThemeSurface {
        Content(
            showResetSuggestionsDialog = true,
        )
    }
}
