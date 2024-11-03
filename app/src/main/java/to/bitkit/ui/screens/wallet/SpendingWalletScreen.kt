package to.bitkit.ui.screens.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceView
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallet.activity.ActivityLatest
import to.bitkit.ui.screens.wallet.activity.ActivityType

@Composable
fun SpendingWalletScreen(
    viewModel: WalletViewModel,
    navController: NavHostController,
) = AppScaffold(navController, viewModel, "Spending") {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        BalanceView(
            label = "SPENDING BALANCE",
            value = uiState.totalLightningSats,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ActivityLatest(ActivityType.LIGHTNING, viewModel, navController)
    }
}
