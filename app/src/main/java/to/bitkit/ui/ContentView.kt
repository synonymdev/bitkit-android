@file:Suppress("TooManyFunctions")

package to.bitkit.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.env.Env
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.models.WidgetType
import to.bitkit.ui.Routes.ExternalConnection
import to.bitkit.ui.components.AuthCheckScreen
import to.bitkit.ui.components.DrawerMenu
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.ui.onboarding.InitializingWalletView
import to.bitkit.ui.onboarding.WalletRestoreErrorView
import to.bitkit.ui.onboarding.WalletRestoreSuccessView
import to.bitkit.ui.screens.CriticalUpdateScreen
import to.bitkit.ui.screens.contacts.AddContactScreen
import to.bitkit.ui.screens.contacts.AddContactViewModel
import to.bitkit.ui.screens.contacts.ContactDetailScreen
import to.bitkit.ui.screens.contacts.ContactDetailViewModel
import to.bitkit.ui.screens.contacts.ContactImportOverviewScreen
import to.bitkit.ui.screens.contacts.ContactImportOverviewViewModel
import to.bitkit.ui.screens.contacts.ContactImportSelectScreen
import to.bitkit.ui.screens.contacts.ContactImportSelectViewModel
import to.bitkit.ui.screens.contacts.ContactsIntroScreen
import to.bitkit.ui.screens.contacts.ContactsScreen
import to.bitkit.ui.screens.contacts.ContactsViewModel
import to.bitkit.ui.screens.contacts.EditContactScreen
import to.bitkit.ui.screens.contacts.EditContactViewModel
import to.bitkit.ui.screens.contacts.shouldDiscardPendingImport
import to.bitkit.ui.screens.profile.CreateProfileScreen
import to.bitkit.ui.screens.profile.CreateProfileViewModel
import to.bitkit.ui.screens.profile.EditProfileScreen
import to.bitkit.ui.screens.profile.EditProfileViewModel
import to.bitkit.ui.screens.profile.MilestoneDetailScreen
import to.bitkit.ui.screens.profile.PayContactsScreen
import to.bitkit.ui.screens.profile.ProfileIntroScreen
import to.bitkit.ui.screens.profile.ProfileScreen
import to.bitkit.ui.screens.profile.ProfileViewModel
import to.bitkit.ui.screens.profile.PubkyAuthApprovalSheet
import to.bitkit.ui.screens.profile.PubkyChoiceScreen
import to.bitkit.ui.screens.profile.PubkyChoiceViewModel
import to.bitkit.ui.screens.recovery.RecoveryMnemonicScreen
import to.bitkit.ui.screens.recovery.RecoveryModeScreen
import to.bitkit.ui.screens.settings.DevSettingsScreen
import to.bitkit.ui.screens.settings.FeeSettingsScreen
import to.bitkit.ui.screens.settings.LdkDebugScreen
import to.bitkit.ui.screens.settings.ProbingToolScreen
import to.bitkit.ui.screens.settings.VssDebugScreen
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
import to.bitkit.ui.screens.transfer.external.ExternalNodeViewModel
import to.bitkit.ui.screens.transfer.external.ExternalSuccessScreen
import to.bitkit.ui.screens.transfer.external.LnurlChannelScreen
import to.bitkit.ui.screens.wallets.HomeScreen
import to.bitkit.ui.screens.wallets.SavingsWalletScreen
import to.bitkit.ui.screens.wallets.SpendingWalletScreen
import to.bitkit.ui.screens.wallets.activity.ActivityDetailScreen
import to.bitkit.ui.screens.wallets.activity.ActivityExploreScreen
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.screens.wallets.activity.DateRangeSelectorSheet
import to.bitkit.ui.screens.wallets.activity.TagSelectorSheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.screens.wallets.receive.ReceiveSheet
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
import to.bitkit.ui.screens.widgets.suggestions.SuggestionsPreviewScreen
import to.bitkit.ui.screens.widgets.suggestions.SuggestionsViewModel
import to.bitkit.ui.screens.widgets.weather.WeatherEditScreen
import to.bitkit.ui.screens.widgets.weather.WeatherPreviewScreen
import to.bitkit.ui.screens.widgets.weather.WeatherViewModel
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.CJitDetailScreen
import to.bitkit.ui.settings.ChannelOrdersScreen
import to.bitkit.ui.settings.LanguageSettingsScreen
import to.bitkit.ui.settings.LogDetailScreen
import to.bitkit.ui.settings.LogsScreen
import to.bitkit.ui.settings.OrderDetailScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.advanced.AddressTypePreferenceScreen
import to.bitkit.ui.settings.advanced.AddressViewerScreen
import to.bitkit.ui.settings.advanced.CoinSelectPreferenceScreen
import to.bitkit.ui.settings.advanced.ElectrumConfigScreen
import to.bitkit.ui.settings.advanced.RgsServerScreen
import to.bitkit.ui.settings.appStatus.AppStatusScreen
import to.bitkit.ui.settings.backgroundPayments.BackgroundPaymentsIntroScreen
import to.bitkit.ui.settings.backgroundPayments.BackgroundPaymentsSettings
import to.bitkit.ui.settings.backups.ResetAndRestoreScreen
import to.bitkit.ui.settings.general.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.general.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.general.TagsSettingsScreen
import to.bitkit.ui.settings.general.WidgetsSettingsScreen
import to.bitkit.ui.settings.lightning.ChannelDetailScreen
import to.bitkit.ui.settings.lightning.CloseConnectionScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsViewModel
import to.bitkit.ui.settings.pin.PinManagementScreen
import to.bitkit.ui.settings.quickPay.QuickPayIntroScreen
import to.bitkit.ui.settings.quickPay.QuickPaySettingsScreen
import to.bitkit.ui.settings.support.ReportIssueResultScreen
import to.bitkit.ui.settings.support.ReportIssueScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.settings.transactionSpeed.CustomFeeSettingsScreen
import to.bitkit.ui.settings.transactionSpeed.TransactionSpeedSettingsScreen
import to.bitkit.ui.sheets.BackgroundPaymentsIntroSheet
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.BackupSheet
import to.bitkit.ui.sheets.ChangePinSheet
import to.bitkit.ui.sheets.ConnectionClosedSheet
import to.bitkit.ui.sheets.DisablePinSheet
import to.bitkit.ui.sheets.ForceTransferSheet
import to.bitkit.ui.sheets.GiftSheet
import to.bitkit.ui.sheets.HighBalanceWarningSheet
import to.bitkit.ui.sheets.LnurlAuthSheet
import to.bitkit.ui.sheets.PinSheet
import to.bitkit.ui.sheets.QrScanningSheet
import to.bitkit.ui.sheets.QuickPayIntroSheet
import to.bitkit.ui.sheets.SendSheet
import to.bitkit.ui.sheets.UpdateSheet
import to.bitkit.ui.utils.AutoReadClipboardHandler
import to.bitkit.ui.utils.RequestNotificationPermissions
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.navigationWithDefaultTransitions
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

