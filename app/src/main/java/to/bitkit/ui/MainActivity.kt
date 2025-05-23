package to.bitkit.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.androidServices.LightningNodeService
import to.bitkit.androidServices.LightningNodeService.Companion.CHANNEL_ID_NODE
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.ForgotPinSheet
import to.bitkit.ui.components.InactivityTracker
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletView
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.onboarding.WarningMultipleDevicesScreen
import to.bitkit.ui.screens.SplashScreen
import to.bitkit.ui.screens.wallets.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.enableAppEdgeToEdge
import to.bitkit.ui.utils.screenScaleIn
import to.bitkit.ui.utils.screenScaleOut
import to.bitkit.ui.utils.screenSlideIn
import to.bitkit.ui.utils.screenSlideOut
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.MainScreenEffect
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val appViewModel by viewModels<AppViewModel>()
    private val walletViewModel by viewModels<WalletViewModel>()
    private val blocktankViewModel by viewModels<BlocktankViewModel>()
    private val currencyViewModel by viewModels<CurrencyViewModel>()
    private val activityListViewModel by viewModels<ActivityListViewModel>()
    private val transferViewModel by viewModels<TransferViewModel>()
    private val settingsViewModel by viewModels<SettingsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        initNotificationChannel( //TODO EXTRACT TO Strings
            id = CHANNEL_ID_NODE,
            name = "Lightning node notification",
            desc = "Channel for LightningNodeService",
        )
        startForegroundService(Intent(this, LightningNodeService::class.java))
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
                        composable<StartupRoutes.Intro>(
                            enterTransition = { screenSlideIn },
                            exitTransition = { screenScaleOut },
                            popEnterTransition = { screenScaleIn },
                            popExitTransition = { screenSlideOut },
                        ) {
                            IntroScreen(
                                onStartClick = {
                                    startupNavController.navigate(StartupRoutes.Slides())
                                },
                                onSkipClick = {
                                    startupNavController.navigate(StartupRoutes.Slides(4))
                                },
                            )
                        }
                        composable<StartupRoutes.Slides>(
                            enterTransition = { screenSlideIn },
                            exitTransition = { screenScaleOut },
                            popEnterTransition = { screenScaleIn },
                            popExitTransition = { screenSlideOut },
                        ) { navBackEntry ->
                            val route = navBackEntry.toRoute<StartupRoutes.Slides>()
                            OnboardingSlidesScreen(
                                currentTab = route.tab,
                                onAdvancedSetupClick = { startupNavController.navigate(StartupRoutes.Advanced) },
                                onCreateClick = {
                                    scope.launch {
                                        try {
                                            appViewModel.resetIsAuthenticatedState()
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.createWallet(bip39Passphrase = null)
                                            settingsViewModel.setShowEmptyState(true)
                                        } catch (e: Throwable) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                                onRestoreClick = { startupNavController.navigate(StartupRoutes.WarningMultipleDevices) },
                            )
                        }
                        composable<StartupRoutes.WarningMultipleDevices>(
                            enterTransition = { screenSlideIn },
                            exitTransition = { screenScaleOut },
                            popEnterTransition = { screenScaleIn },
                            popExitTransition = { screenSlideOut },
                        ) {
                            WarningMultipleDevicesScreen(
                                onBackClick = {
                                    startupNavController.popBackStack()
                                },
                                onConfirmClick = {
                                    startupNavController.navigate(StartupRoutes.Restore)
                                }
                            )
                        }
                        composable<StartupRoutes.Restore>(
                            enterTransition = { screenSlideIn },
                            exitTransition = { screenScaleOut },
                            popEnterTransition = { screenScaleIn },
                            popExitTransition = { screenSlideOut },
                        ) {
                            RestoreWalletView(
                                onBackClick = { startupNavController.popBackStack() },
                                onRestoreClick = { mnemonic, passphrase ->
                                    scope.launch {
                                        try {
                                            appViewModel.resetIsAuthenticatedState()
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.setRestoringWalletState(isRestoringWallet = true)
                                            walletViewModel.restoreWallet(mnemonic, passphrase)
                                            settingsViewModel.setShowEmptyState(false)
                                        } catch (e: Throwable) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                }
                            )
                        }
                        composable<StartupRoutes.Advanced>(
                            enterTransition = { screenSlideIn },
                            exitTransition = { screenScaleOut },
                            popEnterTransition = { screenScaleIn },
                            popExitTransition = { screenSlideOut },
                        ) {
                            CreateWalletWithPassphraseScreen(
                                onBackClick = { startupNavController.popBackStack() },
                                onCreateClick = { passphrase ->
                                    scope.launch {
                                        try {
                                            appViewModel.resetIsAuthenticatedState()
                                            walletViewModel.setInitNodeLifecycleState()
                                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                                            settingsViewModel.setShowEmptyState(true)
                                        } catch (e: Throwable) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    InactivityTracker(appViewModel, settingsViewModel) {
                        ContentView(
                            appViewModel = appViewModel,
                            walletViewModel = walletViewModel,
                            blocktankViewModel = blocktankViewModel,
                            currencyViewModel = currencyViewModel,
                            activityListViewModel = activityListViewModel,
                            transferViewModel = transferViewModel,
                            settingsViewModel = settingsViewModel,
                        )
                    }

                    val isAuthenticated by appViewModel.isAuthenticated.collectAsStateWithLifecycle()
                    AnimatedVisibility(
                        visible = !isAuthenticated,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        AuthCheckView(
                            showLogoOnPin = true,
                            appViewModel = appViewModel,
                            onSuccess = { appViewModel.setIsAuthenticated(true) },
                        )
                    }

                    val showForgotPinSheet by appViewModel.showForgotPinSheet.collectAsStateWithLifecycle()
                    if (showForgotPinSheet) {
                        ForgotPinSheet(
                            onDismiss = { appViewModel.setShowForgotPin(false) },
                            onResetClick = { walletViewModel.wipeStorage() },
                        )
                    }

                    LaunchedEffect(appViewModel) {
                        appViewModel.mainScreenEffect.collect {
                            when (it) {
                                MainScreenEffect.WipeStorage -> walletViewModel.wipeStorage()
                                else -> Unit
                            }
                        }
                    }
                }

                ToastOverlay(
                    toast = appViewModel.currentToast,
                    onDismiss = {
                        appViewModel.hideToast()
                    }
                )

                if (appViewModel.showNewTransaction) {
                    NewTransactionSheet(appViewModel = appViewModel, currencyViewModel = currencyViewModel)
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

    @Serializable
    data object WarningMultipleDevices
}
