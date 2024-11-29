package to.bitkit.ui.screens.wallets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ui.AppViewModel
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceSummary
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToAllActivity
import to.bitkit.ui.navigateToTransfer
import to.bitkit.ui.postNotificationsPermission
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallets.activity.ActivityList
import to.bitkit.ui.screens.wallets.receive.ReceiveQRScreen
import to.bitkit.ui.screens.wallets.send.SendOptionsView
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.shared.util.qrCodeScanner

@Composable
fun HomeScreen(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    navController: NavController,
) {
    val uiState by walletViewModel.uiState.collectAsState()
    val currentSheet by appViewModel.currentSheet
    SheetHost(
        appViewModel,
        sheets = {
            when (currentSheet) {
                BottomSheetType.Send -> {
                    SendOptionsView(
                        onComplete = { sheet ->
                            appViewModel.hideSheet()
                            sheet?.let { appViewModel.showNewTransactionSheet(it) }
                        }
                    )
                }

                BottomSheetType.Receive -> {
                    ReceiveQRScreen(uiState)
                }

                null -> Unit
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val nestedNavController = rememberNavController()
            NavHost(
                navController = nestedNavController,
                startDestination = HomeRoutes.Home
            ) {
                composable<HomeRoutes.Home> {
                    HomeContentView(navController, walletViewModel, nestedNavController)
                }
                composable<HomeRoutes.Savings> {
                    SavingsWalletScreen(
                        viewModel = walletViewModel,
                        onAllActivityButtonClick = { navController.navigateToAllActivity() },
                        onActivityItemClick = { navController.navigateToActivityItem(it) },
                        onTransferClick = { navController.navigateToTransfer() },
                        onBackClick = { nestedNavController.popBackStack() },
                    )
                }
                composable<HomeRoutes.Spending> {
                    SpendingWalletScreen(
                        viewModel = walletViewModel,
                        onAllActivityButtonClick = { navController.navigateToAllActivity() },
                        onActivityItemClick = { navController.navigateToActivityItem(it) },
                        onBackCLick = { nestedNavController.popBackStack() }
                    )
                }

            }

            val scanner = qrCodeScanner()
            TabBar(
                onSendClick = { appViewModel.showSheet(BottomSheetType.Send) },
                onReceiveClick = { appViewModel.showSheet(BottomSheetType.Receive) },
                onScanClick = {
                    scanner?.startScan()?.addOnCompleteListener { task ->
                        task.takeIf { it.isSuccessful }?.result?.rawValue?.let { data ->
                            walletViewModel.onScanSuccess(data)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding()
            )
        }
    }
}

@Composable
private fun HomeContentView(
    navController: NavController,
    walletViewModel: WalletViewModel,
    nestedNavController: NavHostController,
) {
    AppScaffold(navController, walletViewModel, stringResource(R.string.app_name)) {
        RequestNotificationPermissions()
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            BalanceSummary(
                onSavingsClick = { nestedNavController.navigate(HomeRoutes.Savings) },
                onSpendingClick = { nestedNavController.navigate(HomeRoutes.Savings) },
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            ActivityList(
                items = walletViewModel.activityItems.value?.take(3),
                onAllActivityClick = { navController.navigateToAllActivity() },
                onActivityItemClick = { navController.navigateToActivityItem(it) },
            )
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
