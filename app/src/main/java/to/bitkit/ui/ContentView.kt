package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.models.WidgetType
import to.bitkit.ui.components.AuthCheckScreen
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.onboarding.InitializingWalletView
import to.bitkit.ui.onboarding.WalletRestoreErrorView
import to.bitkit.ui.onboarding.WalletRestoreSuccessView
import to.bitkit.ui.screens.profile.CreateProfileScreen
import to.bitkit.ui.screens.profile.ProfileIntroScreen
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.scanner.SCAN_REQUEST_KEY
import to.bitkit.ui.screens.settings.DevSettingsScreen
import to.bitkit.ui.screens.settings.FeeSettingsScreen
import to.bitkit.ui.screens.shop.ShopIntroScreen
import to.bitkit.ui.screens.shop.shopDiscover.ShopDiscoverScreen
import to.bitkit.ui.screens.shop.shopWebView.ShopWebViewScreen
import to.bitkit.ui.screens.transfer.FundingAdvancedScreen
import to.bitkit.ui.screens.transfer.FundingScreen
import to.bitkit.ui.screens.transfer.LiquidityScreen
import to.bitkit.ui.screens.transfer.SavingsAdvancedScreen
import to.bitkit.ui.screens.transfer.SavingsAvailabilityScreen
import to.bitkit.ui.screens.transfer.SavingsConfirmScreen
import to.bitkit.ui.screens.transfer.SavingsIntroScreen
import to.bitkit.ui.screens.transfer.SavingsProgressScreen
import to.bitkit.ui.screens.transfer.SettingUpScreen
import to.bitkit.ui.screens.transfer.SpendingAdvancedScreen
import to.bitkit.ui.screens.transfer.SpendingAmountScreen
import to.bitkit.ui.screens.transfer.SpendingConfirmScreen
import to.bitkit.ui.screens.transfer.SpendingIntroScreen
import to.bitkit.ui.screens.transfer.TransferIntroScreen
import to.bitkit.ui.screens.transfer.external.ExternalAmountScreen
import to.bitkit.ui.screens.transfer.external.ExternalConfirmScreen
import to.bitkit.ui.screens.transfer.external.ExternalConnectionScreen
import to.bitkit.ui.screens.transfer.external.ExternalFeeCustomScreen
import to.bitkit.ui.screens.transfer.external.ExternalNodeViewModel
import to.bitkit.ui.screens.transfer.external.ExternalSuccessScreen
import to.bitkit.ui.screens.transfer.external.LnurlChannelScreen
import to.bitkit.ui.screens.wallets.HomeNav
import to.bitkit.ui.screens.wallets.activity.ActivityDetailScreen
import to.bitkit.ui.screens.wallets.activity.ActivityExploreScreen
import to.bitkit.ui.screens.wallets.activity.DateRangeSelectorSheet
import to.bitkit.ui.screens.wallets.activity.TagSelectorSheet
import to.bitkit.ui.screens.wallets.receive.ReceiveQrSheet
import to.bitkit.ui.screens.wallets.send.SendOptionsView
import to.bitkit.ui.screens.wallets.sheets.LnurlAuthSheet
import to.bitkit.ui.screens.wallets.suggestion.BuyIntroScreen
import to.bitkit.ui.screens.widgets.AddWidgetsScreen
import to.bitkit.ui.screens.widgets.WidgetsIntroScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksEditScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksPreviewScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksViewModel
import to.bitkit.ui.screens.widgets.calculator.CalculatorPreviewScreen
import to.bitkit.ui.screens.widgets.facts.FactsEditScreen
import to.bitkit.ui.screens.widgets.facts.FactsPreviewScreen
import to.bitkit.ui.screens.widgets.facts.FactsViewModel
import to.bitkit.ui.screens.widgets.headlines.HeadlinesEditScreen
import to.bitkit.ui.screens.widgets.headlines.HeadlinesPreviewScreen
import to.bitkit.ui.screens.widgets.headlines.HeadlinesViewModel
import to.bitkit.ui.screens.widgets.price.PriceEditScreen
import to.bitkit.ui.screens.widgets.price.PricePreviewScreen
import to.bitkit.ui.screens.widgets.price.PriceViewModel
import to.bitkit.ui.screens.widgets.weather.WeatherEditScreen
import to.bitkit.ui.screens.widgets.weather.WeatherPreviewScreen
import to.bitkit.ui.screens.widgets.weather.WeatherViewModel
import to.bitkit.ui.settings.AboutScreen
import to.bitkit.ui.settings.AdvancedSettingsScreen
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.CJitDetailScreen
import to.bitkit.ui.settings.ChannelOrdersScreen
import to.bitkit.ui.settings.LogDetailScreen
import to.bitkit.ui.settings.LogsScreen
import to.bitkit.ui.settings.OrderDetailScreen
import to.bitkit.ui.settings.SecuritySettingsScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.advanced.AddressViewerScreen
import to.bitkit.ui.settings.advanced.CoinSelectPreferenceScreen
import to.bitkit.ui.settings.advanced.ElectrumConfigScreen
import to.bitkit.ui.settings.advanced.RgsServerScreen
import to.bitkit.ui.settings.appStatus.AppStatusScreen
import to.bitkit.ui.settings.backups.BackupNavigationSheet
import to.bitkit.ui.settings.backups.BackupSheet
import to.bitkit.ui.settings.backups.ResetAndRestoreScreen
import to.bitkit.ui.settings.general.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.general.GeneralSettingsScreen
import to.bitkit.ui.settings.general.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.general.TagsSettingsScreen
import to.bitkit.ui.settings.general.WidgetsSettingsScreen
import to.bitkit.ui.settings.lightning.ChannelDetailScreen
import to.bitkit.ui.settings.lightning.CloseConnectionScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsViewModel
import to.bitkit.ui.settings.pin.ChangePinConfirmScreen
import to.bitkit.ui.settings.pin.ChangePinNewScreen
import to.bitkit.ui.settings.pin.ChangePinResultScreen
import to.bitkit.ui.settings.pin.ChangePinScreen
import to.bitkit.ui.settings.pin.DisablePinScreen
import to.bitkit.ui.settings.pin.PinNavigationSheet
import to.bitkit.ui.settings.quickPay.QuickPayIntroScreen
import to.bitkit.ui.settings.quickPay.QuickPaySettingsScreen
import to.bitkit.ui.settings.support.ReportIssueResultScreen
import to.bitkit.ui.settings.support.ReportIssueScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.settings.transactionSpeed.CustomFeeSettingsScreen
import to.bitkit.ui.settings.transactionSpeed.TransactionSpeedSettingsScreen
import to.bitkit.ui.utils.AutoReadClipboardHandler
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.screenSlideIn
import to.bitkit.ui.utils.screenSlideOut
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BackupsViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.MainScreenEffect
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

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
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Effects on app entering fg (ON_START) / bg (ON_STOP)
    DisposableEffect(lifecycle) {
        // TODO ADAPT THIS LOGIC TO WORK WITH LightningNodeService
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    try {
                        walletViewModel.start()
                    } catch (e: Throwable) {
                        Logger.error("Failed to start wallet", e)
                    }

                    val pendingTransaction = NewTransactionSheetDetails.load(context)
                    if (pendingTransaction != null) {
                        appViewModel.showNewTransactionSheet(pendingTransaction)
                        NewTransactionSheetDetails.clear(context)
                    }

                    currencyViewModel.triggerRefresh()
                    blocktankViewModel.refreshOrders()
                }

                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        walletViewModel.observeLdkWallet()
    }

    LaunchedEffect(Unit) { walletViewModel.handleHideBalanceOnOpen() }

    LaunchedEffect(appViewModel) {
        appViewModel.mainScreenEffect.collect {
            when (it) {
                is MainScreenEffect.Navigate -> navController.navigate(it.route)
                is MainScreenEffect.ProcessClipboardAutoRead -> {
                    val isOnHome = navController.currentDestination?.hasRoute<Routes.Home>() == true
                    if (!isOnHome) {
                        navController.navigateToHome()
                        delay(100) // Small delay to ensure navigation completes
                    }
                    appViewModel.onScanResult(it.data)
                }

                else -> Unit
            }
        }
    }

    val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
    val nodeLifecycleState = walletUiState.nodeLifecycleState

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
        // TODO ADAPT THIS LOGIC TO WORK WITH LightningNodeService
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
                isRestoring = restoreState.isRestoring(),
            )
        }
    } else if (restoreState is RestoreState.BackupRestoreCompleted) {
        WalletRestoreSuccessView(
            onContinue = { walletViewModel.onRestoreContinue() },
        )
    } else {
        val balance by walletViewModel.balanceState.collectAsStateWithLifecycle()
        val currencies by currencyViewModel.uiState.collectAsState()

        LaunchedEffect(balance) {
            // Anytime we receive a balance update, we should sync the payments to activity list
            activityListViewModel.syncLdkNodePayments()
        }

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
            LocalBalances provides balance,
            LocalCurrencies provides currencies,
        ) {
            AutoReadClipboardHandler()

            val currentSheet by appViewModel.currentSheet
            SheetHost(
                shouldExpand = currentSheet != null,
                onDismiss = { appViewModel.hideSheet() },
                sheets = {
                    when (val sheet = currentSheet) {
                        is BottomSheetType.Send -> {
                            SendOptionsView(
                                appViewModel = appViewModel,
                                walletViewModel = walletViewModel,
                                startDestination = sheet.route,
                                onComplete = { txSheet ->
                                    appViewModel.resetSendState()
                                    appViewModel.hideSheet()
                                    appViewModel.clearClipboardForAutoRead()
                                    txSheet?.let { appViewModel.showNewTransactionSheet(it) }
                                }
                            )
                        }

                        is BottomSheetType.Receive -> {
                            val walletUiState by walletViewModel.uiState.collectAsState()
                            ReceiveQrSheet(
                                walletState = walletUiState,
                                navigateToExternalConnection = {
                                    navController.navigate(Routes.ExternalConnection)
                                }
                            )
                        }

                        is BottomSheetType.ActivityDateRangeSelector -> DateRangeSelectorSheet()
                        is BottomSheetType.ActivityTagSelector -> TagSelectorSheet()

                        is BottomSheetType.PinSetup -> PinNavigationSheet(
                            onDismiss = { appViewModel.hideSheet() },
                        )

                        BottomSheetType.Backup -> BackupSheet(
                            onDismiss = { appViewModel.hideSheet() },
                            onBackupClick = {
                                appViewModel.hideSheet()
                                appViewModel.showSheet(BottomSheetType.BackupNavigation)
                            },
                            walletViewModel = walletViewModel
                        )

                        BottomSheetType.BackupNavigation -> BackupNavigationSheet(
                            onDismiss = { appViewModel.hideSheet() },
                        )

                        is BottomSheetType.LnurlAuth -> LnurlAuthSheet(sheet, appViewModel)

                        null -> Unit
                    }
                }
            ) {
                RootNavHost(
                    navController = navController,
                    walletViewModel = walletViewModel,
                    appViewModel = appViewModel,
                    activityListViewModel = activityListViewModel,
                    settingsViewModel = settingsViewModel,
                    currencyViewModel = currencyViewModel,
                    transferViewModel = transferViewModel,
                )
            }
        }
    }
}

