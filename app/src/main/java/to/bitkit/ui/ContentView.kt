package to.bitkit.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.onboarding.InitializingWalletView
import to.bitkit.ui.screens.DevSettingsScreen
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.transfer.TransferScreen
import to.bitkit.ui.screens.transfer.TransferViewModel
import to.bitkit.ui.screens.wallets.HomeScreen
import to.bitkit.ui.screens.wallets.activity.ActivityItemScreen
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.BlocktankRegtestViewModel
import to.bitkit.ui.settings.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.GeneralSettingsScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.backups.BackupWalletScreen
import to.bitkit.ui.settings.backups.RestoreWalletScreen
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.NodeLifecycleState
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun ContentView(
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    blocktankViewModel: BlocktankViewModel,
    currencyViewModel: CurrencyViewModel,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    walletViewModel.start()

                    val pendingTransaction = NewTransactionSheetDetails.load(context)
                    if (pendingTransaction != null) {
                        appViewModel.showNewTransactionSheet(pendingTransaction)
                        NewTransactionSheetDetails.clear(context)
                    }
                }

                Lifecycle.Event.ON_STOP -> {
                    walletViewModel.stopIfNeeded()
                }

                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        walletViewModel.observeLdkWallet()
    }

    val walletUiState by walletViewModel.uiState.collectAsState()

    if (walletUiState.nodeLifecycleState == NodeLifecycleState.Initializing) {
        InitializingWalletView()
    } else {
        val balance by walletViewModel.balanceState.collectAsState()
        val currencies by currencyViewModel.uiState.collectAsState()

        CompositionLocalProvider(
            LocalAppViewModel provides appViewModel,
            LocalWalletViewModel provides walletViewModel,
            LocalBlocktankViewModel provides blocktankViewModel,
            LocalCurrencyViewModel provides currencyViewModel,
            LocalBalances provides balance,
            LocalCurrencies provides currencies,
        ) {
            NavHost(navController, startDestination = Routes.Home) {
                home(walletViewModel, appViewModel, navController)
                settings(walletViewModel, navController)
                nodeState(walletViewModel, navController)
                generalSettings(navController)
                defaultUnitSettings(currencyViewModel, navController)
                localCurrencySettings(currencyViewModel, navController)
                backupSettings(navController)
                backupWalletSettings(navController)
                restoreWalletSettings(navController)
                lightning(walletViewModel, navController)
                devSettings(walletViewModel, navController)
                regtestSettings(navController)
                transfer(navController)
                allActivity(walletViewModel, navController)
                activityItem(walletViewModel, navController)
                qrScanner(appViewModel, navController)
            }
        }
    }
}

// region destinations
private fun NavGraphBuilder.home(
    viewModel: WalletViewModel,
    appViewModel: AppViewModel,
    navController: NavHostController,
) {
    composable<Routes.Home> {
        HomeScreen(
            walletViewModel = viewModel,
            appViewModel = appViewModel,
            rootNavController = navController
        )
    }
}

private fun NavGraphBuilder.settings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Settings> {
        SettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.nodeState(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.NodeState> {
        NodeStateScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.generalSettings(navController: NavHostController) {
    composable<Routes.GeneralSettings> {
        GeneralSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.defaultUnitSettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composable<Routes.DefaultUnitSettings> {
        DefaultUnitSettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.localCurrencySettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composable<Routes.LocalCurrencySettings> {
        LocalCurrencySettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.backupSettings(
    navController: NavHostController,
) {
    composable<Routes.BackupSettings> {
        BackupSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.backupWalletSettings(
    navController: NavHostController,
) {
    composable<Routes.BackupWalletSettings> {
        BackupWalletScreen(navController)
    }
}

private fun NavGraphBuilder.restoreWalletSettings(
    navController: NavHostController,
) {
    composable<Routes.RestoreWalletSettings> {
        RestoreWalletScreen(navController)
    }
}

private fun NavGraphBuilder.lightning(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Lightning> {
        LightningSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.devSettings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.DevSettings> {
        DevSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.regtestSettings(
    navController: NavHostController,
) {
    composable<Routes.RegtestSettings> {
        val viewModel = hiltViewModel<BlocktankRegtestViewModel>()
        BlocktankRegtestScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.transfer(
    navController: NavHostController,
) {
    composable<Routes.Transfer> {
        val viewModel = hiltViewModel<TransferViewModel>()
        TransferScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.allActivity(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.AllActivity> {
        AllActivityScreen(
            viewModel = viewModel,
            onBackCLick = { navController.popBackStack() },
            onActivityItemClick = { navController.navigateToActivityItem(it) },
        )
    }
}

private fun NavGraphBuilder.activityItem(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.ActivityItem> { navBackEntry ->
        ActivityItemScreen(
            viewModel = viewModel,
            activityItem = navBackEntry.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.qrScanner(
    appViewModel: AppViewModel,
    navController: NavHostController,
) {
    composable<Routes.QrScanner>(
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 300)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 300)
            )
        },
    ) {
        QrScanningScreen(navController = navController) { qrCode ->
            navController.popBackStack()
            appViewModel.onScanSuccess(
                data = qrCode,
                onResultDelay = 650 // slight delay to for home navigation before showing send sheet
            )
        }
    }
}
// endregion

// region events
fun NavController.navigateToSettings() = navigate(
    route = Routes.Settings,
)

fun NavController.navigateToNodeState() = navigate(
    route = Routes.NodeState,
)

fun NavController.navigateToGeneralSettings() = navigate(
    route = Routes.GeneralSettings,
)

fun NavController.navigateToDefaultUnitSettings() = navigate(
    route = Routes.DefaultUnitSettings,
)

fun NavController.navigateToLocalCurrencySettings() = navigate(
    route = Routes.LocalCurrencySettings,
)

fun NavController.navigateToBackupSettings() = navigate(
    route = Routes.BackupSettings,
)

fun NavController.navigateToBackupWalletSettings() = navigate(
    route = Routes.BackupWalletSettings,
)

fun NavController.navigateToRestoreWalletSettings() = navigate(
    route = Routes.RestoreWalletSettings,
)

fun NavController.navigateToLightning() = navigate(
    route = Routes.Lightning,
)

fun NavController.navigateToDevSettings() = navigate(
    route = Routes.DevSettings,
)

fun NavController.navigateToRegtestSettings() = navigate(
    route = Routes.RegtestSettings,
)

fun NavController.navigateToTransfer() = navigate(
    route = Routes.Transfer,
)

fun NavController.navigateToAllActivity() = navigate(
    route = Routes.AllActivity,
)

fun NavController.navigateToActivityItem(id: String) = navigate(
    route = Routes.ActivityItem(id),
)

fun NavController.navigateToQrScanner() = navigate(
    route = Routes.QrScanner,
)
// endregion

object Routes {
    @Serializable
    data object Home

    @Serializable
    data object Settings

    @Serializable
    data object NodeState

    @Serializable
    data object GeneralSettings

    @Serializable
    data object DefaultUnitSettings

    @Serializable
    data object LocalCurrencySettings

    @Serializable
    data object BackupSettings

    @Serializable
    data object BackupWalletSettings

    @Serializable
    data object RestoreWalletSettings

    @Serializable
    data object Lightning

    @Serializable
    data object DevSettings

    @Serializable
    data object RegtestSettings

    @Serializable
    data object Transfer

    @Serializable
    data object AllActivity

    @Serializable
    data class ActivityItem(val id: String)

    @Serializable
    data object QrScanner
}
