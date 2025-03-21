package to.bitkit.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
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
import to.bitkit.ui.screens.wallets.activity.ActivityItemScreen
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.BlocktankRegtestViewModel
import to.bitkit.ui.settings.CJitDetailScreen
import to.bitkit.ui.settings.ChannelOrdersScreen
import to.bitkit.ui.settings.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.GeneralSettingsScreen
import to.bitkit.ui.settings.LightningSettingsScreen
import to.bitkit.ui.settings.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.OrderDetailScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.backups.BackupWalletScreen
import to.bitkit.ui.settings.backups.RestoreWalletScreen
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.ExternalNodeViewModel
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
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    try {
                        walletViewModel.start()
                        activityListViewModel.syncLdkNodePayments()
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

                Lifecycle.Event.ON_STOP -> {
                    walletViewModel.stopIfNeeded()
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

    if (walletIsInitializing) {
        if (nodeLifecycleState is NodeLifecycleState.ErrorStarting) {
            WalletInitResultView(result = WalletInitResult.Failed(nodeLifecycleState.cause)) {
                scope.launch {
                    try {
                        walletViewModel.setInitNodeLifecycleState()
                        walletViewModel.start()
                        walletViewModel.setWalletExistsState()
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
            walletViewModel.isRestoringWallet = false
        }
    } else {
        val balance by walletViewModel.balanceState.collectAsState()
        val currencies by currencyViewModel.uiState.collectAsState()

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
                home(walletViewModel, appViewModel, navController)
                settings(walletViewModel, navController)
                nodeState(walletViewModel, navController)
                generalSettings(navController)
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
                allActivity(activityListViewModel, navController)
                activityItem(activityListViewModel, navController)
                qrScanner(appViewModel, navController)

                // TODO extract transferNavigation
                navigation<Routes.TransferRoot>(
                    startDestination = Routes.TransferIntro,
                ) {
                    composable<Routes.TransferIntro> {
                        TransferIntroScreen()
                    }
                    composable<Routes.SavingsIntro> {
                        SavingsIntroScreen(
                            onContinueClick = {
                                navController.navigate(Routes.SavingsAvailability)
                                appViewModel.setHasSeenSavingsIntro(true)
                            },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                        )
                    }
                    composable<Routes.SavingsAvailability> {
                        SavingsAvailabilityScreen(
                            onBackClick = { navController.popBackStack() },
                            onCancelClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onContinueClick = { navController.navigate(Routes.SavingsConfirm) },
                        )
                    }
                    composable<Routes.SavingsConfirm> {
                        SavingsConfirmScreen(
                            onConfirm = { navController.navigate(Routes.SavingsProgress) },
                            onAdvancedClick = { navController.navigate(Routes.SavingsAdvanced) },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                        )
                    }
                    composable<Routes.SavingsAdvanced> {
                        SavingsAdvancedScreen(
                            onContinueClick = { navController.popBackStack<Routes.SavingsConfirm>(inclusive = false) },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                        )
                    }
                    composable<Routes.SavingsProgress> {
                        SavingsProgressScreen(
                            onContinueClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                        )
                    }
                    composable<Routes.SpendingIntro> {
                        SpendingIntroScreen(
                            onContinueClick = {
                                navController.navigate(Routes.SpendingAmount)
                                appViewModel.setHasSeenSpendingIntro(true)
                            },
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                        )
                    }
                    composable<Routes.SpendingAmount> {
                        SpendingAmountScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onOrderCreated = { navController.navigate(Routes.SpendingConfirm) },
                        )
                    }
                    composable<Routes.SpendingConfirm> {
                        SpendingConfirmScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onLearnMoreClick = { navController.navigate(Routes.TransferLiquidity) },
                            onAdvancedClick = { navController.navigate(Routes.SpendingAdvanced) },
                            onConfirm = { navController.navigate(Routes.SettingUp) },
                        )
                    }
                    composable<Routes.SpendingAdvanced> {
                        SpendingAdvancedScreen(
                            viewModel = transferViewModel,
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onOrderCreated = { navController.popBackStack<Routes.SpendingConfirm>(inclusive = false) },
                        )
                    }
                    composable<Routes.TransferLiquidity> {
                        LiquidityScreen(
                            onBackClick = { navController.popBackStack() },
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onContinueClick = { navController.popBackStack() }
                        )
                    }
                    composable<Routes.SettingUp> {
                        SettingUpScreen(
                            viewModel = transferViewModel,
                            onCloseClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
                            onContinueClick = { navController.popBackStack<Routes.Home>(inclusive = false) },
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
                                    navController.popBackStack<Routes.Home>(inclusive = false)
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
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                        composable<Routes.ExternalConfirm> {
                            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                            val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                            ExternalConfirmScreen(
                                viewModel = viewModel,
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                            )
                        }
                        composable<Routes.ExternalSuccess> {
                            ExternalSuccessScreen(
                                onBackClick = { navController.popBackStack() },
                                onCloseClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
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
    navController: NavHostController,
) {
    composable<Routes.Home> {
        HomeScreen(
            walletViewModel = viewModel,
            appViewModel = appViewModel,
            rootNavController = navController
        )
    }
}

private fun NavGraphBuilder.settings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.Settings> {
        SettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.nodeState(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.NodeState> {
        NodeStateScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.generalSettings(navController: NavHostController) {
    composable<Routes.GeneralSettings> {
        GeneralSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.defaultUnitSettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composable<Routes.DefaultUnitSettings> {
        DefaultUnitSettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.localCurrencySettings(
    currencyViewModel: CurrencyViewModel,
    navController: NavHostController,
) {
    composable<Routes.LocalCurrencySettings> {
        LocalCurrencySettingsScreen(currencyViewModel, navController)
    }
}

private fun NavGraphBuilder.backupSettings(
    navController: NavHostController,
) {
    composable<Routes.BackupSettings> {
        BackupSettingsScreen(navController)
    }
}

private fun NavGraphBuilder.backupWalletSettings(
    navController: NavHostController,
) {
    composable<Routes.BackupWalletSettings> {
        BackupWalletScreen(navController)
    }
}

private fun NavGraphBuilder.restoreWalletSettings(
    navController: NavHostController,
) {
    composable<Routes.RestoreWalletSettings> {
        RestoreWalletScreen(navController)
    }
}

private fun NavGraphBuilder.channelOrdersSettings(
    navController: NavHostController,
) {
    composable<Routes.ChannelOrdersSettings> {
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
    composable<Routes.OrderDetail> { navBackEntry ->
        OrderDetailScreen(
            orderItem = navBackEntry.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.cjitDetailSettings(
    navController: NavHostController,
) {
    composable<Routes.CjitDetail> { navBackEntry ->
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
    composable<Routes.Lightning> {
        LightningSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.devSettings(
    viewModel: WalletViewModel,
    navController: NavHostController,
) {
    composable<Routes.DevSettings> {
        DevSettingsScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.regtestSettings(
    navController: NavHostController,
) {
    composable<Routes.RegtestSettings> {
        val viewModel = hiltViewModel<BlocktankRegtestViewModel>()
        BlocktankRegtestScreen(viewModel, navController)
    }
}

private fun NavGraphBuilder.allActivity(
    viewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composable<Routes.AllActivity> {
        AllActivityScreen(
            viewModel = viewModel,
            onBackCLick = { navController.popBackStack() },
            onActivityItemClick = { navController.navigateToActivityItem(it) },
        )
    }
}

private fun NavGraphBuilder.activityItem(
    viewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composable<Routes.ActivityItem> { navBackEntry ->
        ActivityItemScreen(
            viewModel = viewModel,
            activityItem = navBackEntry.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.qrScanner(
    appViewModel: AppViewModel,
    navController: NavHostController,
) {
    composable<Routes.QrScanner>(
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 300)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 300)
            )
        },
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
// endregion

// region events
fun NavController.navigateToSettings() = navigate(
    route = Routes.Settings,
)

fun NavController.navigateToNodeState() = navigate(
    route = Routes.NodeState,
)

fun NavController.navigateToGeneralSettings() = navigate(
    route = Routes.GeneralSettings,
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

fun NavController.navigateToTransferFunding() = navigate(
    route = Routes.Funding,
)

fun NavController.navigateToAllActivity() = navigate(
    route = Routes.AllActivity,
)

fun NavController.navigateToActivityItem(id: String) = navigate(
    route = Routes.ActivityItem(id),
)

fun NavController.navigateToQrScanner() = navigate(
    route = Routes.QrScanner,
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
    data object AllActivity

    @Serializable
    data class ActivityItem(val id: String)

    @Serializable
    data object QrScanner
}
