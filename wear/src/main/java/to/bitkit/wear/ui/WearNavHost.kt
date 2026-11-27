package to.bitkit.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navigation
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import to.bitkit.wear.ext.navigateTo
import to.bitkit.wear.ui.screens.home.HomeScreen
import to.bitkit.wear.ui.screens.onboarding.OnboardingScreen
import to.bitkit.wear.ui.screens.removed.WalletRemovedScreen
import to.bitkit.wear.ui.screens.restore.RestoreChecksumScreen
import to.bitkit.wear.ui.screens.restore.RestoreEffect
import to.bitkit.wear.ui.screens.restore.RestorePassphraseScreen
import to.bitkit.wear.ui.screens.restore.RestoreStartScreen
import to.bitkit.wear.ui.screens.restore.RestoreViewModel
import to.bitkit.wear.ui.screens.restore.RestoreWordScreen
import to.bitkit.wear.ui.screens.settings.AboutScreen
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
        modifier = modifier
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onRestoreClick = { navController.navigateTo(Routes.RESTORE) })
        }
        restore(navController)
        composable(Routes.WALLET_REMOVED) {
            WalletRemovedScreen(onConfirm = { navController.navigateClearingBackStack(Routes.ONBOARDING) })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAboutClick = { navController.navigateTo(Routes.ABOUT) },
                onWalletRemove = { navController.navigateClearingBackStack(Routes.ONBOARDING) },
                onSeedKeyLoss = { navController.navigateClearingBackStack(Routes.WALLET_REMOVED) },
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

private fun NavGraphBuilder.restore(navController: NavController) {
    navigation(startDestination = Routes.RESTORE_START, route = Routes.RESTORE) {
        composable(Routes.RESTORE_START) {
            RestoreStartScreen(viewModel = restoreViewModel(navController, it))
        }
        composable(
            route = Routes.RESTORE_WORD,
            arguments = listOf(navArgument(Routes.RESTORE_WORD_INDEX) { type = NavType.IntType }),
        ) {
            RestoreWordScreen(
                index = it.arguments?.getInt(Routes.RESTORE_WORD_INDEX) ?: 0,
                viewModel = restoreViewModel(navController, it),
            )
        }
        composable(Routes.RESTORE_CHECKSUM) {
            restoreViewModel(navController, it)
            RestoreChecksumScreen(
                onReviewClick = { navController.showRestoreWord(0) },
            )
        }
        composable(Routes.RESTORE_PASSPHRASE) {
            RestorePassphraseScreen(viewModel = restoreViewModel(navController, it))
        }
    }
}

@Composable
private fun restoreViewModel(navController: NavController, entry: NavBackStackEntry): RestoreViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry(Routes.RESTORE) }
    val viewModel = hiltViewModel<RestoreViewModel>(graphEntry)
    LaunchedEffect(viewModel, navController) {
        viewModel.effects.collect {
            when (it) {
                RestoreEffect.ShowStart -> navController.popBackStack(Routes.RESTORE_START, inclusive = false)
                is RestoreEffect.ShowWord -> navController.showRestoreWord(it.index)
                RestoreEffect.ShowChecksumError -> navController.navigateTo(Routes.RESTORE_CHECKSUM)
                RestoreEffect.ShowPassphrase -> navController.navigateTo(Routes.RESTORE_PASSPHRASE)
                RestoreEffect.Restored -> navController.navigateClearingBackStack(Routes.HOME)
            }
        }
    }
    return viewModel
}

private fun NavController.showRestoreWord(index: Int) {
    val current = currentBackStackEntry
    val isShown = current?.destination?.route == Routes.RESTORE_WORD &&
        current.arguments?.getInt(Routes.RESTORE_WORD_INDEX) == index
    if (isShown) return
    navigateTo(Routes.restoreWord(index)) {
        if (index == 0) popUpTo(Routes.RESTORE_START)
    }
}

/** Navigates to [route] as the only screen, so swiping back leaves the app instead of returning. */
fun NavController.navigateClearingBackStack(route: String) {
    navigateTo(route) { popUpTo(graph.id) { inclusive = true } }
}
