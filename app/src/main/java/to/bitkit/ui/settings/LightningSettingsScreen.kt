package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import to.bitkit.R
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.navigateToNodeState
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun LightningSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    ScreenColumn {
        AppTopBar(
            stringResource(R.string.lightning),
            onBackClick = { navController.popBackStack() },
            actions = {
                IconButton(onClick = { navController.navigateToTransferFunding() }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.lightning__conn_button_add),
                    )
                }
            }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                Text(
                    text = "LDK",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(12.dp)
                )
                NavButton("Node State") { navController.navigateToNodeState() }
            }
            Column {
                Text(
                    text = "Blocktank",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(12.dp)
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    FullWidthTextButton(viewModel::manualRegisterForNotifications) { Text("Register for notifications") }
                    FullWidthTextButton(viewModel::debugLspNotifications) { Text("Self test notification") }
                    FullWidthTextButton(viewModel::openChannel) { Text("Open channel to trusted peer") }

                }
            }
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}
