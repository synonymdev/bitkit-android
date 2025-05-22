package to.bitkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NodeLifecycleState
import to.bitkit.ui.components.AuthCheckScreen
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.onboarding.InitializingWalletView
import to.bitkit.ui.onboarding.WalletInitResult
import to.bitkit.ui.onboarding.WalletInitResultView
import to.bitkit.ui.screens.DevSettingsScreen
import to.bitkit.ui.screens.scanner.QrScanningScreen
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
import to.bitkit.ui.screens.transfer.external.ExternalSuccessScreen
import to.bitkit.ui.screens.wallets.HomeScreen
import to.bitkit.ui.screens.wallets.activity.ActivityDetailScreen
import to.bitkit.ui.screens.wallets.activity.ActivityExploreScreen
import to.bitkit.ui.screens.wallets.suggestion.BuyIntroScreen
import to.bitkit.ui.settings.AboutScreen
import to.bitkit.ui.settings.AdvancedSettingsScreen
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.BlocktankRegtestViewModel
import to.bitkit.ui.settings.CJitDetailScreen
import to.bitkit.ui.settings.ChannelOrdersScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.LogDetailScreen
import to.bitkit.ui.settings.LogsScreen
import to.bitkit.ui.settings.OrderDetailScreen
import to.bitkit.ui.settings.SecuritySettingsScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.backups.BackupWalletScreen
import to.bitkit.ui.settings.backups.RestoreWalletScreen
import to.bitkit.ui.settings.general.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.general.GeneralSettingsScreen
import to.bitkit.ui.settings.general.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.general.QuickPaySettingsScreen
import to.bitkit.ui.settings.general.TagsSettingsScreen
import to.bitkit.ui.settings.general.WidgetsSettingsScreen
import to.bitkit.ui.settings.pin.ChangePinConfirmScreen
import to.bitkit.ui.settings.pin.ChangePinNewScreen
import to.bitkit.ui.settings.pin.ChangePinResultScreen
import to.bitkit.ui.settings.pin.ChangePinScreen
import to.bitkit.ui.settings.pin.DisablePinScreen
import to.bitkit.ui.settings.quickPay.QuickPayIntroScreen
import to.bitkit.ui.settings.quickPay.QuickPaySettingsScreen
import to.bitkit.ui.settings.support.ReportIssueResultScreen
import to.bitkit.ui.settings.support.ReportIssueScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.settings.transactionSpeed.CustomFeeSettingsScreen
import to.bitkit.ui.settings.transactionSpeed.TransactionSpeedSettingsScreen
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.screenSlideIn
import to.bitkit.ui.utils.screenSlideOut
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.ExternalNodeViewModel
import to.bitkit.viewmodels.MainScreenEffect
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
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    // Effects on app entering fg (ON_START) / bg (ON_STOP)
    DisposableEffect(lifecycle) { //TODO ADAPT THIS LOGIC TO WORK WITH LightningNodeService
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
                    blocktankViewModel.triggerRefreshOrders()
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

    LaunchedEffect(appViewModel) {
        appViewModel.mainScreenEffect.collect {
            when (it) {
                is MainScreenEffect.NavigateActivityDetail -> navController.navigate(Routes.ActivityDetail(it.activityId))
                else -> Unit
            }
        }
    }

    val walletUiState by walletViewModel.uiState.collectAsState()
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

    if (walletIsInitializing) { //TODO ADAPT THIS LOGIC TO WORK WITH LightningNodeService
        if (nodeLifecycleState is NodeLifecycleState.ErrorStarting) {
            WalletInitResultView(result = WalletInitResult.Failed(nodeLifecycleState.cause)) {
                scope.launch {
                    try {
                        walletViewModel.setInitNodeLifecycleState()
                        walletViewModel.start()
                    } catch (e: Exception) {
                        Logger.error("Failed to start wallet on retry", e)
                    }
                }
            }
        } else {
            InitializingWalletView(
                shouldFinish = walletInitShouldFinish,
                onComplete = {
                    Logger.debug("Wallet finished initializing but node state is $nodeLifecycleState")

                    if (nodeLifecycleState == NodeLifecycleState.Running) {
                        walletIsInitializing = false
                    }
                }
            )
        }
    } else if (walletViewModel.isRestoringWallet) {
        WalletInitResultView(result = WalletInitResult.Restored) {
            walletViewModel.setRestoringWalletState(false)
        }
    } else {
        val balance by walletViewModel.balanceState.collectAsStateWithLifecycle()
        val currencies by currencyViewModel.uiState.collectAsState()

        LaunchedEffect(balance) {
            // Anytime we receive a balance update, we should sync the payments to activity list
            activityListViewModel.syncLdkNodePayments()
        }

        CompositionLocalProvider(
            LocalAppViewModel provides appViewModel,
            LocalWalletViewModel provides walletViewModel,
            LocalBlocktankViewModel provides blocktankViewModel,
            LocalCurrencyViewModel provides currencyViewModel,
            LocalActivityListViewModel provides activityListViewModel,
            LocalTransferViewModel provides transferViewModel,
            LocalBalances provides balance,
            LocalCurrencies provides currencies,
        ) {
            NavHost(navController, startDestination = Routes.Home) {
                home(walletViewModel, appViewModel, activityListViewModel, navController)
                settings(navController, appViewModel)
                nodeState(walletViewModel, navController)
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
                backupWalletSettings(navController)
                restoreWalletSettings(navController)
                channelOrdersSettings(navController)
                orderDetailSettings(navController)
                cjitDetailSettings(navController)
                lightning(walletViewModel, navController)
                devSettings(walletViewModel, navController)
                regtestSettings(navController)
                activityItem(activityListViewModel, navController)
                qrScanner(appViewModel, navController)
                authCheck(navController)
                logs(navController)
                suggestions(navController)

                // TODO extract transferNavigation
                navigation<Routes.TransferRoot>(
                    startDestination = Routes.TransferIntro,
                ) {
                    composable<Routes.TransferIntro> {
                        TransferIntroScreen(
                            onContinueClick = {
                                navController.navigateToTransferFunding()
                                appViewModel.setHasSeenTransferIntro(true)
                            },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SavingsIntro> {
                        SavingsIntroScreen(
                            onContinueClick = {
                                navController.navigate(Routes.SavingsAvailability)
                                appViewModel.setHasSeenSavingsIntro(true)
                            },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SavingsAvailability> {
                        SavingsAvailabilityScreen(
                            onBackClick = { navController.popBackStack() },
                            onCancelClick = { navController.navigateToHome() },
                            onContinueClick = { navController.navigate(Routes.SavingsConfirm) },
                        )
                    }
                    composable<Routes.SavingsConfirm> {
                        SavingsConfirmScreen(
                            onConfirm = { navController.navigate(Routes.SavingsProgress) },
                            onAdvancedClick = { navController.navigate(Routes.SavingsAdvanced) },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SavingsAdvanced> {
                        SavingsAdvancedScreen(
                            onContinueClick = { navController.popBackStack<Routes.SavingsConfirm>(inclusive = false) },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SavingsProgress> {
                        SavingsProgressScreen(
                            onContinueClick = { navController.navigateToHome() },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SpendingIntro> {
                        SpendingIntroScreen(
                            onContinueClick = {
                                navController.navigate(Routes.SpendingAmount)
                                appViewModel.setHasSeenSpendingIntro(true)
                            },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.SpendingAmount> {
                        SpendingAmountScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                            onOrderCreated = { navController.navigate(Routes.SpendingConfirm) },
                        )
                    }
                    composable<Routes.SpendingConfirm> {
                        SpendingConfirmScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                            onLearnMoreClick = { navController.navigate(Routes.TransferLiquidity) },
                            onAdvancedClick = { navController.navigate(Routes.SpendingAdvanced) },
                            onConfirm = { navController.navigate(Routes.SettingUp) },
                        )
                    }
                    composable<Routes.SpendingAdvanced> {
                        SpendingAdvancedScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                            onOrderCreated = { navController.popBackStack<Routes.SpendingConfirm>(inclusive = false) },
                        )
                    }
                    composable<Routes.TransferLiquidity> {
                        LiquidityScreen(
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.navigateToHome() },
                            onContinueClick = { navController.popBackStack() }
                        )
                    }
                    composable<Routes.SettingUp> {
                        SettingUpScreen(
                            viewModel = transferViewModel,
                            onCloseClick = { navController.navigateToHome() },
                            onContinueClick = { navController.navigateToHome() },
                        )
                    }
                    composable<Routes.Funding> {
                        val hasSeenSpendingIntro by appViewModel.hasSeenSpendingIntro.collectAsState()
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
                            onCloseClick = { navController.navigateUp() },
                        )
                    }
                    composable<Routes.FundingAdvanced> {
                        FundingAdvancedScreen(
                            onLnUrl = { navController.navigateToQrScanner() },
                            onManual = { navController.navigate(Routes.ExternalNav) },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                        )
                    }
                    navigation<Routes.ExternalNav>(
                        startDestination = Routes.ExternalConnection,
                    ) {
                        composable<Routes.ExternalConnection> {
                            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                            val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                            ExternalConnectionScreen(
                                viewModel = viewModel,
                                onNodeConnected = { navController.navigate(Routes.ExternalAmount) },
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                        composable<Routes.ExternalAmount> {
                            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                            val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                            ExternalAmountScreen(
                                viewModel = viewModel,
                                onContinue = { navController.navigate(Routes.ExternalConfirm) },
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                        composable<Routes.ExternalConfirm> {
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
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                        composable<Routes.ExternalSuccess> {
                            ExternalSuccessScreen(
                                onContinue = { navController.navigateToHome() },
                                onClose = { navController.navigateToHome() },
                            )
                        }
                        composable<Routes.ExternalFeeCustom> {
                            ExternalFeeCustomScreen(
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// region destinations
private fun NavGraphBuilder.home(
    viewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composable<Routes.Home> {
        HomeScreen(
            walletViewModel = viewModel,
            appViewModel = appViewModel,
            activityListViewModel = activityListViewModel,
            rootNavController = navController,
        )
    }
}

private fun NavGraphBuilder.settings(
    navController: NavHostController,
    appViewModel: AppViewModel,
) {
    composableWithDefaultTransitions<Routes.Settings> {
        SettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.QuickPayIntro> {
        QuickPayIntroScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
            onContinue = {
                appViewModel.setQuickPayIntroSeen(true)
                navController.navigate(Routes.QuickPaySettings)
            }
        )
    }
    composableWithDefaultTransitions<Routes.QuickPaySettings> {
        QuickPaySettingsScreen (
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
        )
    }
}

private fun NavGraphBuilder.nodeState(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.NodeState>(
        enterTransition = { screenSlideIn },
        exitTransition = { screenSlideOut },
    ) {
        NodeStateScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.generalSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.GeneralSettings> {
        GeneralSettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.WidgetsSettings> {
        WidgetsSettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.QuickPaySettings> {
        QuickPaySettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.TagsSettings> {
        TagsSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.advancedSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.AdvancedSettings> {
        AdvancedSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.aboutSettings(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.AboutSettings> {
        AboutScreen(navController)
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
    composableWithDefaultTransitions<Routes.ChangePinConfirm> { navBackEntry ->
        val route = navBackEntry.toRoute<Routes.ChangePinConfirm>()
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

private fun NavGraphBuilder.backupWalletSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.BackupWalletSettings> {
        BackupWalletScreen(navController)
    }
}

private fun NavGraphBuilder.restoreWalletSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.RestoreWalletSettings> {
        RestoreWalletScreen(navController)
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
    composableWithDefaultTransitions<Routes.OrderDetail> { navBackEntry ->
        OrderDetailScreen(
            orderItem = navBackEntry.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.cjitDetailSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.CjitDetail> { navBackEntry ->
        CJitDetailScreen(
            cjitItem = navBackEntry.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.lightning(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.Lightning> {
        LightningSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.devSettings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.DevSettings> {
        DevSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.regtestSettings(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.RegtestSettings> {
        val viewModel = hiltViewModel<BlocktankRegtestViewModel>()
        BlocktankRegtestScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.activityItem(
    activityListViewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.ActivityDetail> { navBackEntry ->
        ActivityDetailScreen(
            listViewModel = activityListViewModel,
            route = navBackEntry.toRoute(),
            onExploreClick = { id -> navController.navigateToActivityExplore(id) },
            onBackClick = { navController.popBackStack() },
            onCloseClick = { navController.navigateToHome() },
        )
    }
    composableWithDefaultTransitions<Routes.ActivityExplore> { navBackEntry ->
        ActivityExploreScreen(
            listViewModel = activityListViewModel,
            route = navBackEntry.toRoute(),
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
            navController.popBackStack()
            appViewModel.onScanSuccess(
                data = qrCode,
                onResultDelay = 650 // slight delay to for home navigation before showing send sheet
            )
        }
    }
}

private fun NavGraphBuilder.authCheck(
    navController: NavHostController,
) {
    composable<Routes.AuthCheck> { navBackEntry ->
        val route = navBackEntry.toRoute<Routes.AuthCheck>()
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
    composableWithDefaultTransitions<Routes.LogDetail> { navBackEntry ->
        val route = navBackEntry.toRoute<Routes.LogDetail>()
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
    composableWithDefaultTransitions<Routes.Support> {
        SupportScreen(
            onBack = { navController.popBackStack() },
            onClose = { navController.navigateToHome() },
            navigateReportIssue = { navController.navigate(Routes.ReportIssue) }
        )
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

// endregion

// region events
fun NavController.navigateToHome() = navigate(
    route = Routes.Home,
    navOptions = navOptions { popUpTo(Routes.Home) }
)

fun NavController.navigateToSettings() = navigate(
    route = Routes.Settings,
)

fun NavController.navigateToNodeState() = navigate(
    route = Routes.NodeState,
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

fun NavController.navigateToBackupWalletSettings() = navigate(
    route = Routes.BackupWalletSettings,
)

fun NavController.navigateToRestoreWalletSettings() = navigate(
    route = Routes.RestoreWalletSettings,
)

fun NavController.navigateToChannelOrdersSettings() = navigate(
    route = Routes.ChannelOrdersSettings,
)

fun NavController.navigateToOrderDetail(id: String) = navigate(
    route = Routes.OrderDetail(id),
)

fun NavController.navigateToCjitDetail(id: String) = navigate(
    route = Routes.CjitDetail(id),
)

fun NavController.navigateToLightning() = navigate(
    route = Routes.Lightning,
)

fun NavController.navigateToDevSettings() = navigate(
    route = Routes.DevSettings,
)

fun NavController.navigateToRegtestSettings() = navigate(
    route = Routes.RegtestSettings,
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

fun NavController.navigateToQrScanner() = navigate(
    route = Routes.QrScanner,
)

fun NavController.navigateToLogs() = navigate(
    route = Routes.Logs,
)

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

object Routes {
    @Serializable
    data object Home

    @Serializable
    data object Settings

    @Serializable
    data object NodeState

    @Serializable
    data object GeneralSettings

    @Serializable
    data object TransactionSpeedSettings

    @Serializable
    data object WidgetsSettings

    @Serializable
    data object QuickPaySettings

    @Serializable
    data object TagsSettings

    @Serializable
    data object AdvancedSettings

    @Serializable
    data object AboutSettings

    @Serializable
    data object CustomFeeSettings

    @Serializable
    data object SecuritySettings

    @Serializable
    data object DisablePin

    @Serializable
    data object ChangePin

    @Serializable
    data object ChangePinNew

    @Serializable
    data class ChangePinConfirm(val newPin: String)

    @Serializable
    data object ChangePinResult

    @Serializable
    data class AuthCheck(
        val showLogoOnPin: Boolean = false,
        val requirePin: Boolean = false,
        val requireBiometrics: Boolean = false,
        val onSuccessActionId: String,
    )

    @Serializable
    data object DefaultUnitSettings

    @Serializable
    data object LocalCurrencySettings

    @Serializable
    data object BackupSettings

    @Serializable
    data object BackupWalletSettings

    @Serializable
    data object RestoreWalletSettings

    @Serializable
    data object ChannelOrdersSettings

    @Serializable
    data object Logs

    @Serializable
    data class LogDetail(val fileName: String)

    @Serializable
    data class OrderDetail(val id: String)

    @Serializable
    data class CjitDetail(val id: String)

    @Serializable
    data object Lightning

    @Serializable
    data object DevSettings

    @Serializable
    data object RegtestSettings

    @Serializable
    data object TransferRoot

    @Serializable
    data object TransferIntro

    @Serializable
    data object SpendingIntro

    @Serializable
    data object SpendingAmount

    @Serializable
    data object SpendingConfirm

    @Serializable
    data object SpendingAdvanced

    @Serializable
    data object TransferLiquidity

    @Serializable
    data object SettingUp

    @Serializable
    data object SavingsIntro

    @Serializable
    data object SavingsAvailability

    @Serializable
    data object SavingsConfirm

    @Serializable
    data object SavingsAdvanced

    @Serializable
    data object SavingsProgress

    @Serializable
    data object Funding

    @Serializable
    data object FundingAdvanced

    @Serializable
    data object ExternalNav

    @Serializable
    data object ExternalConnection

    @Serializable
    data object ExternalAmount

    @Serializable
    data object ExternalConfirm

    @Serializable
    data object ExternalSuccess

    @Serializable
    data object ExternalFeeCustom

    @Serializable
    data class ActivityDetail(val id: String)

    @Serializable
    data class ActivityExplore(val id: String)

    @Serializable
    data object QrScanner

    @Serializable
    data object BuyIntro

    @Serializable
    data object Support

    @Serializable
    data object ReportIssue

    @Serializable
    data object ReportIssueSuccess

    @Serializable
    data object ReportIssueFailure

    @Serializable
    data object QuickPayIntro

    @Serializable
    data object QuickPaySettings
}
