package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import to.bitkit.ui.MainUiState
import to.bitkit.ui.Routes

@Composable
fun BalanceSummary(
    uiState: MainUiState.Content,
    navController: NavHostController,
) {
    Column {
        Text(
            text = "Total balance",
            style = MaterialTheme.typography.titleMedium,
        )
        val balanceSat = uiState.totalBalanceSats?.let { moneyString(it.toLong()) } ?: "Loading…"
        Text(
            text = "$balanceSat",
            style = MaterialTheme.typography.titleLarge,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(
            onClick = {
                navController.navigate(Routes.Transfer.destination)
            },
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
        Column {
            Row {
                Text(
                    text = "Savings",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(uiState.balanceDetails?.totalOnchainBalanceSats?.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Text(
                    text = "Spending",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = moneyString(uiState.balanceDetails?.totalLightningBalanceSats?.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
