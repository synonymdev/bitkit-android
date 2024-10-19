package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
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
import androidx.navigation.NavHostController
import to.bitkit.ui.MainUiState

@Composable
fun BalanceSummary(
    uiState: MainUiState.Content,
    navController: NavHostController,
) {
    Column {
        Text(
            text = "Total balance",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
        )
        val balanceSat = uiState.totalBalanceSats
            ?.let { moneyString(it.toLong(), null) }
            ?: "Loading…"
        Text(
            text = "$balanceSat",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
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
                .padding(vertical = 4.dp)
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
