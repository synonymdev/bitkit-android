package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.ui.screens.DevSettingsScreen
import to.bitkit.ui.screens.TransferScreen
import to.bitkit.ui.screens.wallet.SavingsWalletScreen
import to.bitkit.ui.screens.wallet.SpendingWalletScreen
import to.bitkit.ui.screens.wallet.activity.ActivityItemScreen
import to.bitkit.ui.screens.wallet.activity.AllActivityScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModel: WalletViewModel,
    content: @Composable () -> Unit,
) {
    NavHost(navController, startDestination = Routes.Main) {
        home(content)
        settings(viewModel, navController)
        nodeState(viewModel, navController)
        lightning(viewModel, navController)
        devSettings(viewModel, navController)
        savings(viewModel, navController)
        spending(viewModel, navController)
        transfer(viewModel, navController)
        allActivity(viewModel, navController)
        activityItem(viewModel, navController)
    }
}

// region destinations
private fun NavGraphBuilder.home(content: @Composable () -> Unit) {
    composable(Routes.Main) {
        content()
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
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Transfer> {
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
fun NavController.navigateToHome() = navigate(
    route = Routes.Main,
    builder = { clearBackStack() },
)

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
    @Suppress("ConstPropertyName")
    const val Main = "Main"

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
