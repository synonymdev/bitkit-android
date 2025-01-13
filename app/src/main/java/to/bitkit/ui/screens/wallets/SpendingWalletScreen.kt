package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PaymentKind
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.activityListViewModel
import to.bitkit.viewmodels.WalletViewModel
import to.bitkit.ui.components.BalanceHeaderView
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
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BalanceHeaderView(sats = balances.totalLightningSats.toLong(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            val activity = activityListViewModel ?: return@Column
            val lightningActivities by activity.lightningActivities.collectAsState()
            ActivityListWithHeaders(
                items = lightningActivities,
                showFooter = true,
                onAllActivityButtonClick = onAllActivityButtonClick,
                onActivityItemClick = onActivityItemClick,
            )
        }
    }
}
