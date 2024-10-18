package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import to.bitkit.ui.MainUiState

@Composable
fun BalanceSummary(
    uiState: MainUiState.Content,
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
}
