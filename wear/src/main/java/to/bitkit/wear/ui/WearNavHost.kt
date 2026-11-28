package to.bitkit.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import to.bitkit.wear.ui.screens.activity.ActivityDetailScreen
import to.bitkit.wear.ui.screens.activity.ActivityViewModel
import to.bitkit.wear.ui.screens.home.HomeScreen
import to.bitkit.wear.ui.screens.onboarding.OnboardingScreen
import to.bitkit.wear.ui.screens.receive.ReceiveQrScreen
import to.bitkit.wear.ui.screens.removed.WalletRemovedScreen
import to.bitkit.wear.ui.screens.restore.RestoreChecksumScreen
import to.bitkit.wear.ui.screens.restore.RestoreEffect
import to.bitkit.wear.ui.screens.restore.RestorePassphraseScreen
import to.bitkit.wear.ui.screens.restore.RestoreStartScreen
import to.bitkit.wear.ui.screens.restore.RestoreViewModel
import to.bitkit.wear.ui.screens.restore.RestoreWordScreen
import to.bitkit.wear.ui.screens.send.SendScreen
import to.bitkit.wear.ui.screens.settings.AboutScreen
import to.bitkit.wear.ui.screens.unsupported.UnsupportedScreen
import to.bitkit.wear.utils.Logger

@Composable
fun WearNavHost(
    startRoute: String,
    launchRequest: LaunchRequest?,
    onLaunchRequestHandle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberSwipeDismissableNavController()
    var isRestoreSaving by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    val currentOnLaunchRequestHandle by rememberUpdatedState(onLaunchRequestHandle)
    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        currentOnLaunchRequestHandle()
        navController.showLaunchRequest(request)
    }
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startRoute,
        userSwipeEnabled = !isRestoreSaving && !isSending,
        modifier = modifier
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onRestoreClick = { navController.navigateTo(Routes.RESTORE) },
                onWalletFind = { navController.navigateClearingBackStack(Routes.HOME) },
            )
        }
        restore(navController, onSavingChange = { isRestoreSaving = it })
        composable(Routes.WALLET_REMOVED) {
            WalletRemovedScreen(onConfirm = { navController.navigateClearingBackStack(Routes.ONBOARDING) })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onReceiveClick = { navController.navigateTo(Routes.RECEIVE_QR) },
                onPaymentClick = { navController.navigateTo(Routes.activityDetail(it)) },
                onAboutClick = { navController.navigateTo(Routes.ABOUT) },
                onWalletRemove = { navController.navigateClearingBackStack(Routes.ONBOARDING) },
                onSeedKeyLoss = { navController.navigateClearingBackStack(Routes.WALLET_REMOVED) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen()
        }
        composable(Routes.RECEIVE_QR) {
            ReceiveQrScreen()
        }
        composable(
            route = Routes.SEND,
            arguments = listOf(navArgument(Routes.SEND_INPUT) { type = NavType.StringType }),
        ) {
            SendScreen(
                onClose = { navController.popBackStack(Routes.SEND, inclusive = true) },
                onBusyChange = { isSending = it },
            )
        }
        composable(
            route = Routes.ACTIVITY_DETAIL,
            arguments = listOf(navArgument(Routes.ACTIVITY_PAYMENT_ID) { type = NavType.StringType }),
        ) {
            val homeEntry = remember(it) { navController.getBackStackEntry(Routes.HOME) }
            ActivityDetailScreen(
                paymentId = it.arguments?.getString(Routes.ACTIVITY_PAYMENT_ID).orEmpty(),
                viewModel = hiltViewModel<ActivityViewModel>(homeEntry),
            )
        }
        composable(Routes.UNSUPPORTED) {
            UnsupportedScreen()
        }
    }
}

private fun NavGraphBuilder.restore(
    navController: NavController,
    onSavingChange: (Boolean) -> Unit,
) {
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
            RestorePassphraseScreen(
                viewModel = restoreViewModel(navController, it),
                onSavingChange = onSavingChange,
            )
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

/**
 * Opens what [request] asks for above Home: the send flow of a deeplink or the receive QR. Without a wallet, or
 * outside Home's back stack, such as during the restore, the request is dropped.
 */
private fun NavController.showLaunchRequest(request: LaunchRequest) {
    val hasHome = runCatching { getBackStackEntry(Routes.HOME) }.isSuccess
    if (!hasHome) {
        Logger.info("Dropped launch request '${request::class.simpleName}' outside home", context = TAG)
        return
    }
    popBackStack(Routes.HOME, inclusive = false)
    when (request) {
        is LaunchRequest.Send -> navigateTo(Routes.send(request.input))
        LaunchRequest.Receive -> navigateTo(Routes.RECEIVE_QR)
    }
}

private const val TAG = "WearNavHost"

/** Navigates to [route] as the only screen, so swiping back leaves the app instead of returning. */
fun NavController.navigateClearingBackStack(route: String) {
    navigateTo(route) { popUpTo(graph.id) { inclusive = true } }
}
