package to.bitkit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.display
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.PrimaryDisplay

@Composable
fun DefaultUnitSettingsScreen(
    currencyViewModel: CurrencyViewModel,
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.default_unit), onBackClick = { navController.popBackStack() })
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val (_, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
            SectionTitle(title = "DISPLAY AMOUNTS IN")

            CurrencyOptionRow(
                leadingIcon = Icons.Default.CurrencyBitcoin,
                label = "Bitcoin",
                isSelected = primaryDisplay == PrimaryDisplay.BITCOIN,
                onClick = {
                    currencyViewModel.setPrimaryDisplayUnit(PrimaryDisplay.BITCOIN)
                },
            )

            val convertedCurrency = currencyViewModel.convert(1)?.currency ?: "fiat"
            CurrencyOptionRow(
                leadingIcon = Icons.Default.Language,
                label = convertedCurrency,
                isSelected = primaryDisplay == PrimaryDisplay.FIAT,
                onClick = {
                    currencyViewModel.setPrimaryDisplayUnit(PrimaryDisplay.FIAT)
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            LabelText(
                text = "Tip: Quickly toggle between Bitcoin and $convertedCurrency by tapping on your wallet balance.",
            )

            Spacer(modifier = Modifier.height(16.dp))

            SectionTitle(title = "BITCOIN DENOMINATION")
            BitcoinDisplayUnit.entries.forEach { unit ->
                BitcoinDenominationRow(
                    unit = unit,
                    isSelected = displayUnit == unit,
                    onClick = {
                        currencyViewModel.setBtcDisplayUnit(unit)
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    LabelText(
        text = title,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun CurrencyOptionRow(
    leadingIcon: ImageVector,
    label: String,
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
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), shape = CircleShape)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label)
            Spacer(modifier = Modifier.weight(1f))
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

@Composable
private fun BitcoinDenominationRow(unit: BitcoinDisplayUnit, isSelected: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(text = "${unit.display} (${if (unit == BitcoinDisplayUnit.MODERN) "₿ 10 000" else "₿ 0.00010000"})")
            Spacer(modifier = Modifier.weight(1f))
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