@Composable
private fun RootNavHost(
    navController: NavHostController,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
    transferViewModel: TransferViewModel,
) {
    val scope = rememberCoroutineScope()

    NavHost(navController, startDestination = Routes.Home) {
        home(walletViewModel, appViewModel, activityListViewModel, settingsViewModel, navController)
        settings(navController, settingsViewModel)
        profile(navController, settingsViewModel)
        shop(navController, settingsViewModel, appViewModel)
        generalSettings(navController)
        advancedSettings(navController)
        aboutSettings(navController)
        transactionSpeedSettings(navController)
        securitySettings(navController)
        disablePin(navController)
        changePin(navController)
        changePinNew(navController)
        changePinConfirm(navController)
        changePinResult(navController)
        defaultUnitSettings(currencyViewModel, navController)
        localCurrencySettings(currencyViewModel, navController)
        backupSettings(navController)
        resetAndRestoreSettings(navController)
        channelOrdersSettings(navController)
        orderDetailSettings(navController)
        cjitDetailSettings(navController)
        lightningConnections(navController)
        activityItem(activityListViewModel, navController)
        qrScanner(appViewModel, navController)
        authCheck(navController)
        logs(navController)
        suggestions(navController)
        support(navController)
        widgets(navController, settingsViewModel, currencyViewModel)

        // TODO extract transferNavigation
        navigation<Routes.TransferRoot>(
            startDestination = Routes.TransferIntro,
        ) {
            composableWithDefaultTransitions<Routes.TransferIntro> {
                TransferIntroScreen(
                    onContinueClick = {
                        navController.navigateToTransferFunding()
                        settingsViewModel.setHasSeenTransferIntro(true)
                    },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsIntro> {
                SavingsIntroScreen(
                    onContinueClick = {
                        navController.navigate(Routes.SavingsAvailability)
                        settingsViewModel.setHasSeenSavingsIntro(true)
                    },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsAvailability> {
                SavingsAvailabilityScreen(
                    onBackClick = { navController.popBackStack() },
                    onCancelClick = { navController.navigateToHome() },
                    onContinueClick = { navController.navigate(Routes.SavingsConfirm) },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsConfirm> {
                SavingsConfirmScreen(
                    onConfirm = { navController.navigate(Routes.SavingsProgress) },
                    onAdvancedClick = { navController.navigate(Routes.SavingsAdvanced) },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsAdvanced> {
                SavingsAdvancedScreen(
                    onContinueClick = { navController.popBackStack<Routes.SavingsConfirm>(inclusive = false) },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsProgress> {
                SavingsProgressScreen(
                    onContinueClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                    onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingIntro> {
                SpendingIntroScreen(
                    onContinueClick = {
                        navController.navigate(Routes.SpendingAmount)
                        settingsViewModel.setHasSeenSpendingIntro(true)
                    },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingAmount> {
                SpendingAmountScreen(
                    viewModel = transferViewModel,
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                    onOrderCreated = { navController.navigate(Routes.SpendingConfirm) },
                    toastException = { appViewModel.toast(it) },
                    toast = { title, description ->
                        appViewModel.toast(
                            type = Toast.ToastType.ERROR,
                            title = title,
                            description = description
                        )
                    },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingConfirm> {
                SpendingConfirmScreen(
                    viewModel = transferViewModel,
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                    onLearnMoreClick = { navController.navigate(Routes.TransferLiquidity) },
                    onAdvancedClick = { navController.navigate(Routes.SpendingAdvanced) },
                    onConfirm = { navController.navigate(Routes.SettingUp) },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingAdvanced> {
                SpendingAdvancedScreen(
                    viewModel = transferViewModel,
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                    onOrderCreated = { navController.popBackStack<Routes.SpendingConfirm>(inclusive = false) },
                )
            }
            composableWithDefaultTransitions<Routes.TransferLiquidity> {
                LiquidityScreen(
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                    onContinueClick = { navController.popBackStack() }
                )
            }
            composableWithDefaultTransitions<Routes.SettingUp> {
                SettingUpScreen(
                    viewModel = transferViewModel,
                    onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                    onContinueClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                )
            }
            composableWithDefaultTransitions<Routes.Funding> {
                val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsState()
                FundingScreen(
                    onTransfer = {
                        if (!hasSeenSpendingIntro) {
                            navController.navigateToTransferSpendingIntro()
                        } else {
                            navController.navigateToTransferSpendingAmount()
                        }
                    },
                    onFund = {
                        scope.launch {
                            // TODO show receive sheet -> ReceiveAmount
                            navController.navigateToHome()
                            delay(500) // Wait for nav to actually finish
                            appViewModel.showSheet(BottomSheetType.Receive)
                        }
                    },
                    onAdvanced = { navController.navigate(Routes.FundingAdvanced) },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            composableWithDefaultTransitions<Routes.FundingAdvanced> {
                FundingAdvancedScreen(
                    onLnurl = { navController.navigateToScanner() },
                    onManual = { navController.navigate(Routes.ExternalNav) },
                    onBackClick = { navController.popBackStack() },
                    onCloseClick = { navController.navigateToHome() },
                )
            }
            navigation<Routes.ExternalNav>(
                startDestination = Routes.ExternalConnection(),
            ) {
                composableWithDefaultTransitions<Routes.ExternalConnection> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val route = it.toRoute<Routes.ExternalConnection>()
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalConnectionScreen(
                        route = route,
                        savedStateHandle = it.savedStateHandle,
                        viewModel = viewModel,
                        onNodeConnected = { navController.navigate(Routes.ExternalAmount) },
                        onScanClick = { navController.navigateToScanner(isCalledForResult = true) },
                        onBackClick = { navController.popBackStack() },
                        onCloseClick = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalAmount> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalAmountScreen(
                        viewModel = viewModel,
                        onContinue = { navController.navigate(Routes.ExternalConfirm) },
                        onBackClick = { navController.popBackStack() },
                        onCloseClick = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalConfirm> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalConfirmScreen(
                        viewModel = viewModel,
                        onConfirm = {
                            walletViewModel.refreshState()
                            navController.navigate(Routes.ExternalSuccess)
                        },
                        onNetworkFeeClick = { navController.navigate(Routes.ExternalFeeCustom) },
                        onBackClick = { navController.popBackStack() },
                        onCloseClick = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.LnurlChannel> {
                    LnurlChannelScreen(
                        route = it.toRoute<Routes.LnurlChannel>(),
                        onConnected = { navController.navigate(Routes.ExternalSuccess) },
                        onBack = { navController.popBackStack() },
                        onClose = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalSuccess> {
                    ExternalSuccessScreen(
                        onContinue = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                        onClose = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalFeeCustom> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalFeeCustomScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onClose = { navController.navigateToHome() },
                    )
                }
            }
        }
    }
}

// region destinations
private fun NavGraphBuilder.home(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
) {
    composable<Routes.Home> {
        HomeNav(
            walletViewModel = walletViewModel,
            appViewModel = appViewModel,
            activityListViewModel = activityListViewModel,
            settingsViewModel = settingsViewModel,
            rootNavController = navController,
        )
    }
}

private fun NavGraphBuilder.settings(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    composableWithDefaultTransitions<Routes.Settings> {
        SettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.QuickPayIntro> {
        QuickPayIntroScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
            onContinue = {
                settingsViewModel.setQuickPayIntroSeen(true)
                navController.navigate(Routes.QuickPaySettings)
            }
        )
    }
    composableWithDefaultTransitions<Routes.QuickPaySettings> {
        QuickPaySettingsScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
        )
    }
    composableWithDefaultTransitions<Routes.DevSettings> {
        DevSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.FeeSettings> {
        FeeSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.RegtestSettings> {
        BlocktankRegtestScreen(navController)
    }
}

private fun NavGraphBuilder.profile(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    composableWithDefaultTransitions<Routes.ProfileIntro> {
        ProfileIntroScreen(
            onClose = { navController.navigateToHome() },
            onContinue = {
                settingsViewModel.setHasSeenProfileIntro(true)
                navController.navigate(Routes.CreateProfile)
            }
        )
    }
    composableWithDefaultTransitions<Routes.CreateProfile> {
        CreateProfileScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
        )
    }
}

private fun NavGraphBuilder.shop(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    appViewModel: AppViewModel,
) {
    composableWithDefaultTransitions<Routes.ShopIntro> {
        ShopIntroScreen(
            onClose = { navController.navigateToHome() },
            onContinue = {
                settingsViewModel.setHasSeenShopIntro(true)
                navController.navigate(Routes.ShopDiscover)
            }
        )
    }
    composableWithDefaultTransitions<Routes.ShopDiscover> {
        ShopDiscoverScreen(
            onClose = { navController.navigateToHome() },
            onBack = { navController.popBackStack() },
            navigateWebView = { page, title ->
                navController.navigate(Routes.ShopWebView(page = page, title = title))
            }
        )
    }
    composableWithDefaultTransitions<Routes.ShopWebView> {
        ShopWebViewScreen(
            onClose = { navController.navigateToHome() },
            onBack = { navController.popBackStack() },
            page = it.toRoute<Routes.ShopWebView>().page,
            title = it.toRoute<Routes.ShopWebView>().title,
            onPaymentIntent = { data ->
                appViewModel.onScanResult(data)
            }
        )
    }
}

private fun NavGraphBuilder.generalSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.GeneralSettings> {
        GeneralSettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.WidgetsSettings> {
        WidgetsSettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.TagsSettings> {
        TagsSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.advancedSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.AdvancedSettings> {
        AdvancedSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.CoinSelectPreference> {
        CoinSelectPreferenceScreen(navController)
    }
    composableWithDefaultTransitions<Routes.ElectrumConfig> {
        ElectrumConfigScreen(it.savedStateHandle, navController)
    }
    composableWithDefaultTransitions<Routes.RgsServer> {
        RgsServerScreen(it.savedStateHandle, navController)
    }
    composableWithDefaultTransitions<Routes.AddressViewer> {
        AddressViewerScreen(navController)
    }
    composableWithDefaultTransitions<Routes.NodeInfo> {
        NodeInfoScreen(navController)
    }
}

private fun NavGraphBuilder.aboutSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.AboutSettings> {
        AboutScreen(
            onBack = {
                navController.popBackStack()
            },
            onClose = {
                navController.navigateToHome()
            }
        )
    }
}

private fun NavGraphBuilder.transactionSpeedSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.TransactionSpeedSettings> {
        TransactionSpeedSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.CustomFeeSettings> {
        CustomFeeSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.securitySettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.SecuritySettings> {
        SecuritySettingsScreen(navController = navController)
    }
}

private fun NavGraphBuilder.disablePin(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.DisablePin> {
        DisablePinScreen(navController)
    }
}

private fun NavGraphBuilder.changePin(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.ChangePin> {
        ChangePinScreen(navController)
    }
}

private fun NavGraphBuilder.changePinNew(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.ChangePinNew> {
        ChangePinNewScreen(navController)
    }
}

private fun NavGraphBuilder.changePinConfirm(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.ChangePinConfirm> {
        val route = it.toRoute<Routes.ChangePinConfirm>()
        ChangePinConfirmScreen(
            newPin = route.newPin,
            navController = navController,
        )
    }
}

private fun NavGraphBuilder.changePinResult(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.ChangePinResult> {
        ChangePinResultScreen(navController)
    }
}

private fun NavGraphBuilder.defaultUnitSettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.DefaultUnitSettings> {
        DefaultUnitSettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.localCurrencySettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.LocalCurrencySettings> {
        LocalCurrencySettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.backupSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.BackupSettings> {
        BackupSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.resetAndRestoreSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.ResetAndRestoreSettings> {
        ResetAndRestoreScreen(navController)
    }
}

private fun NavGraphBuilder.channelOrdersSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.ChannelOrdersSettings> {
        ChannelOrdersScreen(
            onBackClick = { navController.popBackStack() },
            onOrderItemClick = { navController.navigateToOrderDetail(it) },
            onCjitItemClick = { navController.navigateToCjitDetail(it) },
        )
    }
}

private fun NavGraphBuilder.orderDetailSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.OrderDetail> {
        OrderDetailScreen(
            orderItem = it.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.cjitDetailSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.CjitDetail> {
        CJitDetailScreen(
            cjitItem = it.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.lightningConnections(
    navController: NavHostController,
) {
    navigation<Routes.ConnectionsNav>(
        startDestination = Routes.LightningConnections,
    ) {
        composableWithDefaultTransitions<Routes.LightningConnections> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ConnectionsNav) }
            val viewModel = hiltViewModel<LightningConnectionsViewModel>(parentEntry)
            LightningConnectionsScreen(navController, viewModel)
        }
        composableWithDefaultTransitions<Routes.ChannelDetail> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ConnectionsNav) }
            val viewModel = hiltViewModel<LightningConnectionsViewModel>(parentEntry)
            ChannelDetailScreen(
                navController = navController,
                viewModel = viewModel,
            )
        }
        composableWithDefaultTransitions<Routes.CloseConnection> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ConnectionsNav) }
            val viewModel = hiltViewModel<LightningConnectionsViewModel>(parentEntry)
            CloseConnectionScreen(
                navController = navController,
                viewModel = viewModel,
            )
        }
    }
}

private fun NavGraphBuilder.activityItem(
    activityListViewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.ActivityDetail> {
        ActivityDetailScreen(
            listViewModel = activityListViewModel,
            route = it.toRoute(),
            onExploreClick = { id -> navController.navigateToActivityExplore(id) },
            onBackClick = { navController.popBackStack() },
            onCloseClick = { navController.navigateToHome() },
        )
    }
    composableWithDefaultTransitions<Routes.ActivityExplore> {
        ActivityExploreScreen(
            listViewModel = activityListViewModel,
            route = it.toRoute(),
            onBackClick = { navController.popBackStack() },
            onCloseClick = { navController.navigateToHome() },
        )
    }
}

private fun NavGraphBuilder.qrScanner(
    appViewModel: AppViewModel,
    navController: NavHostController,
) {
    composable<Routes.QrScanner>(
        enterTransition = { screenSlideIn },
        exitTransition = { screenSlideOut },
    ) {
        QrScanningScreen(navController = navController) { qrCode ->
            appViewModel.onScanResult(
                data = qrCode,
                delayMs = 650 // slight delay for nav transition before showing send sheet
            )
        }
    }
}

private fun NavGraphBuilder.authCheck(
    navController: NavHostController,
) {
    composable<Routes.AuthCheck> {
        val route = it.toRoute<Routes.AuthCheck>()
        AuthCheckScreen(
            route = route,
            navController = navController,
        )
    }
}

private fun NavGraphBuilder.logs(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.Logs> {
        LogsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.LogDetail> {
        val route = it.toRoute<Routes.LogDetail>()
        LogDetailScreen(
            navController = navController,
            fileName = route.fileName,
        )
    }
}

private fun NavGraphBuilder.suggestions(
    navController: NavHostController,
) {
    composable<Routes.BuyIntro>(
        enterTransition = { screenSlideIn },
        exitTransition = { screenSlideOut },
    ) {
        BuyIntroScreen(
            onBackClick = { navController.popBackStack() }
        )
    }
}

private fun NavGraphBuilder.support(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.Support> {
        SupportScreen(navController)
    }

    composableWithDefaultTransitions<Routes.AppStatus> {
        AppStatusScreen(navController)
    }

    composableWithDefaultTransitions<Routes.ReportIssue> {
        ReportIssueScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
            navigateResultScreen = { isSuccess ->
                if (isSuccess) {
                    navController.navigate(Routes.ReportIssueSuccess)
                } else {
                    navController.navigate(Routes.ReportIssueFailure)
                }
            }
        )
    }

    composableWithDefaultTransitions<Routes.ReportIssueSuccess> {
        ReportIssueResultScreen(
            isSuccess = true,
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
        )
    }

    composableWithDefaultTransitions<Routes.ReportIssueFailure> {
        ReportIssueResultScreen(
            isSuccess = false,
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
        )
    }
}

private fun NavGraphBuilder.widgets(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
) {
    composableWithDefaultTransitions<Routes.WidgetsIntro> {
        WidgetsIntroScreen(
            onClose = { navController.navigateToHome() },
            onContinue = {
                settingsViewModel.setHasSeenWidgetsIntro(true)
                navController.navigate(Routes.AddWidget)
            }
        )
    }
    composableWithDefaultTransitions<Routes.AddWidget> {
        AddWidgetsScreen(
            onClose = { navController.navigateToHome() },
            onWidgetSelected = { widgetType ->
                when (widgetType) {
                    WidgetType.BLOCK -> navController.navigate(Routes.BlocksPreview)
                    WidgetType.CALCULATOR -> navController.navigate(Routes.CalculatorPreview)
                    WidgetType.FACTS -> navController.navigate(Routes.FactsPreview)
                    WidgetType.NEWS -> navController.navigate(Routes.HeadlinesPreview)
                    WidgetType.PRICE -> navController.navigate(Routes.PricePreview)
                    WidgetType.WEATHER -> navController.navigate(Routes.WeatherPreview)
                }
            },
            fiatSymbol = currencyViewModel.getCurrencySymbol()
        )
    }
    composableWithDefaultTransitions<Routes.CalculatorPreview> {
        CalculatorPreviewScreen(
            onClose = { navController.navigateToHome() },
            onBack = { navController.popBackStack() },
            currencyViewModel = currencyViewModel
        )
    }
    navigation<Routes.Headlines>(
        startDestination = Routes.HeadlinesPreview
    ) {
        composableWithDefaultTransitions<Routes.HeadlinesPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Headlines) }
            val viewModel = hiltViewModel<HeadlinesViewModel>(parentEntry)

            HeadlinesPreviewScreen(
                headlinesViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigate(Routes.HeadlinesEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.HeadlinesEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Headlines) }
            val viewModel = hiltViewModel<HeadlinesViewModel>(parentEntry)

            HeadlinesEditScreen(
                headlinesViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigatePreview = {
                    navController.navigate(Routes.HeadlinesPreview)
                }
            )
        }
    }
    navigation<Routes.Facts>(
        startDestination = Routes.FactsPreview
    ) {
        composableWithDefaultTransitions<Routes.FactsPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Facts) }
            val viewModel = hiltViewModel<FactsViewModel>(parentEntry)

            FactsPreviewScreen(
                factsViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigate(Routes.FactsEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.FactsEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Facts) }
            val viewModel = hiltViewModel<FactsViewModel>(parentEntry)

            FactsEditScreen(
                factsViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigate(Routes.FactsPreview) }
            )
        }
    }
    navigation<Routes.Blocks>(
        startDestination = Routes.BlocksPreview
    ) {
        composableWithDefaultTransitions<Routes.BlocksPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Blocks) }
            val viewModel = hiltViewModel<BlocksViewModel>(parentEntry)

            BlocksPreviewScreen(
                blocksViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigate(Routes.BlocksEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.BlocksEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Blocks) }
            val viewModel = hiltViewModel<BlocksViewModel>(parentEntry)

            BlocksEditScreen(
                blocksViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigate(Routes.BlocksPreview) }
            )
        }
    }
    navigation<Routes.Weather>(
        startDestination = Routes.WeatherPreview
    ) {
        composableWithDefaultTransitions<Routes.WeatherPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Weather) }
            val viewModel = hiltViewModel<WeatherViewModel>(parentEntry)

            WeatherPreviewScreen(
                weatherViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigate(Routes.WeatherEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.WeatherEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Weather) }
            val viewModel = hiltViewModel<WeatherViewModel>(parentEntry)

            WeatherEditScreen(
                weatherViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigate(Routes.WeatherPreview) }
            )
        }
    }
    navigation<Routes.Price>(
        startDestination = Routes.PricePreview
    ) {
        composableWithDefaultTransitions<Routes.PricePreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Price) }
            val viewModel = hiltViewModel<PriceViewModel>(parentEntry)

            PricePreviewScreen(
                priceViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigate(Routes.PriceEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.PriceEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Price) }
            val viewModel = hiltViewModel<PriceViewModel>(parentEntry)
            PriceEditScreen(
                viewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigate(Routes.PricePreview) }
            )
        }
    }
}

// endregion

// region events
fun NavController.navigateToHome() {
    val popped = popBackStack<Routes.Home>(inclusive = false)
    if (!popped) {
        navigate(Routes.Home) {
            popUpTo(graph.startDestinationId)
            launchSingleTop = true
        }
    }
}

fun NavController.navigateToSettings() = navigate(
    route = Routes.Settings,
)

fun NavController.navigateToGeneralSettings() = navigate(
    route = Routes.GeneralSettings,
)

fun NavController.navigateToSecuritySettings() = navigate(
    route = Routes.SecuritySettings,
)

fun NavController.navigateToDisablePin() = navigate(
    route = Routes.DisablePin,
)

fun NavController.navigateToChangePin() = navigate(
    route = Routes.ChangePin,
)

fun NavController.navigateToChangePinNew() = navigate(
    route = Routes.ChangePinNew,
)

fun NavController.navigateToChangePinConfirm(newPin: String) = navigate(
    route = Routes.ChangePinConfirm(newPin),
)

fun NavController.navigateToChangePinResult() = navigate(
    route = Routes.ChangePinResult,
)

fun NavController.navigateToAuthCheck(
    showLogoOnPin: Boolean = false,
    requirePin: Boolean = false,
    requireBiometrics: Boolean = false,
    onSuccessActionId: String,
    navOptions: NavOptions? = null,
) = navigate(
    route = Routes.AuthCheck(
        showLogoOnPin = showLogoOnPin,
        requirePin = requirePin,
        requireBiometrics = requireBiometrics,
        onSuccessActionId = onSuccessActionId,
    ),
    navOptions = navOptions,
)

fun NavController.navigateToDefaultUnitSettings() = navigate(
    route = Routes.DefaultUnitSettings,
)

fun NavController.navigateToLocalCurrencySettings() = navigate(
    route = Routes.LocalCurrencySettings,
)

fun NavController.navigateToBackupSettings() = navigate(
    route = Routes.BackupSettings,
)

fun NavController.navigateToOrderDetail(id: String) = navigate(
    route = Routes.OrderDetail(id),
)

fun NavController.navigateToCjitDetail(id: String) = navigate(
    route = Routes.CjitDetail(id),
)

fun NavController.navigateToDevSettings() = navigate(
    route = Routes.DevSettings,
)

fun NavController.navigateToTransferSavingsIntro() = navigate(
    route = Routes.SavingsIntro,
)

fun NavController.navigateToTransferSavingsAvailability() = navigate(
    route = Routes.SavingsAvailability,
)

fun NavController.navigateToTransferSpendingIntro() = navigate(
    route = Routes.SpendingIntro,
)

fun NavController.navigateToTransferSpendingAmount() = navigate(
    route = Routes.SpendingAmount,
)

fun NavController.navigateToTransferIntro() = navigate(
    route = Routes.TransferIntro,
)

fun NavController.navigateToTransferFunding() = navigate(
    route = Routes.Funding,
)

fun NavController.navigateToActivityItem(id: String) = navigate(
    route = Routes.ActivityDetail(id),
)

fun NavController.navigateToActivityExplore(id: String) = navigate(
    route = Routes.ActivityExplore(id),
)

fun NavController.navigateToScanner(isCalledForResult: Boolean = false) {
    if (isCalledForResult) {
        currentBackStackEntry?.savedStateHandle?.set(SCAN_REQUEST_KEY, true)
    }
    navigate(Routes.QrScanner)
}

fun NavController.navigateToLogDetail(fileName: String) = navigate(
    route = Routes.LogDetail(fileName),
)

fun NavController.navigateToTransactionSpeedSettings() = navigate(
    route = Routes.TransactionSpeedSettings,
)

fun NavController.navigateToCustomFeeSettings() = navigate(
    route = Routes.CustomFeeSettings,
)

fun NavController.navigateToWidgetsSettings() = navigate(
    route = Routes.WidgetsSettings,
)

fun NavController.navigateToQuickPaySettings() = navigate(
    route = Routes.QuickPaySettings,
)

fun NavController.navigateToTagsSettings() = navigate(
    route = Routes.TagsSettings,
)

fun NavController.navigateToAdvancedSettings() = navigate(
    route = Routes.AdvancedSettings,
)

fun NavController.navigateToAboutSettings() = navigate(
    route = Routes.AboutSettings,
)
// endregion

sealed interface Routes {
    @Serializable
    data object Home : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data object NodeInfo : Routes

    @Serializable
    data object GeneralSettings : Routes

    @Serializable
    data object TransactionSpeedSettings : Routes

    @Serializable
    data object WidgetsSettings : Routes

    @Serializable
    data object TagsSettings : Routes

    @Serializable
    data object AdvancedSettings : Routes

    @Serializable
    data object CoinSelectPreference : Routes

    @Serializable
    data object ElectrumConfig : Routes

    @Serializable
    data object RgsServer : Routes

    @Serializable
    data object AddressViewer : Routes

    @Serializable
    data object AboutSettings : Routes

    @Serializable
    data object CustomFeeSettings : Routes

    @Serializable
    data object SecuritySettings : Routes

    @Serializable
    data object DisablePin : Routes

    @Serializable
    data object ChangePin : Routes

    @Serializable
    data object ChangePinNew : Routes

    @Serializable
    data class ChangePinConfirm(val newPin: String) : Routes

    @Serializable
    data object ChangePinResult : Routes

    @Serializable
    data class AuthCheck(
        val showLogoOnPin: Boolean = false,
        val requirePin: Boolean = false,
        val requireBiometrics: Boolean = false,
        val onSuccessActionId: String,
    ) : Routes

    @Serializable
    data object DefaultUnitSettings : Routes

    @Serializable
    data object LocalCurrencySettings : Routes

    @Serializable
    data object BackupSettings : Routes

    @Serializable
    data object ResetAndRestoreSettings : Routes

    @Serializable
    data object ChannelOrdersSettings : Routes

    @Serializable
    data object Logs : Routes

    @Serializable
    data class LogDetail(val fileName: String) : Routes

    @Serializable
    data class OrderDetail(val id: String) : Routes

    @Serializable
    data class CjitDetail(val id: String) : Routes

    @Serializable
    data object ConnectionsNav : Routes

    @Serializable
    data object LightningConnections : Routes

    @Serializable
    data object ChannelDetail : Routes

    @Serializable
    data object CloseConnection : Routes

    @Serializable
    data object DevSettings : Routes

    @Serializable
    data object FeeSettings : Routes

    @Serializable
    data object RegtestSettings : Routes

    @Serializable
    data object TransferRoot : Routes

    @Serializable
    data object TransferIntro : Routes

    @Serializable
    data object SpendingIntro : Routes

    @Serializable
    data object SpendingAmount : Routes

    @Serializable
    data object SpendingConfirm : Routes

    @Serializable
    data object SpendingAdvanced : Routes

    @Serializable
    data object TransferLiquidity : Routes

    @Serializable
    data object SettingUp : Routes

    @Serializable
    data object SavingsIntro : Routes

    @Serializable
    data object SavingsAvailability : Routes

    @Serializable
    data object SavingsConfirm : Routes

    @Serializable
    data object SavingsAdvanced : Routes

    @Serializable
    data object SavingsProgress : Routes

    @Serializable
    data object Funding : Routes

    @Serializable
    data object FundingAdvanced : Routes

    @Serializable
    data object ExternalNav : Routes

    @Serializable
    data class ExternalConnection(val scannedNodeUri: String? = null) : Routes

    @Serializable
    data object ExternalAmount : Routes

    @Serializable
    data object ExternalConfirm : Routes

    @Serializable
    data object ExternalSuccess : Routes

    @Serializable
    data object ExternalFeeCustom : Routes

    @Serializable
    data class LnurlChannel(val uri: String, val callback: String, val k1: String) : Routes

    @Serializable
    data class ActivityDetail(val id: String) : Routes

    @Serializable
    data class ActivityExplore(val id: String) : Routes

    @Serializable
    data object QrScanner : Routes

    @Serializable
    data object BuyIntro : Routes

    @Serializable
    data object Support : Routes

    @Serializable
    data object ReportIssue : Routes

    @Serializable
    data object ReportIssueSuccess : Routes

    @Serializable
    data object ReportIssueFailure : Routes

    @Serializable
    data object QuickPayIntro : Routes

    @Serializable
    data object QuickPaySettings : Routes

    @Serializable
    data object ProfileIntro : Routes

    @Serializable
    data object CreateProfile : Routes

    @Serializable
    data object ShopIntro : Routes

    @Serializable
    data object ShopDiscover : Routes

    @Serializable
    data class ShopWebView(val page: String, val title: String) : Routes

    @Serializable
    data object WidgetsIntro : Routes

    @Serializable
    data object AddWidget : Routes

    @Serializable
    data object Headlines : Routes

    @Serializable
    data object HeadlinesPreview : Routes

    @Serializable
    data object HeadlinesEdit : Routes

    @Serializable
    data object Facts : Routes

    @Serializable
    data object FactsPreview : Routes

    @Serializable
    data object FactsEdit : Routes

    @Serializable
    data object Blocks : Routes

    @Serializable
    data object BlocksPreview : Routes

    @Serializable
    data object BlocksEdit : Routes

    @Serializable
    data object Weather : Routes

    @Serializable
    data object WeatherPreview : Routes

    @Serializable
    data object WeatherEdit : Routes

    @Serializable
    data object Price : Routes

    @Serializable
    data object PricePreview : Routes

    @Serializable
    data object PriceEdit : Routes

    @Serializable
    data object CalculatorPreview : Routes

    @Serializable
    data object AppStatus : Routes
}
