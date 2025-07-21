package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SavingsWalletScreen(
    onAllActivityButtonClick: () -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onTransferToSpendingClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val balances = LocalBalances.current
    val showEmptyState by remember(balances.totalOnchainSats) {
        mutableStateOf(balances.totalOnchainSats == 0uL) // TODO use && hasOnchainActivity
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.piggybank),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (120).dp)
                .size(268.dp)
        )
        ScreenColumn(noBackground = true) {
            AppTopBar(
                titleText = stringResource(R.string.wallet__savings__title),
                icon = painterResource(R.drawable.ic_btc_circle),
                onBackClick = onBackClick,
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                BalanceHeaderView(sats = balances.totalOnchainSats.toLong(), modifier = Modifier.fillMaxWidth())
                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))
                    SecondaryButton(
                        onClick = onTransferToSpendingClick,
                        text = "Transfer To Spending", // TODO add missing localized text
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_transfer),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    )

                    val activity = activityListViewModel ?: return@Column
                    val onchainActivities by activity.onchainActivities.collectAsState()
                    ActivityListGrouped(
                        items = onchainActivities,
                        onActivityItemClick = onActivityItemClick,
                        onEmptyActivityRowClick = onEmptyActivityRowClick,
                        showFooter = true,
                        onAllActivityButtonClick = onAllActivityButtonClick,
                    )
                }
            }
        }
        if (showEmptyState) {
            EmptyStateView(
                text = stringResource(R.string.wallet__savings__onboarding).withAccent(),
                modifier = Modifier
                    .systemBarsPadding()
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SavingsWalletScreenPreview() {
    AppThemeSurface {
        SavingsWalletScreen(
            onAllActivityButtonClick = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
            onTransferToSpendingClick = {},
            onBackClick = {},
        )
    }
}
