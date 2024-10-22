package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.NavButton

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: WalletViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        NavButton("Lightning") { navController.navigate(Routes.Lightning.destination) }
        NavButton("Developer") { navController.navigate(Routes.DevSettings.destination) }
        NavButton("Wipe Wallet", showIcon = false) { viewModel.wipeStorage() }
    }
}
