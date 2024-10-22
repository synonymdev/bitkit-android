package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import to.bitkit.ui.Routes
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.FullWidthTextButton

@Composable
fun LightningSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "LDK",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            FullWidthTextButton({ navController.navigate(Routes.NodeState.destination) }) { Text("Node State") }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Blocktank",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            HorizontalDivider()
            FullWidthTextButton(viewModel::registerForNotifications) { Text("Register for notifications") }
            FullWidthTextButton(viewModel::debugLspNotifications) { Text("Self test notification") }
            FullWidthTextButton(viewModel::openChannel) { Text("Open channel to trusted peer") }

        }
        Spacer(modifier = Modifier.height(1.dp))
    }
}
