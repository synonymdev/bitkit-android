package to.bitkit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletView
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.screens.SplashScreen
import to.bitkit.ui.screens.wallets.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.enableAppEdgeToEdge
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel by viewModels<AppViewModel>()
    private val walletViewModel by viewModels<WalletViewModel>()
    private val blocktankViewModel by viewModels<BlocktankViewModel>()
    private val currencyViewModel by viewModels<CurrencyViewModel>()
    private val activityListViewModel by viewModels<ActivityListViewModel>()
    private val transferViewModel by viewModels<TransferViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        installSplashScreen()
        enableAppEdgeToEdge()
        setContent {
            AppThemeSurface {
                val scope = rememberCoroutineScope()
                if (!walletViewModel.walletExists) {
                    val startupNavController = rememberNavController()
                    NavHost(
                        navController = startupNavController,
                        startDestination = StartupRoutes.Terms,
                    ) {
                        composable<StartupRoutes.Terms> {
                            TermsOfUseScreen(
                                onNavigateToIntro = {
                                    startupNavController.navigate(StartupRoutes.Intro)
                                }
                            )
                        }
                        composable<StartupRoutes.Intro> {
                            IntroScreen(
                                onStartClick = {
                                    startupNavController.navigate(StartupRoutes.Slides())
                                },
                                onSkipClick = {
                                    startupNavController.navigate(StartupRoutes.Slides(4))
                                },
                            )
                        }
                        composable<StartupRoutes.Slides> { navBackEntry ->
                            val route = navBackEntry.toRoute<StartupRoutes.Slides>()
                            OnboardingSlidesScreen(
                                currentTab = route.tab,
                                onAdvancedSetupClick = { startupNavController.navigate(StartupRoutes.Advanced) },
                                onCreateClick = {
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.createWallet(bip39Passphrase = null)
                                            walletViewModel.setWalletExistsState()
                                            appViewModel.setShowEmptyState(true)
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                                onRestoreClick = { startupNavController.navigate(StartupRoutes.Restore) },
                            )
                        }
                        composable<StartupRoutes.Restore> {
                            RestoreWalletView(
                                onBackClick = { startupNavController.popBackStack() },
                                onRestoreClick = { mnemonic, passphrase ->
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.isRestoringWallet = true
                                            walletViewModel.restoreWallet(mnemonic, passphrase)
                                            walletViewModel.setWalletExistsState()
                                            appViewModel.setShowEmptyState(false)
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                }
                            )
                        }
                        composable<StartupRoutes.Advanced> {
                            CreateWalletWithPassphraseScreen(
                                onBackClick = { startupNavController.popBackStack() },
                                onCreateClick = { passphrase ->
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                                            walletViewModel.setWalletExistsState()
                                            appViewModel.setShowEmptyState(true)
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    ContentView(
                        appViewModel = appViewModel,
                        walletViewModel = walletViewModel,
                        blocktankViewModel = blocktankViewModel,
                        currencyViewModel = currencyViewModel,
                        activityListViewModel = activityListViewModel,
                        transferViewModel = transferViewModel,
                    )
                }

                ToastOverlay(
                    toast = appViewModel.currentToast,
                    onDismiss = {
                        appViewModel.hideToast()
                    }
                )

                if (appViewModel.showNewTransaction) {
                    NewTransactionSheet(appViewModel)
                }

                SplashScreen(appViewModel.splashVisible)
            }
        }
    }
}

private object StartupRoutes {
    @Serializable
    data object Terms

    @Serializable
    data object Intro

    @Serializable
    data class Slides(val tab: Int = 0)

    @Serializable
    data object Restore

    @Serializable
    data object Advanced
}
