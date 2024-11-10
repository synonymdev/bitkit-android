package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import to.bitkit.ui.screens.DevSettingsScreen
import to.bitkit.ui.screens.TransferScreen
import to.bitkit.ui.screens.TransferViewModel
import to.bitkit.ui.screens.wallets.HomeScreen
import to.bitkit.ui.screens.wallets.SavingsWalletScreen
import to.bitkit.ui.screens.wallets.SpendingWalletScreen
import to.bitkit.ui.screens.wallets.activity.ActivityItemScreen
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    onWalletWiped: () -> Unit,
) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        walletViewModel.observeLdkWallet()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnWalletWiped by rememberUpdatedState(onWalletWiped)
    LaunchedEffect(appViewModel, lifecycle) {
        snapshotFlow { appViewModel.uiState }
            .filter { it.walletExists == false }
            .flowWithLifecycle(lifecycle)
            .collect {
                currentOnWalletWiped()
            }
    }

    NavHost(navController, startDestination = Routes.Home) {
        home(walletViewModel, navController)
        settings(walletViewModel, navController)
        nodeState(walletViewModel, navController)
        lightning(walletViewModel, navController)
        devSettings(walletViewModel, navController)
        savings(walletViewModel, navController)
        spending(walletViewModel, navController)
        transfer(navController)
        allActivity(walletViewModel, navController)
        activityItem(walletViewModel, navController)
    }
}

// region destinations
private fun NavGraphBuilder.home(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Home> {
        HomeScreen(viewModel, navController)
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

private fun NavGraphBuilder.savings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Savings> {
        SavingsWalletScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.spending(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Spending> {
        SpendingWalletScreen(viewModel, navController)
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
        AllActivityScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.activityItem(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.ActivityItem> { navBackEntry ->
        ActivityItemScreen(viewModel, navController, activityItem = navBackEntry.toRoute())
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

fun NavController.navigateToLightning() = navigate(
    route = Routes.Lightning,
)

fun NavController.navigateToDevSettings() = navigate(
    route = Routes.DevSettings,
)

fun NavController.navigateToSavings() = navigate(
    route = Routes.Savings,
)

fun NavController.navigateToSpending() = navigate(
    route = Routes.Spending,
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
// endregion

private fun NavOptionsBuilder.clearBackStack() = popUpTo(id = 0)

object Routes {
    @Serializable
    data object Home

    @Serializable
    data object Settings

    @Serializable
    data object NodeState

    @Serializable
    data object Lightning

    @Serializable
    data object DevSettings

    @Serializable
    data object Savings

    @Serializable
    data object Spending

    @Serializable
    data object Transfer

    @Serializable
    data object AllActivity

    @Serializable
    data class ActivityItem(val id: String)
}
