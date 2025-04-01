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
import to.bitkit.ui.screens.wallets.activity.ActivityListWithHeaders
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.MainUiState

@Composable
fun SpendingWalletScreen(
    uiState: MainUiState,
    onAllActivityButtonClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onTransferToSavingsClick: () -> Unit,
    onBackCLick: () -> Unit,
) {
    val balances = LocalBalances.current
    val showEmptyState by remember(balances.totalLightningSats) {
        // TODO use && hasLnActivity + LN spendingSats
        mutableStateOf(balances.totalLightningSats == 0uL)
    }
    val canTransfer by remember(balances.totalLightningSats, uiState.channels.size) {
        val hasLnBalance = balances.totalLightningSats > 0uL
        val hasChannels = uiState.channels.isNotEmpty()

        mutableStateOf(hasLnBalance && hasChannels)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.coin_stack_x_2),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (155).dp, y = (-35).dp)
                .size(330.dp)
        )
        ScreenColumn(noBackground = true) {
            AppTopBar(
                titleText = stringResource(R.string.wallet__spending__title),
                icon = painterResource(R.drawable.ic_ln_circle),
                onBackClick = onBackCLick,
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                BalanceHeaderView(sats = balances.totalLightningSats.toLong(), modifier = Modifier.fillMaxWidth())

                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))

                    if (canTransfer) {
                        SecondaryButton(
                            onClick = onTransferToSavingsClick,
                            text = "Transfer To Savings",
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_transfer),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

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
        if (showEmptyState) {
            EmptyStateView(
                text = stringResource(R.string.wallet__spending__onboarding).withAccent(accentColor = Colors.Purple),
                modifier = Modifier
                    .systemBarsPadding()
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SpendingWalletScreenPreview() {
    AppThemeSurface {
        SpendingWalletScreen(
            uiState = MainUiState(),
            onAllActivityButtonClick = {},
            onActivityItemClick = {},
            onTransferToSavingsClick = {},
            onBackCLick = {},
        )
    }
}
