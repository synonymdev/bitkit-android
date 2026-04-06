package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SavingsWalletScreen(
    isGeoBlocked: Boolean,
    onchainActivities: ImmutableList<Activity>,
    onAllActivityButtonClick: () -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onTransferToSpendingClick: () -> Unit,
    onBackClick: () -> Unit,
    forceCloseRemainingDuration: String? = null,
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

    val hazeState = rememberHazeState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .blockPointerInputPassthrough()
    ) {
        // Background layer: hazeSource must be a sibling of hazeEffect, not a parent.
        // Haze can't blur an ancestor — source and effect must be at the same level.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Colors.Black)
                .hazeSource(hazeState)
        ) {
            Image(
                painter = painterResource(id = R.drawable.piggybank),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 0.dp)
                    .offset(x = (160).dp)
                    .size(360.dp)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Vertical))
            )
        }
        ScreenColumn(noBackground = true) {
            AppTopBar(
                titleText = stringResource(R.string.wallet__savings__title),
                icon = R.drawable.ic_btc_circle,
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
                        remainingDuration = forceCloseRemainingDuration,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))

                    if (canTransfer) {
                        SecondaryButton(
                            onClick = onTransferToSpendingClick,
                            text = stringResource(R.string.wallet__transfer_to_spending),
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_transfer),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            hazeState = hazeState,
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
                onchainActivities = persistentListOf(),
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
                onchainActivities = persistentListOf(),
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
