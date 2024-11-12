package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.ui.MainUiState
import to.bitkit.ui.navigateToSavings
import to.bitkit.ui.navigateToSpending
import to.bitkit.ui.shared.moneyString

@Composable
fun BalanceSummary(
    uiState: MainUiState,
    navController: NavController,
) {
    BalanceView(
        label = "TOTAL BALANCE",
        value = uiState.totalBalanceSats,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { navController.navigateToSavings() }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "SAVINGS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = moneyString(uiState.totalOnchainSats?.toLong(), null),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        VerticalDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { navController.navigateToSpending() }
                .padding(4.dp)
        ) {
            Text(
                text = "SPENDING",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = moneyString(uiState.totalLightningSats?.toLong(), null),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
fun BalanceView(
    label: String,
    value: ULong?,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
        )
        val valueText = value
            ?.let { moneyString(it.toLong(), null) }
            ?: "Loading…"
        Text(
            text = "$valueText",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
    }
}
