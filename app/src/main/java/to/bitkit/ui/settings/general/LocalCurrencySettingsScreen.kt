package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.viewmodels.CurrencyViewModel

@Composable
fun LocalCurrencySettingsScreen(
    currencyViewModel: CurrencyViewModel,
    navController: NavController,
) {
    val (rates, _, _, selectedCurrency) = LocalCurrencies.current
    var searchText by remember { mutableStateOf("") }

    val mostUsedCurrenciesList = remember { listOf("USD", "GBP", "CAD", "CNY", "EUR") }

    val filteredRates by remember(rates, searchText) {
        derivedStateOf {
            rates.filter { rate ->
                searchText.isEmpty() ||
                    rate.quote.contains(searchText, ignoreCase = true) ||
                    rate.quoteName.contains(searchText, ignoreCase = true) ||
                    rate.currencySymbol.contains(searchText, ignoreCase = true)
            }
        }
    }

    val mostUsedRates by remember(filteredRates) {
        derivedStateOf {
            mostUsedCurrenciesList.mapNotNull { currency ->
                filteredRates.find { it.quote == currency }
            }
        }
    }

    val otherCurrencies by remember(filteredRates, mostUsedCurrenciesList) {
        derivedStateOf {
            filteredRates.filter { it.quote !in mostUsedCurrenciesList }
        }
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__general__currency_local_title),
            onBackClick = { navController.popBackStack() },
            actions = { CloseNavIcon(navController::navigateToHome) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
        ) {
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.common__search)) },
                singleLine = true,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.large,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            LazyColumn {
                if (mostUsedRates.isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.settings__general__currency_most_used))
                    }
                    items(mostUsedRates) { rate ->
                        SettingsButtonRow(
                            title = "${rate.quote} (${rate.currencySymbol})",
                            value = SettingsButtonValue.BooleanValue(selectedCurrency == rate.quote),
                            onClick = { currencyViewModel.setSelectedCurrency(rate.quote) }
                        )
                    }
                }

                item {
                    SectionHeader(title = stringResource(R.string.settings__general__currency_other))
                }

                items(otherCurrencies) { rate ->
                    SettingsButtonRow(
                        title = rate.quote,
                        value = SettingsButtonValue.BooleanValue(selectedCurrency == rate.quote),
                        onClick = { currencyViewModel.setSelectedCurrency(rate.quote) }
                    )
                }
            }
        }
    }
}