@Suppress("CyclomaticComplexMethod")
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
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current
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
                is MainScreenEffect.Navigate -> if (it.clearStack) {
                    navController.navigateTo(it.route) { popUpTo(0) { inclusive = true } }
                } else {
                    navController.navigateTo(it.route)
                }

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

    var walletIsInitializing by remember { mutableStateOf(nodeLifecycleState == NodeLifecycleState.Initializing) }
    var walletInitShouldFinish by remember { mutableStateOf(false) }

    val restoreState by walletViewModel.restoreState.collectAsStateWithLifecycle()
    val isRestoringFromRNRemoteBackup by walletViewModel.isRestoringFromRNRemoteBackup.collectAsStateWithLifecycle()

    // React to nodeLifecycleState changes
    LaunchedEffect(nodeLifecycleState, restoreState, isRestoringFromRNRemoteBackup) {
        when (nodeLifecycleState) {
            NodeLifecycleState.Initializing -> {
                walletIsInitializing = true
            }

            NodeLifecycleState.Running -> {
                val restoreComplete = restoreState !is RestoreState.InProgress
                val metadataComplete = !isRestoringFromRNRemoteBackup
                if (restoreComplete && metadataComplete) {
                    walletInitShouldFinish = true
                }
            }

            is NodeLifecycleState.ErrorStarting -> {
                walletInitShouldFinish = true
            }

            else -> Unit
        }
    }

    if (walletIsInitializing) {
        if (nodeLifecycleState is NodeLifecycleState.ErrorStarting) {
            WalletRestoreErrorView(
                retryCount = restoreState.retryCount(),
                hazeState = hazeState,
                onRetry = walletViewModel::onRestoreRetry,
                onProceedWithoutRestore = {
                    walletViewModel.onProceedWithoutRestore {
                        walletIsInitializing = false
                    }
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
    val currencies by currencyViewModel.uiState.collectAsStateWithLifecycle()

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
        AutoReadClipboardHandler()

        val hasSeenWidgetsIntro by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
        val hasSeenShopIntro by settingsViewModel.hasSeenShopIntro.collectAsStateWithLifecycle()
        val hasSeenProfileIntro by settingsViewModel.hasSeenProfileIntro.collectAsStateWithLifecycle()
        val hasSeenContactsIntro by settingsViewModel.hasSeenContactsIntro.collectAsStateWithLifecycle()
        val isProfileAuthenticated by settingsViewModel.isPubkyAuthenticated.collectAsStateWithLifecycle()
        val currentSheet by appViewModel.currentSheet.collectAsStateWithLifecycle()

        Box(
            modifier = modifier.fillMaxSize()
        ) {
            SheetHost(
                shouldExpand = currentSheet != null,
                onDismiss = { appViewModel.hideSheet() },
                sheets = {
                    when (val sheet = currentSheet) {
                        null -> Unit
                        is Sheet.Send -> {
                            SendSheet(
                                appViewModel = appViewModel,
                                walletViewModel = walletViewModel,
                                startDestination = sheet.route,
                            )
                        }

                        is Sheet.Receive -> {
                            val walletState by walletViewModel.walletState.collectAsStateWithLifecycle()
                            ReceiveSheet(
                                startRoute = sheet.route,
                                walletState = walletState,
                                navigateToExternalConnection = {
                                    navController.navigateTo(ExternalConnection())
                                    appViewModel.hideSheet()
                                }
                            )
                        }

                        is Sheet.ActivityDateRangeSelector -> DateRangeSelectorSheet()
                        is Sheet.ActivityTagSelector -> TagSelectorSheet()
                        is Sheet.Pin -> PinSheet(sheet, appViewModel)
                        Sheet.ChangePin -> ChangePinSheet(appViewModel)
                        Sheet.DisablePin -> DisablePinSheet(appViewModel)
                        is Sheet.Backup -> BackupSheet(sheet, onDismiss = { appViewModel.hideSheet() })
                        is Sheet.LnurlAuth -> LnurlAuthSheet(sheet, appViewModel)
                        Sheet.ForceTransfer -> ForceTransferSheet(appViewModel, transferViewModel)
                        Sheet.ConnectionClosed -> ConnectionClosedSheet(
                            onDismiss = { appViewModel.hideSheet() },
                        )

                        is Sheet.Gift -> GiftSheet(sheet, appViewModel)
                        Sheet.QrScanner -> QrScanningSheet(appViewModel)
                        is Sheet.PubkyAuth -> PubkyAuthApprovalSheet(
                            authUrl = sheet.authUrl,
                            viewModel = hiltViewModel(),
                            onDismiss = { appViewModel.hideSheet() },
                        )
                        is Sheet.TimedSheet -> {
                            when (sheet.type) {
                                TimedSheetType.APP_UPDATE -> {
                                    UpdateSheet(onCancel = { appViewModel.dismissTimedSheet() })
                                }

                                TimedSheetType.BACKUP -> {
                                    BackupSheet(
                                        sheet = Sheet.Backup(BackupRoute.Intro),
                                        onDismiss = { appViewModel.dismissTimedSheet() }
                                    )
                                }

                                TimedSheetType.NOTIFICATIONS -> {
                                    BackgroundPaymentsIntroSheet(
                                        onContinue = {
                                            appViewModel.dismissTimedSheet()
                                            navController.navigateTo(Routes.BackgroundPaymentsSettings)
                                            settingsViewModel.setBgPaymentsIntroSeen(true)
                                        },
                                    )
                                }

                                TimedSheetType.QUICK_PAY -> {
                                    QuickPayIntroSheet(
                                        onContinue = {
                                            appViewModel.dismissTimedSheet()
                                            navController.navigateTo(Routes.QuickPaySettings)
                                        },
                                    )
                                }

                                TimedSheetType.HIGH_BALANCE -> {
                                    HighBalanceWarningSheet(
                                        understoodClick = { appViewModel.dismissTimedSheet() },
                                        learnMoreClick = {
                                            val intent =
                                                Intent(Intent.ACTION_VIEW, Env.STORING_BITCOINS_URL.toUri())
                                            context.startActivity(intent)
                                            appViewModel.dismissTimedSheet()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    RootNavHost(
                        navController = navController,
                        drawerState = drawerState,
                        walletViewModel = walletViewModel,
                        appViewModel = appViewModel,
                        activityListViewModel = activityListViewModel,
                        settingsViewModel = settingsViewModel,
                        currencyViewModel = currencyViewModel,
                        transferViewModel = transferViewModel,
                    )

                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val showTabBar = currentRoute in listOf(
                        Routes.Home::class.qualifiedName,
                        Routes.AllActivity::class.qualifiedName,
                        Routes.Savings::class.qualifiedName,
                        Routes.Spending::class.qualifiedName,
                    )

                    if (showTabBar) {
                        TabBar(
                            onSendClick = { appViewModel.showSheet(Sheet.Send()) },
                            onReceiveClick = { appViewModel.showSheet(Sheet.Receive()) },
                            onScanClick = { appViewModel.showScannerSheet() },
                        )
                    }
                }
            }

            DrawerMenu(
                drawerState = drawerState,
                rootNavController = navController,
                hasSeenWidgetsIntro = hasSeenWidgetsIntro,
                hasSeenShopIntro = hasSeenShopIntro,
                onBeforeNavigate = { destination ->
                    if (shouldDiscardPendingImport(navController.currentDestination, destination)) {
                        appViewModel.clearPendingPubkyImport()
                    }
                },
                hasSeenProfileIntro = hasSeenProfileIntro,
                hasSeenContactsIntro = hasSeenContactsIntro,
                isProfileAuthenticated = isProfileAuthenticated,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun RootNavHost(
    navController: NavHostController,
    drawerState: DrawerState,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
    transferViewModel: TransferViewModel,
) {
    val scope = rememberCoroutineScope()

    NavHost(navController, startDestination = Routes.Home) {
        home(
            walletViewModel = walletViewModel,
            appViewModel = appViewModel,
            activityListViewModel = activityListViewModel,
            settingsViewModel = settingsViewModel,
            navController = navController,
            drawerState = drawerState,
        )
        allActivity(
            activityListViewModel = activityListViewModel,
            navController = navController,
        )
        settings(navController, settingsViewModel)
        contacts(navController, settingsViewModel, appViewModel)
        profile(navController, settingsViewModel)
        shop(navController, settingsViewModel, appViewModel)
        generalSettingsSubScreens(navController)
        advancedSettingsSubScreens(navController)
        transactionSpeedSettings(navController)
        pinManagement(navController)
        defaultUnitSettings(currencyViewModel, navController)
        localCurrencySettings(currencyViewModel, navController)
        backupSettings(navController)
        resetAndRestoreSettings(navController)
        channelOrdersSettings(navController)
        orderDetailSettings(navController)
        cjitDetailSettings(navController)
        lightningConnections(navController)
        activityItem(activityListViewModel, navController)
        authCheck(navController)
        logs(navController)
        suggestions(navController)
        support(navController)
        widgets(navController, settingsViewModel, currencyViewModel)
        update()
        recoveryMode(navController, appViewModel)

        // TODO extract transferNavigation
        navigationWithDefaultTransitions<Routes.TransferRoot>(
            startDestination = Routes.TransferIntro,
        ) {
            composableWithDefaultTransitions<Routes.TransferIntro> {
                TransferIntroScreen(
                    onContinueClick = {
                        navController.navigateToTransferFunding()
                        settingsViewModel.setHasSeenTransferIntro(true)
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsIntro> {
                SavingsIntroScreen(
                    onContinueClick = {
                        navController.navigateTo(Routes.SavingsAvailability)
                        settingsViewModel.setHasSeenSavingsIntro(true)
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsAvailability> {
                SavingsAvailabilityScreen(
                    onBackClick = { navController.popBackStack() },
                    onCancelClick = { navController.navigateToHome() },
                    onContinueClick = { navController.navigateTo(Routes.SavingsConfirm) },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsConfirm> {
                SavingsConfirmScreen(
                    onConfirm = { navController.navigateTo(Routes.SavingsProgress) },
                    onAdvancedClick = { navController.navigateTo(Routes.SavingsAdvanced) },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsAdvanced> {
                SavingsAdvancedScreen(
                    onContinueClick = { navController.popBackStack<Routes.SavingsConfirm>(inclusive = false) },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<Routes.SavingsProgress> {
                SavingsProgressScreen(
                    app = appViewModel,
                    wallet = walletViewModel,
                    transfer = transferViewModel,
                    onContinueClick = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                    onTransferUnavailable = { navController.popBackStack<Routes.TransferRoot>(inclusive = true) },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingIntro> {
                SpendingIntroScreen(
                    onContinueClick = {
                        navController.navigateTo(Routes.SpendingAmount)
                        settingsViewModel.setHasSeenSpendingIntro(true)
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingAmount> {
                SpendingAmountScreen(
                    viewModel = transferViewModel,
                    onBackClick = { navController.popBackStack() },
                    onOrderCreated = { navController.navigateTo(Routes.SpendingConfirm) },
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
                    onLearnMoreClick = { navController.navigateTo(Routes.TransferLiquidity) },
                    onAdvancedClick = { navController.navigateTo(Routes.SpendingAdvanced) },
                    onConfirm = { navController.navigateTo(Routes.SettingUp) },
                )
            }
            composableWithDefaultTransitions<Routes.SpendingAdvanced> {
                SpendingAdvancedScreen(
                    viewModel = transferViewModel,
                    onBackClick = { navController.popBackStack() },
                    onOrderCreated = { navController.popBackStack<Routes.SpendingConfirm>(inclusive = false) },
                )
            }
            composableWithDefaultTransitions<Routes.TransferLiquidity> {
                LiquidityScreen(
                    onBackClick = { navController.popBackStack() },
                    onContinueClick = { navController.popBackStack() }
                )
            }
            composableWithDefaultTransitions<Routes.SettingUp> {
                SettingUpScreen(
                    viewModel = transferViewModel,
                    onContinueClick = {
                        navController.navigateToHome()
                    }
                )
            }
            composableWithDefaultTransitions<Routes.Funding> {
                val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
                val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()

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
                            navController.navigateToHome()
                            delay(500) // Wait for nav to actually finish
                            appViewModel.showSheet(Sheet.Receive(route = ReceiveRoute.Amount))
                        }
                    },
                    onManual = { navController.navigateTo(Routes.ExternalNav) },
                    onBackClick = { navController.popBackStack() },
                    isGeoBlocked = isGeoBlocked,
                )
            }
            composableWithDefaultTransitions<Routes.FundingAdvanced> {
                FundingAdvancedScreen(
                    onLnurl = { appViewModel.showScannerSheet() },
                    onManual = { navController.navigateTo(Routes.ExternalNav) },
                    onBackClick = { navController.popBackStack() },
                )
            }
            navigationWithDefaultTransitions<Routes.ExternalNav>(
                startDestination = ExternalConnection(),
            ) {
                composableWithDefaultTransitions<ExternalConnection> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val route = it.toRoute<ExternalConnection>()
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalConnectionScreen(
                        route = route,
                        viewModel = viewModel,
                        onNodeConnected = { navController.navigateTo(Routes.ExternalAmount) },
                        onScanClick = {
                            appViewModel.showScannerSheet {
                                viewModel.parseNodeUri(it)
                            }
                        },
                        onBackClick = { navController.popBackStack() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalAmount> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalAmountScreen(
                        viewModel = viewModel,
                        onContinue = { navController.navigateTo(Routes.ExternalConfirm) },
                        onBackClick = { navController.popBackStack() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalConfirm> {
                    val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ExternalNav) }
                    val viewModel = hiltViewModel<ExternalNodeViewModel>(parentEntry)

                    ExternalConfirmScreen(
                        viewModel = viewModel,
                        onConfirm = {
                            walletViewModel.refreshState()
                            navController.navigateTo(Routes.ExternalSuccess)
                        },
                        onBackClick = { navController.popBackStack() },
                    )
                }
                composableWithDefaultTransitions<Routes.LnurlChannel> {
                    LnurlChannelScreen(
                        route = it.toRoute<Routes.LnurlChannel>(),
                        onConnected = { navController.navigateTo(Routes.ExternalSuccess) },
                        onBack = { navController.popBackStack() },
                        onClose = { navController.navigateToHome() },
                    )
                }
                composableWithDefaultTransitions<Routes.ExternalSuccess> {
                    ExternalSuccessScreen(
                        onContinue = { navController.navigateToHome() },
                    )
                }
            }
        }
    }
}

// region destinations
@Suppress("LongMethod", "LongParameterList")
private fun NavGraphBuilder.home(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
    drawerState: DrawerState,
) {
    composable<Routes.Home> {
        val isRefreshing by walletViewModel.isRefreshing.collectAsStateWithLifecycle()
        val isRecoveryMode by walletViewModel.isRecoveryMode.collectAsStateWithLifecycle()
        val hazeState = rememberHazeState()

        RequestNotificationPermissions(
            showPermissionDialog = !isRecoveryMode,
            onPermissionChange = { granted ->
                settingsViewModel.setNotificationPreference(granted)
            }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            HomeScreen(
                isRefreshing = isRefreshing,
                drawerState = drawerState,
                rootNavController = navController,
                walletNavController = navController,
                settingsViewModel = settingsViewModel,
                walletViewModel = walletViewModel,
                appViewModel = appViewModel,
                activityListViewModel = activityListViewModel,
            )
        }
    }
    composableWithDefaultTransitions<Routes.Savings> {
        val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
        val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()
        val onchainActivities by activityListViewModel.onchainActivities.collectAsStateWithLifecycle()
        val forceCloseRemainingDuration by appViewModel.forceCloseRemainingDuration.collectAsStateWithLifecycle()

        SavingsWalletScreen(
            isGeoBlocked = isGeoBlocked,
            onchainActivities = onchainActivities ?: persistentListOf(),
            onAllActivityButtonClick = { navController.navigateToAllActivity(activityListViewModel::clearFilters) },
            onActivityItemClick = { navController.navigateToActivityItem(it) },
            onEmptyActivityRowClick = { appViewModel.showSheet(Sheet.Receive()) },
            onTransferToSpendingClick = {
                if (!hasSeenSpendingIntro) {
                    navController.navigateToTransferSpendingIntro()
                } else {
                    navController.navigateToTransferSpendingAmount()
                }
            },
            onBackClick = { navController.popBackStack() },
            forceCloseRemainingDuration = forceCloseRemainingDuration,
        )
    }
    composableWithDefaultTransitions<Routes.Spending> {
        val hasSeenSavingsIntro by settingsViewModel.hasSeenSavingsIntro.collectAsStateWithLifecycle()
        val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()
        val lightningActivities by activityListViewModel.lightningActivities.collectAsStateWithLifecycle()

        SpendingWalletScreen(
            channels = lightningState.channels,
            lightningActivities = lightningActivities ?: persistentListOf(),
            onAllActivityButtonClick = { navController.navigateToAllActivity(activityListViewModel::clearFilters) },
            onActivityItemClick = { navController.navigateToActivityItem(it) },
            onEmptyActivityRowClick = { appViewModel.showSheet(Sheet.Receive()) },
            onTransferToSavingsClick = {
                if (!hasSeenSavingsIntro) {
                    navController.navigateToTransferSavingsIntro()
                } else {
                    navController.navigateToTransferSavingsAvailability()
                }
            },
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.allActivity(
    activityListViewModel: ActivityListViewModel,
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.AllActivity> {
        AllActivityScreen(
            viewModel = activityListViewModel,
            onBack = { navController.popBackStack() },
            onActivityItemClick = { id -> navController.navigateToActivityItem(id) },
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
    @Suppress("ForbiddenComment")
    // TODO: display as sheet
    composableWithDefaultTransitions<Routes.QuickPayIntro> {
        QuickPayIntroScreen(
            onBack = { navController.popBackStack() },
            onContinue = {
                settingsViewModel.setQuickPayIntroSeen(true)
                navController.navigateTo(Routes.QuickPaySettings)
            }
        )
    }
    composableWithDefaultTransitions<Routes.QuickPaySettings> {
        QuickPaySettingsScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.DevSettings> {
        DevSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.LdkDebug> {
        LdkDebugScreen(navController)
    }
    composableWithDefaultTransitions<Routes.VssDebug> {
        VssDebugScreen(navController)
    }
    composableWithDefaultTransitions<Routes.ProbingTool> {
        ProbingToolScreen(navController)
    }
    composableWithDefaultTransitions<Routes.FeeSettings> {
        FeeSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.RegtestSettings> {
        BlocktankRegtestScreen(navController)
    }
    composableWithDefaultTransitions<Routes.LanguageSettings> {
        LanguageSettingsScreen(
            onBackClick = { navController.popBackStack() },
        )
    }
}

@Suppress("LongMethod")
private fun NavGraphBuilder.contacts(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    appViewModel: AppViewModel,
) {
    composableWithDefaultTransitions<Routes.Contacts> {
        val viewModel: ContactsViewModel = hiltViewModel()
        ContactsScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onClickMyProfile = { navController.navigateTo(Routes.Profile) },
            onClickContact = { navController.navigateTo(Routes.ContactDetail(it)) },
            onAddContact = { navController.navigateTo(Routes.AddContact(it)) },
            onScanQr = {
                appViewModel.showScannerSheet { scannedData ->
                    navController.navigateTo(Routes.AddContact(scannedData))
                }
            },
        )
    }
    composableWithDefaultTransitions<Routes.ContactsIntro> {
        val isAuthenticated by settingsViewModel.isPubkyAuthenticated.collectAsStateWithLifecycle()
        val hasSeenProfileIntro by settingsViewModel.hasSeenProfileIntro.collectAsStateWithLifecycle()
        ContactsIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenContactsIntro(true)
                val destination = when {
                    isAuthenticated -> Routes.Contacts
                    hasSeenProfileIntro -> Routes.PubkyChoice
                    else -> Routes.ProfileIntro
                }
                navController.navigateTo(destination) { popUpTo(Routes.Home) }
            },
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.ContactDetail> {
        val viewModel: ContactDetailViewModel = hiltViewModel()
        ContactDetailScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onEditContact = { navController.navigateTo(Routes.EditContact(it)) },
        )
    }
    composableWithDefaultTransitions<Routes.AddContact> {
        val viewModel: AddContactViewModel = hiltViewModel()
        AddContactScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onContactSaved = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.EditContact> {
        val viewModel: EditContactViewModel = hiltViewModel()
        EditContactScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onContactDeleted = {
                navController.navigateTo(Routes.Contacts) { popUpTo(Routes.Home) }
            },
        )
    }
    composableWithDefaultTransitions<Routes.ContactImportOverview> {
        val viewModel: ContactImportOverviewViewModel = hiltViewModel()
        ContactImportOverviewScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onNavigateToSelect = { navController.navigateTo(Routes.ContactImportSelect) },
            onImportComplete = {
                navController.navigateTo(Routes.PayContacts) { popUpTo(Routes.Home) }
            },
        )
    }
    composableWithDefaultTransitions<Routes.ContactImportSelect> {
        val viewModel: ContactImportSelectViewModel = hiltViewModel()
        ContactImportSelectScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onImportComplete = {
                navController.navigateTo(Routes.PayContacts) { popUpTo(Routes.Home) }
            },
        )
    }
}

@Suppress("LongMethod")
private fun NavGraphBuilder.profile(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
) {
    composableWithDefaultTransitions<Routes.Profile> {
        val viewModel: ProfileViewModel = hiltViewModel()
        ProfileScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onEditProfile = { navController.navigateTo(Routes.EditProfile) },
            onConnectPubky = { navController.navigateTo(Routes.PubkyChoice) },
            onClickMilestone = { navController.navigateTo(Routes.MilestoneDetail(it.value)) },
        )
    }
    composableWithDefaultTransitions<Routes.MilestoneDetail> { backStackEntry ->
        val route: Routes.MilestoneDetail = backStackEntry.toRoute()
        val viewModel: ProfileViewModel = hiltViewModel()
        MilestoneDetailScreen(
            viewModel = viewModel,
            milestoneId = route.id,
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.ProfileIntro> {
        ProfileIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenProfileIntro(true)
                navController.navigateTo(Routes.PubkyChoice)
            },
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.PubkyChoice> {
        val viewModel: PubkyChoiceViewModel = hiltViewModel()
        PubkyChoiceScreen(
            viewModel = viewModel,
            onNavigateToCreateProfile = { navController.navigateTo(Routes.CreateProfile) },
            onNavigateToContactImportOverview = {
                navController.navigateTo(Routes.ContactImportOverview) { popUpTo(Routes.Home) }
            },
            onNavigateToPayContacts = {
                navController.navigateTo(Routes.PayContacts) { popUpTo(Routes.Home) }
            },
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.CreateProfile> {
        val viewModel: CreateProfileViewModel = hiltViewModel()
        CreateProfileScreen(
            viewModel = viewModel,
            onNavigateToPayContacts = {
                navController.navigateTo(Routes.PayContacts) { popUpTo(Routes.Home) }
            },
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.EditProfile> {
        val viewModel: EditProfileViewModel = hiltViewModel()
        EditProfileScreen(
            viewModel = viewModel,
            onBackClick = { navController.popBackStack() },
            onProfileDeleted = {
                navController.navigateTo(Routes.PubkyChoice) { popUpTo(Routes.Home) }
            },
        )
    }
    composableWithDefaultTransitions<Routes.PayContacts> {
        PayContactsScreen(
            onContinue = {
                navController.navigateTo(Routes.Profile) { popUpTo(Routes.Home) }
            },
            onBackClick = { navController.popBackStack() },
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
            onContinue = {
                settingsViewModel.setHasSeenShopIntro(true)
                navController.navigateTo(Routes.ShopDiscover)
            },
            onBackClick = {
                navController.popBackStack()
            }
        )
    }
    composableWithDefaultTransitions<Routes.ShopDiscover> {
        ShopDiscoverScreen(
            onBack = { navController.popBackStack() },
            navigateWebView = { page, title ->
                navController.navigateTo(Routes.ShopWebView(page = page, title = title))
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

private fun NavGraphBuilder.generalSettingsSubScreens(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.WidgetsSettings> {
        WidgetsSettingsScreen(navController)
    }

    composableWithDefaultTransitions<Routes.TagsSettings> {
        TagsSettingsScreen(navController)
    }
    composableWithDefaultTransitions<Routes.BackgroundPaymentsSettings> {
        BackgroundPaymentsSettings(
            onBack = { navController.popBackStack() },
        )
    }

    composableWithDefaultTransitions<Routes.BackgroundPaymentsIntro> {
        BackgroundPaymentsIntroScreen(
            onBack = { navController.popBackStack() },
            onContinue = {
                navController.navigateTo(Routes.BackgroundPaymentsSettings)
            }
        )
    }
}

private fun NavGraphBuilder.advancedSettingsSubScreens(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.CoinSelectPreference> {
        CoinSelectPreferenceScreen(navController)
    }
    composableWithDefaultTransitions<Routes.ElectrumConfig> {
        ElectrumConfigScreen(navController)
    }
    composableWithDefaultTransitions<Routes.RgsServer> {
        RgsServerScreen(navController)
    }
    composableWithDefaultTransitions<Routes.AddressTypePreference> {
        AddressTypePreferenceScreen(navController)
    }
    composableWithDefaultTransitions<Routes.AddressViewer> {
        AddressViewerScreen(navController)
    }
    composableWithDefaultTransitions<Routes.NodeInfo> {
        NodeInfoScreen(navController)
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

private fun NavGraphBuilder.pinManagement(navController: NavHostController) {
    composableWithDefaultTransitions<Routes.PinManagement> {
        PinManagementScreen(navController)
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
    navigationWithDefaultTransitions<Routes.ConnectionsNav>(
        startDestination = Routes.LightningConnections,
    ) {
        composableWithDefaultTransitions<Routes.LightningConnections> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.ConnectionsNav) }
            val viewModel = hiltViewModel<LightningConnectionsViewModel>(parentEntry)
            LightningConnectionsScreen(navController, viewModel)
        }
        composableWithDefaultTransitions<Routes.ChannelDetail> {
            val route = it.toRoute<Routes.ChannelDetail>()
            ChannelDetailScreen(
                channelId = route.channelId,
                navController = navController,
            )
        }
        composableWithDefaultTransitions<Routes.CloseConnection> {
            val route = it.toRoute<Routes.CloseConnection>()
            CloseConnectionScreen(
                channelId = route.channelId,
                navController = navController,
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
            onChannelClick = { channelId ->
                navController.navigateTo(Routes.ChannelDetail(channelId))
            },
            onBackClick = { navController.popBackStack() },
            onCloseClick = { navController.navigateToHome() },
        )
    }
    composableWithDefaultTransitions<Routes.ActivityExplore> {
        ActivityExploreScreen(
            route = it.toRoute(),
            onBackClick = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.authCheck(
    navController: NavHostController,
) {
    composableWithDefaultTransitions<Routes.AuthCheck> {
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
    composableWithDefaultTransitions<Routes.BuyIntro> {
        BuyIntroScreen(
            onBackClick = { navController.popBackStack() }
        )
    }
}

private fun NavGraphBuilder.update() {
    composableWithDefaultTransitions<Routes.CriticalUpdate> {
        CriticalUpdateScreen()
    }
}

private fun NavGraphBuilder.recoveryMode(
    navController: NavHostController,
    appViewModel: AppViewModel,
) {
    composableWithDefaultTransitions<Routes.RecoveryMode> {
        RecoveryModeScreen(
            onNavigateToSeed = {
                navController.navigateTo(Routes.RecoveryMnemonic)
            },
            appViewModel = appViewModel
        )
    }
    composableWithDefaultTransitions<Routes.RecoveryMnemonic> {
        RecoveryMnemonicScreen(
            onNavigateBack = {
                navController.popBackStack()
            }
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
            navigateResultScreen = { isSuccess ->
                if (isSuccess) {
                    navController.navigateTo(Routes.ReportIssueSuccess)
                } else {
                    navController.navigateTo(Routes.ReportIssueFailure)
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

@Suppress("LongMethod")
private fun NavGraphBuilder.widgets(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
) {
    composableWithDefaultTransitions<Routes.WidgetsIntro> {
        WidgetsIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenWidgetsIntro(true)
                navController.navigateTo(Routes.AddWidget)
            },
            onBackClick = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.AddWidget> {
        val showWidgets by settingsViewModel.showWidgets.collectAsStateWithLifecycle()
        AddWidgetsScreen(
            onWidgetSelected = { widgetType ->
                when (widgetType) {
                    WidgetType.BLOCK -> navController.navigateTo(Routes.BlocksPreview)
                    WidgetType.CALCULATOR -> navController.navigateTo(Routes.CalculatorPreview)
                    WidgetType.FACTS -> navController.navigateTo(Routes.FactsPreview)
                    WidgetType.NEWS -> navController.navigateTo(Routes.HeadlinesPreview)
                    WidgetType.PRICE -> navController.navigateTo(Routes.PricePreview)
                    WidgetType.WEATHER -> navController.navigateTo(Routes.WeatherPreview)
                    WidgetType.SUGGESTIONS -> navController.navigateTo(Routes.SuggestionsPreview)
                }
            },
            fiatSymbol = LocalCurrencies.current.currencySymbol,
            onBackClick = { navController.popBackStack() },
            showWidgets = showWidgets,
            onEnableInSettingsClick = { navController.navigateTo(Routes.WidgetsSettings) },
        )
    }
    composableWithDefaultTransitions<Routes.SuggestionsPreview> {
        val viewModel = hiltViewModel<SuggestionsViewModel>()
        SuggestionsPreviewScreen(
            suggestionsViewModel = viewModel,
            onClose = { navController.navigateToHome() },
            onBack = { navController.popBackStack() },
        )
    }
    composableWithDefaultTransitions<Routes.CalculatorPreview> {
        CalculatorPreviewScreen(
            onClose = { navController.navigateToHome() },
            onBack = { navController.popBackStack() },
            currencyViewModel = currencyViewModel
        )
    }
    navigationWithDefaultTransitions<Routes.Headlines>(
        startDestination = Routes.HeadlinesPreview
    ) {
        composableWithDefaultTransitions<Routes.HeadlinesPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Headlines) }
            val viewModel = hiltViewModel<HeadlinesViewModel>(parentEntry)

            HeadlinesPreviewScreen(
                headlinesViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigateTo(Routes.HeadlinesEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.HeadlinesEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Headlines) }
            val viewModel = hiltViewModel<HeadlinesViewModel>(parentEntry)

            HeadlinesEditScreen(
                headlinesViewModel = viewModel,
                onBack = { navController.popBackStack() },
                navigatePreview = {
                    navController.navigateTo(Routes.HeadlinesPreview)
                }
            )
        }
    }
    navigationWithDefaultTransitions<Routes.Facts>(
        startDestination = Routes.FactsPreview
    ) {
        composableWithDefaultTransitions<Routes.FactsPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Facts) }
            val viewModel = hiltViewModel<FactsViewModel>(parentEntry)

            FactsPreviewScreen(
                factsViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigateTo(Routes.FactsEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.FactsEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Facts) }
            val viewModel = hiltViewModel<FactsViewModel>(parentEntry)

            FactsEditScreen(
                factsViewModel = viewModel,
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigateTo(Routes.FactsPreview) }
            )
        }
    }
    navigationWithDefaultTransitions<Routes.Blocks>(
        startDestination = Routes.BlocksPreview
    ) {
        composableWithDefaultTransitions<Routes.BlocksPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Blocks) }
            val viewModel = hiltViewModel<BlocksViewModel>(parentEntry)

            BlocksPreviewScreen(
                blocksViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigateTo(Routes.BlocksEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.BlocksEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Blocks) }
            val viewModel = hiltViewModel<BlocksViewModel>(parentEntry)

            BlocksEditScreen(
                blocksViewModel = viewModel,
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigateTo(Routes.BlocksPreview) }
            )
        }
    }
    navigationWithDefaultTransitions<Routes.Weather>(
        startDestination = Routes.WeatherPreview
    ) {
        composableWithDefaultTransitions<Routes.WeatherPreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Weather) }
            val viewModel = hiltViewModel<WeatherViewModel>(parentEntry)

            WeatherPreviewScreen(
                weatherViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigateTo(Routes.WeatherEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.WeatherEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Weather) }
            val viewModel = hiltViewModel<WeatherViewModel>(parentEntry)

            WeatherEditScreen(
                weatherViewModel = viewModel,
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigateTo(Routes.WeatherPreview) }
            )
        }
    }
    navigationWithDefaultTransitions<Routes.Price>(
        startDestination = Routes.PricePreview
    ) {
        composableWithDefaultTransitions<Routes.PricePreview> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Price) }
            val viewModel = hiltViewModel<PriceViewModel>(parentEntry)

            PricePreviewScreen(
                priceViewModel = viewModel,
                onClose = { navController.navigateToHome() },
                onBack = { navController.popBackStack() },
                navigateEditWidget = { navController.navigateTo(Routes.PriceEdit) },
            )
        }
        composableWithDefaultTransitions<Routes.PriceEdit> {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.Price) }
            val viewModel = hiltViewModel<PriceViewModel>(parentEntry)
            PriceEditScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                navigatePreview = { navController.navigateTo(Routes.PricePreview) }
            )
        }
    }
}

// endregion

// region events
fun NavController.navigateToHome() {
    val popped = popBackStack<Routes.Home>(inclusive = false)
    if (!popped) {
        navigateTo(Routes.Home) { popUpTo(graph.startDestinationId) }
    }
}

fun NavController.navigateToAllActivity(onClearFilters: () -> Unit) {
    onClearFilters()
    navigateTo(Routes.AllActivity)
}

/**
 * Navigates to [route] with [launchSingleTop] always enabled to prevent
 * duplicate destinations on the back stack (e.g. from double-taps).
 *
 * Use the optional [builder] to add extra nav options like `popUpTo`.
 */
inline fun <reified T : Any> NavController.navigateTo(
    route: T,
    noinline builder: NavOptionsBuilder.() -> Unit = {},
) {
    navigate(route) {
        builder()
        launchSingleTop = true
    }
}

fun NavController.navigateToProfile() = navigateTo(Routes.Profile)

fun NavController.navigateToPinManagement() = navigateTo(Routes.PinManagement)

fun NavController.navigateToAuthCheck(
    showLogoOnPin: Boolean = false,
    requirePin: Boolean = false,
    requireBiometrics: Boolean = false,
    onSuccessActionId: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) = navigateTo(
    route = Routes.AuthCheck(
        showLogoOnPin = showLogoOnPin,
        requirePin = requirePin,
        requireBiometrics = requireBiometrics,
        onSuccessActionId = onSuccessActionId,
    ),
    builder = builder,
)

fun NavController.navigateToDefaultUnitSettings() = navigateTo(Routes.DefaultUnitSettings)

fun NavController.navigateToLocalCurrencySettings() = navigateTo(Routes.LocalCurrencySettings)

fun NavController.navigateToBackupSettings() = navigateTo(Routes.BackupSettings)

fun NavController.navigateToOrderDetail(id: String) = navigateTo(Routes.OrderDetail(id))

fun NavController.navigateToCjitDetail(id: String) = navigateTo(Routes.CjitDetail(id))

fun NavController.navigateToDevSettings() = navigateTo(Routes.DevSettings)

fun NavController.navigateToTransferSavingsIntro() = navigateTo(Routes.SavingsIntro)

fun NavController.navigateToTransferSavingsAvailability() = navigateTo(Routes.SavingsAvailability)

fun NavController.navigateToTransferSpendingIntro() = navigateTo(Routes.SpendingIntro)

fun NavController.navigateToTransferSpendingAmount() = navigateTo(Routes.SpendingAmount)

fun NavController.navigateToTransferIntro() = navigateTo(Routes.TransferIntro)

fun NavController.navigateToTransferFunding() = navigateTo(Routes.Funding)

fun NavController.navigateToActivityItem(id: String) = navigateTo(Routes.ActivityDetail(id))

fun NavController.navigateToActivityExplore(id: String) = navigateTo(Routes.ActivityExplore(id))

fun NavController.navigateToLogDetail(fileName: String) = navigateTo(Routes.LogDetail(fileName))

fun NavController.navigateToTransactionSpeedSettings() = navigateTo(Routes.TransactionSpeedSettings)

fun NavController.navigateToCustomFeeSettings() = navigateTo(Routes.CustomFeeSettings)

fun NavController.navigateToWidgetsSettings() = navigateTo(Routes.WidgetsSettings)

fun NavController.navigateToQuickPaySettings(hasSeenIntro: Boolean = true) =
    navigateTo(if (hasSeenIntro) Routes.QuickPaySettings else Routes.QuickPayIntro)

fun NavController.navigateToTagsSettings() = navigateTo(Routes.TagsSettings)

fun NavController.navigateToLanguageSettings() = navigateTo(Routes.LanguageSettings)

// endregion

@Stable
sealed interface Routes {
    @Serializable
    data object Home : Routes

    @Serializable
    data object Savings : Routes

    @Serializable
    data object Spending : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data object NodeInfo : Routes

    @Serializable
    data object TransactionSpeedSettings : Routes

    @Serializable
    data object WidgetsSettings : Routes

    @Serializable
    data object TagsSettings : Routes

    @Serializable
    data object CoinSelectPreference : Routes

    @Serializable
    data object ElectrumConfig : Routes

    @Serializable
    data object RgsServer : Routes

    @Serializable
    data object AddressTypePreference : Routes

    @Serializable
    data object AddressViewer : Routes

    @Serializable
    data object CustomFeeSettings : Routes

    @Serializable
    data object PinManagement : Routes

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
    data class ChannelDetail(val channelId: String) : Routes

    @Serializable
    data class CloseConnection(val channelId: String) : Routes

    @Serializable
    data object DevSettings : Routes

    @Serializable
    data object LdkDebug : Routes

    @Serializable
    data object VssDebug : Routes

    @Serializable
    data object ProbingTool : Routes

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
    data class LnurlChannel(val uri: String, val callback: String, val k1: String) : Routes

    @Serializable
    data class ActivityDetail(val id: String) : Routes

    @Serializable
    data class ActivityExplore(val id: String) : Routes

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
    data object LanguageSettings : Routes

    @Serializable
    data object Contacts : Routes

    @Serializable
    data object ContactsIntro : Routes

    @Serializable
    data class ContactDetail(val publicKey: String) : Routes

    @Serializable
    data object Profile : Routes

    @Serializable
    data class MilestoneDetail(val id: String) : Routes

    @Serializable
    data object ProfileIntro : Routes

    @Serializable
    data object PubkyChoice : Routes

    @Serializable
    data object CreateProfile : Routes

    @Serializable
    data object EditProfile : Routes

    @Serializable
    data object PayContacts : Routes

    @Serializable
    data class AddContact(val publicKey: String) : Routes

    @Serializable
    data class EditContact(val publicKey: String) : Routes

    @Serializable
    data object ContactImportOverview : Routes

    @Serializable
    data object ContactImportSelect : Routes

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
    data object SuggestionsPreview : Routes

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

    @Serializable
    data object CriticalUpdate : Routes

    @Serializable
    data object RecoveryMode : Routes

    @Serializable
    data object RecoveryMnemonic : Routes

    @Serializable
    data object BackgroundPaymentsIntro : Routes

    @Serializable
    data object BackgroundPaymentsSettings : Routes

    @Serializable
    data object AllActivity : Routes
}
