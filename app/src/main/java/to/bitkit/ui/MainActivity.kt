package to.bitkit.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.androidServices.LightningNodeService
import to.bitkit.androidServices.LightningNodeService.Companion.CHANNEL_ID_NODE
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.InactivityTracker
import to.bitkit.ui.components.IsOnlineTracker
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletScreen
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.onboarding.WarningMultipleDevicesScreen
import to.bitkit.ui.screens.SplashScreen
import to.bitkit.ui.sheets.ForgotPinSheet
import to.bitkit.ui.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.enableAppEdgeToEdge
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BackupsViewModel
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
    private val backupsViewModel by viewModels<BackupsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        initNotificationChannel(
            // TODO Transifex
            id = CHANNEL_ID_NODE,
            name = "Lightning node notification",
            desc = "Channel for LightningNodeService",
            importance = NotificationManager.IMPORTANCE_LOW
        )
        appViewModel.handleDeeplinkIntent(intent)

        installSplashScreen()
        enableAppEdgeToEdge()
        setContent {
            AppThemeSurface(
                modifier = Modifier.semantics {
                    testTagsAsResourceId = true // see https://github.com/appium/appium/issues/15138
                }
            ) {
                val scope = rememberCoroutineScope()
                val isRecoveryMode by walletViewModel.isRecoveryMode.collectAsStateWithLifecycle()
                val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
                val walletExists by walletViewModel.walletState
                    .map { it.walletExists }
                    .collectAsStateWithLifecycle(initialValue = walletViewModel.walletExists)
                val hazeState = rememberHazeState(blurEnabled = true)

                LaunchedEffect(
                    walletExists,
                    isRecoveryMode,
                    notificationsGranted
                ) {
                    if (walletExists && !isRecoveryMode && notificationsGranted) {
                        tryStartForegroundService()
                    }
                }

                if (!walletViewModel.walletExists && !isRecoveryMode) {
                    OnboardingNav(
                        startupNavController = rememberNavController(),
                        scope = scope,
                        appViewModel = appViewModel,
                        walletViewModel = walletViewModel,
                    )
                } else {
                    val isAuthenticated by appViewModel.isAuthenticated.collectAsStateWithLifecycle()

                    IsOnlineTracker(appViewModel)
                    InactivityTracker(appViewModel, settingsViewModel) {
                        ContentView(
                            appViewModel = appViewModel,
                            walletViewModel = walletViewModel,
                            blocktankViewModel = blocktankViewModel,
                            currencyViewModel = currencyViewModel,
                            activityListViewModel = activityListViewModel,
                            transferViewModel = transferViewModel,
                            settingsViewModel = settingsViewModel,
                            backupsViewModel = backupsViewModel,
                            modifier = Modifier.hazeSource(hazeState, zIndex = 0f)
                        )
                    }

                    AnimatedVisibility(
                        visible = !isAuthenticated,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        AuthCheckView(
                            showLogoOnPin = true,
                            appViewModel = appViewModel,
                            settingsViewModel = settingsViewModel,
                            onSuccess = { appViewModel.setIsAuthenticated(true) },
                        )
                    }

                    val showForgotPinSheet by appViewModel.showForgotPinSheet.collectAsStateWithLifecycle()
                    if (showForgotPinSheet) {
                        ForgotPinSheet(
                            onDismiss = { appViewModel.setShowForgotPin(false) },
                            onResetClick = { walletViewModel.wipeWallet() },
                        )
                    }

                    LaunchedEffect(appViewModel) {
                        appViewModel.mainScreenEffect.collect {
                            when (it) {
                                MainScreenEffect.WipeWallet -> walletViewModel.wipeWallet()
                                else -> Unit
                            }
                        }
                    }
                }

                val currentToast by appViewModel.currentToast.collectAsStateWithLifecycle()
                ToastOverlay(
                    toast = currentToast,
                    hazeState = hazeState,
                    onDismiss = { appViewModel.hideToast() },
                    onDragStart = { appViewModel.pauseToast() },
                    onDragEnd = { appViewModel.resumeToast() }
                )

                val transactionSheetDetails by appViewModel.transactionSheet.collectAsStateWithLifecycle()
                if (transactionSheetDetails != NewTransactionSheetDetails.EMPTY) {
                    NewTransactionSheet(
                        appViewModel = appViewModel,
                        currencyViewModel = currencyViewModel,
                        settingsViewModel = settingsViewModel,
                    )
                }

                SplashScreen(appViewModel.splashVisible)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.handleDeeplinkIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!settingsViewModel.notificationsGranted.value) {
            runCatching {
                stopService(Intent(this, LightningNodeService::class.java))
            }
        }
    }

    /**
     * Attempts to start the LightningNodeService if it's not already running.
     */
    private fun tryStartForegroundService() {
        runCatching {
            Logger.debug("Attempting to start LightningNodeService", context = "MainActivity")
            startForegroundService(Intent(this, LightningNodeService::class.java))
        }.onFailure { error ->
            Logger.error("Failed to start LightningNodeService", error, context = "MainActivity")
        }
    }
}

@Composable
private fun OnboardingNav(
    startupNavController: NavHostController,
    scope: CoroutineScope,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
) {
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
        composableWithDefaultTransitions<StartupRoutes.Intro> {
            IntroScreen(
                onStartClick = {
                    startupNavController.navigate(StartupRoutes.Slides())
                },
                onSkipClick = {
                    startupNavController.navigate(StartupRoutes.Slides(StartupRoutes.LAST_SLIDE_INDEX))
                },
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Slides> { navBackEntry ->
            val route = navBackEntry.toRoute<StartupRoutes.Slides>()
            val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()
            OnboardingSlidesScreen(
                currentTab = route.tab,
                isGeoBlocked = isGeoBlocked,
                onAdvancedSetupClick = { startupNavController.navigate(StartupRoutes.Advanced) },
                onCreateClick = {
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.setInitNodeLifecycleState()
                            walletViewModel.createWallet(bip39Passphrase = null)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                },
                onRestoreClick = {
                    startupNavController.navigate(
                        StartupRoutes.WarningMultipleDevices
                    )
                },
            )
        }
        composableWithDefaultTransitions<StartupRoutes.WarningMultipleDevices> {
            WarningMultipleDevicesScreen(
                onBackClick = {
                    startupNavController.popBackStack()
                },
                onConfirmClick = {
                    startupNavController.navigate(StartupRoutes.Restore)
                }
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Restore> {
            RestoreWalletScreen(
                onBackClick = { startupNavController.popBackStack() },
                onRestoreClick = { mnemonic, passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.restoreWallet(mnemonic, passphrase)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                }
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Advanced> {
            CreateWalletWithPassphraseScreen(
                onBackClick = { startupNavController.popBackStack() },
                onCreateClick = { passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                },
            )
        }
    }
}

private object StartupRoutes {
    const val LAST_SLIDE_INDEX = 4

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
