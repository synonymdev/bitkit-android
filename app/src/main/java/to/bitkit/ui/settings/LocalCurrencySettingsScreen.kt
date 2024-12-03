package to.bitkit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.FxRate
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.viewmodels.CurrencyViewModel

@Composable
fun LocalCurrencySettingsScreen(
    currencyViewModel: CurrencyViewModel,
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.local_currency), onBackClick = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
        ) {
            val (rates, _, _, selectedCurrency) = LocalCurrencies.current
            var searchText by remember { mutableStateOf("") }

            val filteredRates = rates.filter { rate ->
                searchText.isEmpty() || rate.quote.contains(searchText, ignoreCase = true) ||
                    rate.quoteName.contains(searchText, ignoreCase = true) ||
                    rate.currencySymbol.contains(searchText, ignoreCase = true)
            }

            val mostUsedCurrencies = setOf("USD", "GBP", "CAD", "CNY", "EUR")
            val mostUsedRates = filteredRates.filter { it.quote in mostUsedCurrencies }
            val otherCurrencies = filteredRates.filter { it.quote !in mostUsedCurrencies }

            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search currencies") },
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
                    item { LabelText(text = "MOST USED", modifier = Modifier.padding(vertical = 8.dp)) }
                    items(mostUsedRates) { rate ->
                        CurrencyRow(
                            rate = rate,
                            isSelected = selectedCurrency == rate.quote,
                        ) {
                            currencyViewModel.setSelectedCurrency(rate.quote)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
                item { LabelText(text = "OTHER CURRENCIES", modifier = Modifier.padding(vertical = 8.dp)) }

                items(otherCurrencies) { rate ->
                    CurrencyRow(
                        rate = rate,
                        isSelected = selectedCurrency == rate.quote,
                    ) {
                        currencyViewModel.setSelectedCurrency(rate.quote)
                    }
                }
            }

        }
    }
}


@Composable
private fun CurrencyRow(
    rate: FxRate,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text("${rate.quote} (${rate.currencySymbol})", modifier = Modifier.weight(1f))
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.25f))
    }
}
