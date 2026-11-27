package to.bitkit.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import to.bitkit.wear.R
import to.bitkit.wear.ext.navigateTo
import to.bitkit.wear.ui.screens.comingsoon.ComingSoonScreen
import to.bitkit.wear.ui.screens.home.HomeScreen
import to.bitkit.wear.ui.screens.onboarding.OnboardingScreen
import to.bitkit.wear.ui.screens.restore.RestoreScreen
import to.bitkit.wear.ui.screens.settings.AboutScreen
import to.bitkit.wear.ui.screens.settings.SettingsScreen
import to.bitkit.wear.ui.screens.unsupported.UnsupportedScreen

@Composable
fun WearNavHost(
    startRoute: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onRestoreClick = { navController.navigateTo(Routes.RESTORE) })
        }
        composable(Routes.RESTORE) {
            RestoreScreen()
        }
        composable(Routes.HOME) {
            HomeScreen(
                onSendClick = { navController.navigateTo(Routes.SEND) },
                onReceiveClick = { navController.navigateTo(Routes.RECEIVE) },
                onSettingsClick = { navController.navigateTo(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEND) {
            ComingSoonScreen(title = stringResource(R.string.home__send), testTag = "WatchSend")
        }
        composable(Routes.RECEIVE) {
            ComingSoonScreen(title = stringResource(R.string.home__receive), testTag = "WatchReceive")
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAboutClick = { navController.navigateTo(Routes.ABOUT) },
                onWalletRemove = { navController.navigateClearingBackStack(Routes.ONBOARDING) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen()
        }
        composable(Routes.UNSUPPORTED) {
            UnsupportedScreen()
        }
    }
}

/** Navigates to [route] as the only screen, so swiping back leaves the app instead of returning. */
fun NavController.navigateClearingBackStack(route: String) {
    navigateTo(route) { popUpTo(graph.id) { inclusive = true } }
}
