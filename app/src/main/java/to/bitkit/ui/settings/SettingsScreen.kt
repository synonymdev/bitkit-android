package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.navigateToDevSettings
import to.bitkit.ui.navigateToLightning
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn

@Composable
fun SettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    ScreenColumn {
        AppTopBar(navController, stringResource(R.string.settings))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            NavButton("Lightning") { navController.navigateToLightning() }
            NavButton("Dev Settings") { navController.navigateToDevSettings() }
            NavButton("Wipe Wallet", showIcon = false) { viewModel.wipeStorage() }
        }
    }
}
