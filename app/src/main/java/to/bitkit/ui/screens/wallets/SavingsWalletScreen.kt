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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import to.bitkit.R
import to.bitkit.models.BalanceState
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.IncomingTransfer
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.screens.wallets.activity.utils.previewOnchainActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SavingsWalletScreen(
    isGeoBlocked: Boolean,
    onchainActivities: List<Activity>,
    onAllActivityButtonClick: () -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onTransferToSpendingClick: () -> Unit,
    onBackClick: () -> Unit,
    balances: BalanceState = LocalBalances.current,
) {
    val showEmptyState by remember(balances.totalOnchainSats, onchainActivities.size) {
        val hasFunds = balances.totalOnchainSats > 0uL
        val hasActivity = onchainActivities.isNotEmpty()
        mutableStateOf(!hasFunds && !hasActivity)
    }
    val canTransfer by remember(balances.totalOnchainSats) {
        val hasFunds = balances.totalOnchainSats > 0uL
        mutableStateOf(hasFunds && !isGeoBlocked)
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
                .padding(top = 32.dp)
                .offset(x = (120).dp)
                .size(268.dp)
        )
        ScreenColumn(noBackground = true) {
            AppTopBar(
                titleText = stringResource(R.string.wallet__savings__title),
                icon = painterResource(R.drawable.ic_btc_circle),
                onBackClick = onBackClick,
                actions = {
                    DrawerNavIcon()
                }
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                BalanceHeaderView(
                    sats = balances.totalOnchainSats.toLong(),
                    testTag = "TotalBalance",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("TotalBalance")
                )

                if (balances.balanceInTransferToSavings > 0u) {
                    IncomingTransfer(
                        amount = balances.balanceInTransferToSavings,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))

                    if (canTransfer) {
                        SecondaryButton(
                            onClick = onTransferToSpendingClick,
                            text = "Transfer To Spending", // TODO add missing localized text
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_transfer),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.testTag("TransferToSpending")
                        )
                    }

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
private fun Preview() {
    AppThemeSurface {
        Box {
            SavingsWalletScreen(
                isGeoBlocked = false,
                onchainActivities = previewOnchainActivityItems(),
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
                onTransferToSpendingClick = {},
                onBackClick = {},
                balances = BalanceState(totalOnchainSats = 50_000u),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewTransfer() {
    AppThemeSurface {
        Box {
            SavingsWalletScreen(
                isGeoBlocked = false,
                onchainActivities = previewOnchainActivityItems(),
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
                onTransferToSpendingClick = {},
                onBackClick = {},
                balances = BalanceState(
                    totalOnchainSats = 50_000u,
                    balanceInTransferToSavings = 25_000u,
                ),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewNoActivity() {
    AppThemeSurface {
        Box {
            SavingsWalletScreen(
                isGeoBlocked = false,
                onchainActivities = emptyList(),
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
                onTransferToSpendingClick = {},
                onBackClick = {},
                balances = BalanceState(totalOnchainSats = 50_000u),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewGeoBlocked() {
    AppThemeSurface {
        Box {
            SavingsWalletScreen(
                isGeoBlocked = true,
                onchainActivities = previewOnchainActivityItems(),
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
                onTransferToSpendingClick = {},
                onBackClick = {},
                balances = BalanceState(totalOnchainSats = 50_000u),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Box {
            SavingsWalletScreen(
                isGeoBlocked = false,
                onchainActivities = emptyList(),
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
                onTransferToSpendingClick = {},
                onBackClick = {},
            )
            TabBar()
        }
    }
}
