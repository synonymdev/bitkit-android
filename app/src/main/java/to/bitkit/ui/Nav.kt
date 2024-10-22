package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
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
import to.bitkit.ui.settings.ChannelsScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.PeersScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    viewModel: WalletViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    // val screenViewModel = viewModel<ScreenViewModel>()
    with(Routes) {
        NavHost(
            navController = navController,
            startDestination = Main.destination,
            modifier = modifier,
        ) {
            composable(Main.destination) { content() }
            composable(Settings.destination) { SettingsScreen(navController, viewModel) }
            composable(Lightning.destination) { LightningSettingsScreen(viewModel, navController) }
            composable(DevSettings.destination) { DevSettingsScreen(viewModel) }
            composable(NodeState.destination) { NodeStateScreen(viewModel) }
            composable(Peers.destination) { PeersScreen(viewModel) }
            composable(Channels.destination) { ChannelsScreen(viewModel) }
            composable(Transfer.destination) { TransferScreen() }
            composable(AllActivity.destination) { AllActivityScreen(viewModel, navController) }
            composable(Savings.destination) { SavingsWalletScreen(navController) }
            composable(Spending.destination) { SpendingWalletScreen(navController) }
            composable<ActivityItemRoute> { backStackEntry ->
                val activityItem: ActivityItemRoute = backStackEntry.toRoute()
                ActivityItemScreen(activityItem)
            }
        }
    }
}

@Stable
@Immutable
@JvmInline
value class Route(val destination: String)

@Stable
object Routes {
    val Main = Route("Main")
    val Settings = Route("Settings")
    val Lightning = Route("Lightning")
    val NodeState = Route("NodeState")
    val Transfer = Route("Transfer")
    val AllActivity = Route("AllActivity")
    val Peers = Route("Peers")
    val Channels = Route("Channels")
    val DevSettings = Route("DevSettings")
    val Savings = Route("Savings")
    val Spending = Route("Spending")
}

@Serializable
data class ActivityItemRoute(val id: String)
