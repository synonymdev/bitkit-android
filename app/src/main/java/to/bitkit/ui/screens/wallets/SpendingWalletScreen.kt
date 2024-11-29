package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PaymentKind
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceView
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.ActivityListWithHeaders

@Composable
fun SpendingWalletScreen(
    viewModel: WalletViewModel,
    onAllActivityButtonClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onBackCLick: () -> Unit,
) {
    val balances = LocalBalances.current
    ScreenColumn {
        AppTopBar(stringResource(R.string.spending), onBackCLick)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
        ) {
            BalanceView(
                label = stringResource(R.string.label_balance_spending),
                value = balances.totalLightningSats,
            )
            Spacer(modifier = Modifier.height(24.dp))
            ActivityListWithHeaders(
                items = viewModel.activityItems.value?.filter { it.kind is PaymentKind.Bolt11 },
                showFooter = true,
                onAllActivityButtonClick = onAllActivityButtonClick,
                onActivityItemClick = onActivityItemClick,
            )
        }
    }
}
