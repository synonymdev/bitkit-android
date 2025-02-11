package to.bitkit.ui.screens.wallets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToAllActivity
import to.bitkit.ui.navigateToQrScanner
import to.bitkit.ui.navigateToTransfer
import to.bitkit.ui.postNotificationsPermission
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallets.activity.ActivityList
import to.bitkit.ui.screens.wallets.receive.ReceiveQRScreen
import to.bitkit.ui.screens.wallets.send.SendOptionsView
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun HomeScreen(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    rootNavController: NavController,
) {
    val uiState by walletViewModel.uiState.collectAsState()
    val currentSheet by appViewModel.currentSheet
    SheetHost(
        shouldExpand = currentSheet != null,
        onDismiss = { appViewModel.hideSheet() },
        sheets = {
            when (val sheet = currentSheet) {
                is BottomSheetType.Send -> {
                    SendOptionsView(
                        appViewModel = appViewModel,
                        startDestination = sheet.route,
                        onComplete = { txSheet ->
                            appViewModel.hideSheet()
                            txSheet?.let { appViewModel.showNewTransactionSheet(it) }
                        }
                    )
                }

                is BottomSheetType.Receive -> {
                    ReceiveQRScreen(uiState)
                }

                null -> Unit
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val walletNavController = rememberNavController()
            NavHost(
                navController = walletNavController,
                startDestination = HomeRoutes.Home
            ) {
                composable<HomeRoutes.Home> {
                    HomeContentView(
                        rootNavController = rootNavController,
                        walletNavController = walletNavController,
                        onRefresh = walletViewModel::refreshState,
                    )
                }
                composable<HomeRoutes.Savings> {
                    SavingsWalletScreen(
                        onAllActivityButtonClick = { rootNavController.navigateToAllActivity() },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onTransferClick = { rootNavController.navigateToTransfer() },
                        onBackClick = { walletNavController.popBackStack() },
                    )
                }
                composable<HomeRoutes.Spending> {
                    SpendingWalletScreen(
                        onAllActivityButtonClick = { rootNavController.navigateToAllActivity() },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onBackCLick = { walletNavController.popBackStack() }
                    )
                }
            }

            TabBar(
                onSendClick = { appViewModel.showSheet(BottomSheetType.Send()) },
                onReceiveClick = { appViewModel.showSheet(BottomSheetType.Receive) },
                onScanClick = { rootNavController.navigateToQrScanner() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
            )
        }
    }
}

@Composable
private fun HomeContentView(
    rootNavController: NavController,
    walletNavController: NavController,
    onRefresh: () -> Unit,
) {
    AppScaffold(
        navController = rootNavController,
        titleText = "Your Name",
        onRefresh = onRefresh,
    ) {
        RequestNotificationPermissions()
        val balances = LocalBalances.current
        val app = appViewModel ?: return@AppScaffold
        val showEmptyStateSetting by app.showEmptyState.collectAsState()
        val showEmptyState by remember(balances.totalSats, showEmptyStateSetting) {
            derivedStateOf {
                showEmptyStateSetting && balances.totalSats == 0uL
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
            ) {
                BalanceHeaderView(sats = balances.totalSats.toLong(), modifier = Modifier.fillMaxWidth())
                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        WalletBalanceView(
                            title = stringResource(R.string.wallet__savings__title),
                            sats = balances.totalOnchainSats.toLong(),
                            icon = painterResource(id = R.drawable.ic_btc_circle),
                            modifier = Modifier
                                .clickable(onClick = { walletNavController.navigate(HomeRoutes.Savings) })
                                .padding(vertical = 4.dp)
                        )
                        VerticalDivider()
                        WalletBalanceView(
                            title = stringResource(R.string.wallet__spending__title),
                            sats = balances.totalLightningSats.toLong(),
                            icon = painterResource(id = R.drawable.ic_ln_circle),
                            modifier = Modifier
                                .clickable(onClick = { walletNavController.navigate(HomeRoutes.Spending) })
                                .padding(vertical = 4.dp)
                                .padding(start = 16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Text13Up(stringResource(R.string.wallet__activity), color = Colors.White64)
                    Spacer(modifier = Modifier.height(16.dp))
                    val activity = activityListViewModel ?: return@Column
                    val latestActivities by activity.latestActivities.collectAsState()
                    ActivityList(
                        items = latestActivities,
                        onAllActivityClick = { rootNavController.navigateToAllActivity() },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                    )
                }
            }
            if (showEmptyState) {
                EmptyStateView(
                    text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
                    onClose = { app.setShowEmptyState(false) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun RequestNotificationPermissions() {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(!context.requiresPermission(postNotificationsPermission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted = it
    }

    LaunchedEffect(isGranted) {
        if (!isGranted) {
            launcher.launch(postNotificationsPermission)
        }
    }
}

object HomeRoutes {
    @Serializable
    data object Home

    @Serializable
    data object Savings

    @Serializable
    data object Spending
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeContentViewPreview() {
    AppThemeSurface {
        HomeContentView(
            rootNavController = rememberNavController(),
            walletNavController = rememberNavController(),
            onRefresh = {},
        )
    }
}
