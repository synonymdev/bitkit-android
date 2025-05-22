package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.settings.SectionFooter
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.CurrencyViewModel

@Composable
fun DefaultUnitSettingsScreen(
    currencyViewModel: CurrencyViewModel,
    navController: NavController,
) {
    val (_, _, _, selectedCurrency, displayUnit, primaryDisplay) = LocalCurrencies.current

    DefaultUnitSettingsScreenContent(
        selectedCurrency = selectedCurrency,
        displayUnit = displayUnit,
        primaryDisplay = primaryDisplay,
        onPrimaryUnitClick = currencyViewModel::setPrimaryDisplayUnit,
        onBitcoinUnitClick = currencyViewModel::setBtcDisplayUnit,
        onBackClick = { navController.popBackStack() },
        onCloseClick = navController::navigateToHome,
    )
}

@Composable
fun DefaultUnitSettingsScreenContent(
    selectedCurrency: String,
    displayUnit: BitcoinDisplayUnit,
    primaryDisplay: PrimaryDisplay,
    onPrimaryUnitClick: (PrimaryDisplay) -> Unit,
    onBitcoinUnitClick: (BitcoinDisplayUnit) -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__general__unit_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(title = stringResource(R.string.settings__general__unit_display))

            SettingsButtonRow(
                title = stringResource(R.string.settings__general__unit_bitcoin),
                iconRes = R.drawable.ic_unit_bitcoin,
                value = SettingsButtonValue.BooleanValue(primaryDisplay == PrimaryDisplay.BITCOIN),
                onClick = { onPrimaryUnitClick(PrimaryDisplay.BITCOIN) },
            )

            SettingsButtonRow(
                title = selectedCurrency,
                iconRes = R.drawable.ic_unit_fiat,
                value = SettingsButtonValue.BooleanValue(primaryDisplay == PrimaryDisplay.FIAT),
                onClick = { onPrimaryUnitClick(PrimaryDisplay.FIAT) },
            )

            SectionFooter(stringResource(R.string.settings__general__unit_note).replace("{currency}", selectedCurrency))

            SectionHeader(title = stringResource(R.string.settings__general__denomination_label))

            BitcoinDisplayUnit.entries.forEach { unit ->
                SettingsButtonRow(
                    title = stringResource(
                        if (unit == BitcoinDisplayUnit.MODERN) R.string.settings__general__denomination_modern
                        else R.string.settings__general__denomination_classic
                    ),
                    value = SettingsButtonValue.BooleanValue(displayUnit == unit),
                    onClick = { onBitcoinUnitClick(unit) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        DefaultUnitSettingsScreenContent(
            selectedCurrency = "USD",
            displayUnit = BitcoinDisplayUnit.MODERN,
            primaryDisplay = PrimaryDisplay.BITCOIN,
            onPrimaryUnitClick = {},
            onBitcoinUnitClick = {},
            onBackClick = {},
            onCloseClick = {},
        )
    }
}
