package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PaymentKind
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.ActivityListWithHeaders

@Composable
fun SavingsWalletScreen(
    viewModel: WalletViewModel,
    onAllActivityButtonClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onTransferClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val balances = LocalBalances.current
    ScreenColumn {
        AppTopBar(stringResource(R.string.savings), onBackClick)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BalanceHeaderView(sats = balances.totalOnchainSats.toLong(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onTransferClick,
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
            ActivityListWithHeaders(
                items = viewModel.activityItems.value?.filter { it.kind is PaymentKind.Onchain },
                showFooter = true,
                onAllActivityButtonClick = onAllActivityButtonClick,
                onActivityItemClick = onActivityItemClick,
            )
        }
    }
}
