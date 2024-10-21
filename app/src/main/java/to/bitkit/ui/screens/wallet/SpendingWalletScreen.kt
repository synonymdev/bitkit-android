package to.bitkit.ui.screens.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceView
import to.bitkit.ui.screens.wallet.activity.ActivityLatest
import to.bitkit.ui.screens.wallet.activity.ActivityType

@Composable
fun SpendingWalletScreen(
    navController: NavHostController,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state.value.asContent() ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Spending",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(modifier = Modifier.height(24.dp))
        BalanceView(
            label = "SPENDING BALANCE",
            value = uiState.totalLightningSats,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ActivityLatest(ActivityType.LIGHTNING, navController)
    }
}
