package to.bitkit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import to.bitkit.models.NodeLifecycleState
import to.bitkit.ui.components.DrawerMenu
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.nav.MS_NAV_DELAY
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.nav.SheetSceneStrategy
import to.bitkit.ui.nav.Transitions
import to.bitkit.ui.nav.entries.homeEntries
import to.bitkit.ui.nav.entries.settingsEntries
import to.bitkit.ui.nav.entries.sheetEntries
import to.bitkit.ui.nav.entries.transferEntries
import to.bitkit.ui.nav.entries.widgetEntries
import to.bitkit.ui.onboarding.InitializingWalletView
import to.bitkit.ui.onboarding.WalletRestoreErrorView
import to.bitkit.ui.onboarding.WalletRestoreSuccessView
import to.bitkit.ui.settings.lightning.LightningConnectionsViewModel
import to.bitkit.ui.utils.AutoReadClipboardHandler
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BackupsViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.MainScreenEffect
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.SendEffect
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun ContentView(
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    blocktankViewModel: BlocktankViewModel,
    currencyViewModel: CurrencyViewModel,
    activityListViewModel: ActivityListViewModel,
    transferViewModel: TransferViewModel,
    settingsViewModel: SettingsViewModel,
    backupsViewModel: BackupsViewModel,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(Routes.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val lightningConnectionsViewModel = hiltViewModel<LightningConnectionsViewModel>()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val walletUiState by walletViewModel.walletState.collectAsStateWithLifecycle()
    val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()
    val nodeLifecycleState = lightningState.nodeLifecycleState

    val isRecoveryMode by walletViewModel.isRecoveryMode.collectAsStateWithLifecycle()
    val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
    val walletExists = walletUiState.walletExists

    // Effects on app entering fg (ON_START) / bg (ON_STOP)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (walletExists && !isRecoveryMode) {
                        walletViewModel.start()
                    }

                    appViewModel.consumePaymentReceivedInBackground()

                    currencyViewModel.triggerRefresh()
                    blocktankViewModel.refreshOrders()
                }

                Lifecycle.Event.ON_STOP -> {
                    if (walletExists && !isRecoveryMode && !notificationsGranted) {
                        // App backgrounded without notification permission - stop node
                        walletViewModel.stop()
                    }
                    // If notificationsGranted=true, service keeps node running
                }

                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) { walletViewModel.handleHideBalanceOnOpen() }

    LaunchedEffect(appViewModel) {
        appViewModel.mainScreenEffect.collect {
            when (it) {
                is MainScreenEffect.Navigate -> navigator.navigate(it.route)
                is MainScreenEffect.ProcessClipboardAutoRead -> {
                    if (!navigator.isAtHome()) {
                        navigator.navigateToHome()
                        delay(MS_NAV_DELAY)
                    }
                    appViewModel.onScanResult(it.data)
                }

                else -> Unit
            }
        }
    }

    // Handle Send flow navigation effects
    LaunchedEffect(appViewModel, navigator) {
        appViewModel.sendEffect.collect { effect ->
            when (effect) {
                is SendEffect.NavigateToAddress -> navigator.navigate(Routes.Send.Address)
                is SendEffect.NavigateToAmount -> navigator.navigate(Routes.Send.Amount())
                is SendEffect.NavigateToScan -> navigator.navigate(Routes.Send.QrScanner)
                is SendEffect.NavigateToCoinSelection -> navigator.navigate(Routes.Send.CoinSelection)
                is SendEffect.NavigateToConfirm -> navigator.navigate(Routes.Send.Confirm)
                is SendEffect.NavigateToQuickPay -> navigator.navigate(Routes.Send.QuickPay)
                is SendEffect.NavigateToWithdrawConfirm -> navigator.navigate(Routes.Send.WithdrawConfirm)
                is SendEffect.NavigateToWithdrawError -> navigator.navigate(Routes.Send.WithdrawError)
                is SendEffect.NavigateToFee -> navigator.navigate(Routes.Send.FeeRate)
                is SendEffect.NavigateToFeeCustom -> navigator.navigate(Routes.Send.FeeCustom)
                is SendEffect.PaymentSuccess -> {
                    appViewModel.clearClipboardForAutoRead()
                    navigator.navigate(Routes.Send.Success)
                }

                is SendEffect.PopBack -> navigator.popBackTo(effect.route)
            }
        }
    }

    var walletIsInitializing by remember { mutableStateOf(nodeLifecycleState == NodeLifecycleState.Initializing) }
    var walletInitShouldFinish by remember { mutableStateOf(false) }

    // React to nodeLifecycleState changes
    LaunchedEffect(nodeLifecycleState) {
        when (nodeLifecycleState) {
            NodeLifecycleState.Initializing -> {
                walletIsInitializing = true
            }

            NodeLifecycleState.Running -> {
                walletInitShouldFinish = true
            }

            is NodeLifecycleState.ErrorStarting -> {
                walletInitShouldFinish = true
            }

            else -> Unit
        }
    }

    val restoreState = walletViewModel.restoreState
    var restoreRetryCount by remember { mutableIntStateOf(0) }

    if (walletIsInitializing) {
        if (nodeLifecycleState is NodeLifecycleState.ErrorStarting) {
            WalletRestoreErrorView(
                retryCount = restoreRetryCount,
                onRetry = {
                    restoreRetryCount++
                    walletViewModel.setInitNodeLifecycleState()
                    walletViewModel.start()
                },
                onProceedWithoutRestore = {
                    walletViewModel.proceedWithoutRestore(
                        onDone = {
                            walletIsInitializing = false
                        }
                    )
                },
            )
        } else {
            // wallet is being created or restored
            InitializingWalletView(
                shouldFinish = walletInitShouldFinish,
                onComplete = {
                    Logger.debug("Wallet finished initializing but node state is $nodeLifecycleState")

                    if (nodeLifecycleState == NodeLifecycleState.Running) {
                        walletIsInitializing = false
                    }
                },
                isRestoring = restoreState.isOngoing(),
            )
        }
        return
    } else if (restoreState is RestoreState.Completed) {
        WalletRestoreSuccessView(
            onContinue = { walletViewModel.onRestoreContinue() },
        )
        return
    }

    val balance by walletViewModel.balanceState.collectAsStateWithLifecycle()
    val currencies by currencyViewModel.uiState.collectAsState()

    // Keep backups in sync
    LaunchedEffect(backupsViewModel) { backupsViewModel.observeAndSyncBackups() }

    CompositionLocalProvider(
        LocalAppViewModel provides appViewModel,
        LocalWalletViewModel provides walletViewModel,
        LocalBlocktankViewModel provides blocktankViewModel,
        LocalCurrencyViewModel provides currencyViewModel,
        LocalActivityListViewModel provides activityListViewModel,
        LocalTransferViewModel provides transferViewModel,
        LocalSettingsViewModel provides settingsViewModel,
        LocalBackupsViewModel provides backupsViewModel,
        LocalDrawerState provides drawerState,
        LocalBalances provides balance,
        LocalCurrencies provides currencies,
    ) {
        val hasSeenWidgetsIntro by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
        val hasSeenShopIntro by settingsViewModel.hasSeenShopIntro.collectAsStateWithLifecycle()

        AutoReadClipboardHandler()

        Box(modifier = modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    sceneStrategy = SheetSceneStrategy<NavKey>(),
                    transitionSpec = Transitions.screenDefault,
                    popTransitionSpec = Transitions.screenDefaultPop,
                    predictivePopTransitionSpec = Transitions.screenDefaultPredictivePop,
                    entryProvider = entryProvider {
                        homeEntries(
                            navigator = navigator,
                            drawerState = drawerState,
                            walletViewModel = walletViewModel,
                            appViewModel = appViewModel,
                            activityListViewModel = activityListViewModel,
                            settingsViewModel = settingsViewModel,
                        )

                        settingsEntries(
                            navigator = navigator,
                            appViewModel = appViewModel,
                            settingsViewModel = settingsViewModel,
                            currencyViewModel = currencyViewModel,
                            lightningConnectionsViewModel = lightningConnectionsViewModel,
                        )

                        transferEntries(
                            navigator = navigator,
                            appViewModel = appViewModel,
                            walletViewModel = walletViewModel,
                            transferViewModel = transferViewModel,
                            settingsViewModel = settingsViewModel,
                        )

                        widgetEntries(
                            navigator = navigator,
                            currencyViewModel = currencyViewModel,
                            settingsViewModel = settingsViewModel,
                        )

                        sheetEntries(
                            navigator = navigator,
                            appViewModel = appViewModel,
                            walletViewModel = walletViewModel,
                            activityListViewModel = activityListViewModel,
                            transferViewModel = transferViewModel,
                        )
                    }
                )

                AnimatedVisibility(
                    visible = navigator.shouldShowTabBar(),
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    TabBar(
                        onSendClick = { navigator.navigate(Routes.Send.Recipient) },
                        onReceiveClick = { navigator.navigate(Routes.Receive.Qr) },
                        onScanClick = { navigator.navigate(Routes.QrScanner) },
                    )
                }
            }

            DrawerMenu(
                drawerState = drawerState,
                navigator = navigator,
                hasSeenWidgetsIntro = hasSeenWidgetsIntro,
                hasSeenShopIntro = hasSeenShopIntro,
            )
        }
    }
}
