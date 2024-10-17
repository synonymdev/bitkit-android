package to.bitkit.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import to.bitkit.R
import to.bitkit.ui.settings.ChannelsScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.PaymentsScreen
import to.bitkit.ui.settings.PeersScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    viewModel: WalletViewModel = hiltViewModel(),
    walletScreen: @Composable () -> Unit = {},
) {
    // val screenViewModel = viewModel<ScreenViewModel>()
    with(Routes) {
        NavHost(
            navController = navController,
            startDestination = Wallet.destination,
            modifier = modifier,
        ) {
            composable(Wallet.destination) { walletScreen() }
            composable(Settings.destination) { SettingsScreen(navController, viewModel) }
            composable(Lightning.destination) { LightningSettingsScreen(viewModel) }
            composable(NodeState.destination) { NodeStateScreen(viewModel) }
            composable(Peers.destination) { PeersScreen(viewModel) }
            composable(Channels.destination) { ChannelsScreen(viewModel) }
            composable(Payments.destination) { PaymentsScreen(viewModel) }
        }
    }
}

@Stable
@Immutable
@JvmInline
value class Route(val destination: String)

@Stable
object Routes {
    val Wallet = Route("wallet")
    val Settings = Route("settings")
    val Lightning = Route("Lightning")
    val NodeState = Route("NodeState")
    val Peers = Route("peers")
    val Channels = Route("channels")
    val Payments = Route("payments")
}

@Stable
sealed class NavItem(
    @StringRes val title: Int,
    val icon: Pair<ImageVector, ImageVector>,
    val route: Route,
) {
    data object Wallet : NavItem(
        title = R.string.home,
        icon = Icons.Outlined.Home to Icons.Filled.Home,
        route = Routes.Wallet,
    )

    data object Settings : NavItem(
        title = R.string.settings,
        icon = Icons.Outlined.Settings to Icons.Filled.Settings,
        route = Routes.Settings,
    )
}

@Stable
val navItems = listOf(
    NavItem.Wallet,
    NavItem.Settings,
)
