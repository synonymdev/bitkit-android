package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToDefaultUnitSettings
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToLocalCurrencySettings
import to.bitkit.ui.navigateToQuickPaySettings
import to.bitkit.ui.navigateToTagsSettings
import to.bitkit.ui.navigateToTransactionSpeedSettings
import to.bitkit.ui.navigateToWidgetsSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.displayText

@Composable
fun GeneralSettingsScreen(
    navController: NavController,
) {
    val settings = settingsViewModel ?: return
    val currencies = LocalCurrencies.current
    val defaultTransactionSpeed by settings.defaultTransactionSpeed.collectAsStateWithLifecycle()
    val lastUsedTags by settings.lastUsedTags.collectAsStateWithLifecycle()

    GeneralSettingsContent(
        selectedCurrency = currencies.selectedCurrency,
        primaryDisplay = currencies.primaryDisplay,
        defaultTransactionSpeed = defaultTransactionSpeed,
        showTagsButton = lastUsedTags.isNotEmpty(),
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
        onLocalCurrencyClick = { navController.navigateToLocalCurrencySettings() },
        onDefaultUnitClick = { navController.navigateToDefaultUnitSettings() },
        onTransactionSpeedClick = { navController.navigateToTransactionSpeedSettings() },
        onWidgetsClick = { navController.navigateToWidgetsSettings() },
        onQuickPayClick = { navController.navigateToQuickPaySettings() },
        onTagsClick = { navController.navigateToTagsSettings() },
    )
}

@Composable
private fun GeneralSettingsContent(
    selectedCurrency: String,
    primaryDisplay: PrimaryDisplay,
    defaultTransactionSpeed: TransactionSpeed,
    showTagsButton: Boolean = false,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onDefaultUnitClick: () -> Unit = {},
    onTransactionSpeedClick: () -> Unit = {},
    onWidgetsClick: () -> Unit = {},
    onQuickPayClick: () -> Unit = {},
    onTagsClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__general_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__currency_local),
                value = SettingsButtonValue.StringValue(selectedCurrency),
                onClick = onLocalCurrencyClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__unit),
                value = SettingsButtonValue.StringValue(
                    when (primaryDisplay) {
                        PrimaryDisplay.BITCOIN -> stringResource(R.string.settings__general__unit_bitcoin)
                        PrimaryDisplay.FIAT -> selectedCurrency
                    }
                ),
                onClick = onDefaultUnitClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__speed),
                value = SettingsButtonValue.StringValue(defaultTransactionSpeed.displayText),
                onClick = onTransactionSpeedClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__widgets__nav_title),
                onClick = onWidgetsClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__quickpay__nav_title),
                onClick = onQuickPayClick,
            )
            if (showTagsButton) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__general__tags),
                    onClick = onTagsClick,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        GeneralSettingsContent(
            selectedCurrency = "USD",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            defaultTransactionSpeed = TransactionSpeed.Medium,
            showTagsButton = true,
        )
    }
}
