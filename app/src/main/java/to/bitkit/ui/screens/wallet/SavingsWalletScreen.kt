package to.bitkit.ui.screens.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceView
import to.bitkit.ui.navigateToTransfer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.screens.wallet.activity.ActivityLatest
import to.bitkit.ui.screens.wallet.activity.ActivityType

@Composable
fun SavingsWalletScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .systemBarsPadding()
            .fillMaxSize()
    ) {
        AppTopBar(navController, stringResource(R.string.savings))
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
        ) {
            BalanceView(
                label = "SAVINGS BALANCE",
                value = uiState.totalOnchainSats,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = { navController.navigateToTransfer() },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Transfer To Spending",
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            ActivityLatest(ActivityType.ONCHAIN, viewModel, navController)
        }
    }
}
