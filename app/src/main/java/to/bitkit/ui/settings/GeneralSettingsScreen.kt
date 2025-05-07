package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.navigateToDefaultUnitSettings
import to.bitkit.ui.navigateToLocalCurrencySettings
import to.bitkit.ui.navigateToTransactionSpeedSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.utils.displayText
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun GeneralSettingsScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val currencies = LocalCurrencies.current
    val defaultTransactionSpeed = app.defaultTransactionSpeed.collectAsStateWithLifecycle()

    GeneralSettingsContent(
        selectedCurrency = currencies.selectedCurrency,
        primaryDisplay = currencies.primaryDisplay,
        defaultTransactionSpeed = defaultTransactionSpeed.value,
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
        onLocalCurrencyClick = { navController.navigateToLocalCurrencySettings() },
        onDefaultUnitClick = { navController.navigateToDefaultUnitSettings() },
        onTransactionSpeedClick = { navController.navigateToTransactionSpeedSettings() },
    )
}

@Composable
private fun GeneralSettingsContent(
    selectedCurrency: String,
    primaryDisplay: PrimaryDisplay,
    defaultTransactionSpeed: TransactionSpeed,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onDefaultUnitClick: () -> Unit = {},
    onTransactionSpeedClick: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__general_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.local_currency),
                value = selectedCurrency,
                onClick = onLocalCurrencyClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.default_unit),
                value = when (primaryDisplay) {
                    PrimaryDisplay.BITCOIN -> stringResource(R.string.settings__general__unit_bitcoin)
                    PrimaryDisplay.FIAT -> selectedCurrency
                },
                onClick = onDefaultUnitClick,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__speed),
                value = defaultTransactionSpeed.displayText,
                onClick = onTransactionSpeedClick,
            )
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
        )
    }
}
