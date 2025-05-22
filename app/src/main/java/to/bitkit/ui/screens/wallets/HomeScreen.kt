package to.bitkit.ui.screens.wallets

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.requiresPermission
import to.bitkit.models.Suggestion
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToQrScanner
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.navigateToTransferIntro
import to.bitkit.ui.navigateToTransferSavingsAvailability
import to.bitkit.ui.navigateToTransferSavingsIntro
import to.bitkit.ui.navigateToTransferSpendingAmount
import to.bitkit.ui.navigateToTransferSpendingIntro
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.screens.wallets.activity.DateRangeSelectorSheet
import to.bitkit.ui.screens.wallets.activity.TagSelectorSheet
import to.bitkit.ui.screens.wallets.activity.components.ActivityListSimple
import to.bitkit.ui.screens.wallets.receive.ReceiveQrSheet
import to.bitkit.ui.screens.wallets.send.SendOptionsView
import to.bitkit.ui.settings.pin.PinNavigationSheet
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.screenSlideIn
import to.bitkit.ui.utils.screenSlideOut
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.WalletViewModel


@Composable
fun HomeScreen(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    rootNavController: NavController,
) {
    val uiState: MainUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
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
                            appViewModel.hideSheet()
                            txSheet?.let { appViewModel.showNewTransactionSheet(it) }
                        }
                    )
                }

                is BottomSheetType.Receive -> {
                    ReceiveQrSheet(
                        walletState = uiState,
                        navigateToExternalConnection = {
                            rootNavController.navigate(Routes.ExternalConnection)
                        }
                    )
                }

                is BottomSheetType.ActivityDateRangeSelector -> DateRangeSelectorSheet()
                is BottomSheetType.ActivityTagSelector -> TagSelectorSheet()

                is BottomSheetType.PinSetup -> PinNavigationSheet(
                    onDismiss = { appViewModel.hideSheet() },
                )

                null -> Unit
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val walletNavController = rememberNavController()
            NavHost(
                navController = walletNavController,
                startDestination = HomeRoutes.Home,
            ) {
                composable<HomeRoutes.Home> {
                    val homeViewModel: HomeViewModel = hiltViewModel()
                    val suggestions by homeViewModel.suggestions.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    val hasSeenTransferIntro by appViewModel.hasSeenTransferIntro.collectAsState()
                    val quickpayIntroSeen by appViewModel.quickpayIntroSeen.collectAsStateWithLifecycle()

                    HomeContentView(
                        uiState = uiState,
                        suggestions = suggestions,
                        rootNavController = rootNavController,
                        walletNavController = walletNavController,
                        onRefresh = {
                            walletViewModel.onPullToRefresh()
                            activityListViewModel.syncLdkNodePayments()
                        },
                        onRemoveSuggestion = { suggestion ->
                            homeViewModel.removeSuggestion(suggestion)
                        },
                        onClickSuggestion = { suggestion ->
                            when (suggestion) {
                                Suggestion.BUY -> {
                                    rootNavController.navigate(Routes.BuyIntro)
                                }

                                Suggestion.SPEND -> {
                                    if (!hasSeenTransferIntro) {
                                        rootNavController.navigateToTransferIntro()
                                    } else {
                                        rootNavController.navigateToTransferFunding()
                                    }
                                }

                                Suggestion.BACK_UP -> { //TODO IMPLEMENT BOTTOM SHEET
                                    rootNavController.navigate(Routes.BackupWalletSettings)
                                }

                                Suggestion.SECURE -> {
                                    appViewModel.showSheet(BottomSheetType.PinSetup)
                                }

                                Suggestion.SUPPORT -> {
                                    rootNavController.navigate(Routes.Support)
                                }

                                Suggestion.INVITE -> {
                                    shareText(
                                        context,
                                        context.getString(R.string.settings__about__shareText)
                                            .replace("{appStoreUrl}", Env.APP_STORE_URL)
                                            .replace("{playStoreUrl}", Env.PLAY_STORE_URL)
                                    )
                                }

                                Suggestion.PROFILE -> {
                                    //TODO IMPLEMENT
                                    appViewModel.toast(Exception("Coming soon: PROFILE"))
                                }

                                Suggestion.SHOP -> {
                                    //TODO CREATE SCREEN https://www.figma.com/design/ltqvnKiejWj0JQiqtDf2JJ/Bitkit-Wallet?node-id=31760-206181&t=RBb2MCjd1HaFYX59-4
                                    val intent = Intent(Intent.ACTION_VIEW, Env.BIT_REFILL_URL.toUri())
                                    context.startActivity(intent)
                                }

                                Suggestion.QUICK_PAY -> {
                                    if (!quickpayIntroSeen) {
                                        rootNavController.navigate(Routes.QuickPayIntro)
                                    } else {
                                        rootNavController.navigate(Routes.QuickPaySettings)
                                    }
                                }
                            }
                        },
                    )
                }
                composable<HomeRoutes.Savings>(
                    enterTransition = { screenSlideIn },
                    exitTransition = { screenSlideOut },
                ) {
                    val hasSeenSpendingIntro by appViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
                    SavingsWalletScreen(
                        onAllActivityButtonClick = { walletNavController.navigate(HomeRoutes.AllActivity) },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onEmptyActivityRowClick = { appViewModel.showSheet(BottomSheetType.Receive) },
                        onTransferToSpendingClick = {
                            if (!hasSeenSpendingIntro) {
                                rootNavController.navigateToTransferSpendingIntro()
                            } else {
                                rootNavController.navigateToTransferSpendingAmount()
                            }
                        },
                        onBackClick = { walletNavController.popBackStack() },
                    )
                }
                composable<HomeRoutes.Spending>(
                    enterTransition = { screenSlideIn },
                    exitTransition = { screenSlideOut },
                ) {
                    val hasSeenSavingsIntro by appViewModel.hasSeenSavingsIntro.collectAsStateWithLifecycle()
                    SpendingWalletScreen(
                        uiState = uiState,
                        onAllActivityButtonClick = { walletNavController.navigate(HomeRoutes.AllActivity) },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onEmptyActivityRowClick = { appViewModel.showSheet(BottomSheetType.Receive) },
                        onTransferToSavingsClick = {
                            if (!hasSeenSavingsIntro) {
                                rootNavController.navigateToTransferSavingsIntro()
                            } else {
                                rootNavController.navigateToTransferSavingsAvailability()
                            }
                        },
                        onBackClick = { walletNavController.popBackStack() },
                    )
                }
                composable<HomeRoutes.AllActivity>(
                    enterTransition = { screenSlideIn },
                    exitTransition = { screenSlideOut },
                ) {
                    AllActivityScreen(
                        viewModel = activityListViewModel,
                        onBack = {
                            activityListViewModel.clearFilters()
                            walletNavController.popBackStack()
                        },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                    )
                }
            }

            TabBar(
                onSendClick = { appViewModel.showSheet(BottomSheetType.Send()) },
                onReceiveClick = { appViewModel.showSheet(BottomSheetType.Receive) },
                onScanClick = { rootNavController.navigateToQrScanner() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeContentView(
    uiState: MainUiState,
    suggestions: List<Suggestion>,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    rootNavController: NavController,
    walletNavController: NavController,
    onRefresh: () -> Unit,
) {
    AppScaffold(
        navController = rootNavController,
        titleText = stringResource(R.string.slashtags__your_name_capital),
    ) {
        RequestNotificationPermissions()
        val balances = LocalBalances.current
        val app = appViewModel ?: return@AppScaffold
        val showEmptyStateSetting by app.showEmptyState.collectAsStateWithLifecycle()
        val showEmptyState by remember(balances.totalSats, showEmptyStateSetting) {
            derivedStateOf {
                showEmptyStateSetting && balances.totalSats == 0uL
            }
        }
        val pullRefreshState = rememberPullRefreshState(refreshing = uiState.isRefreshing, onRefresh = onRefresh)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                BalanceHeaderView(sats = balances.totalSats.toLong(), modifier = Modifier.fillMaxWidth())
                if (!showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        WalletBalanceView(
                            title = stringResource(R.string.wallet__savings__title),
                            sats = balances.totalOnchainSats.toLong(),
                            icon = painterResource(id = R.drawable.ic_btc_circle),
                            modifier = Modifier
                                .clickableAlpha { walletNavController.navigate(HomeRoutes.Savings) }
                                .padding(vertical = 4.dp)
                        )
                        VerticalDivider()
                        WalletBalanceView(
                            title = stringResource(R.string.wallet__spending__title),
                            sats = balances.totalLightningSats.toLong(),
                            icon = painterResource(id = R.drawable.ic_ln_circle),
                            modifier = Modifier
                                .clickableAlpha { walletNavController.navigate(HomeRoutes.Spending) }
                                .padding(vertical = 4.dp)
                                .padding(start = 16.dp)
                        )
                    }

                    AnimatedVisibility(suggestions.isNotEmpty()) {
                        val state = rememberLazyListState()
                        val snapBehavior = rememberSnapFlingBehavior(
                            lazyListState = state,
                            snapPosition = SnapPosition.Start
                        )

                        Column {
                            Spacer(modifier = Modifier.height(32.dp))
                            Text13Up(stringResource(R.string.cards__suggestions), color = Colors.White64)
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                state = state,
                                flingBehavior = snapBehavior
                            ) {
                                items(suggestions, key = { it.name }) { item ->
                                    SuggestionCard(
                                        gradientColor = item.color,
                                        title = stringResource(item.title),
                                        description = stringResource(item.description),
                                        icon = item.icon,
                                        onClose = { onRemoveSuggestion(item) },
                                        onClick = { onClickSuggestion(item) },
                                        modifier = Modifier.testTag("SUGGESTION_${item.name}")
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Text13Up(stringResource(R.string.wallet__activity), color = Colors.White64)
                    Spacer(modifier = Modifier.height(16.dp))
                    val activity = activityListViewModel ?: return@Column
                    val latestActivities by activity.latestActivities.collectAsStateWithLifecycle()
                    ActivityListSimple(
                        items = latestActivities,
                        onAllActivityClick = { walletNavController.navigate(HomeRoutes.AllActivity) },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onEmptyActivityRowClick = { app.showSheet(BottomSheetType.Receive) },
                    )

                    // Scrollable empty space behind bottom buttons
                    Spacer(modifier = Modifier.height(120.dp))
                }
            }
            if (showEmptyState) {
                EmptyStateView(
                    text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
                    onClose = { app.setShowEmptyState(false) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                )
            }

            PullRefreshIndicator(
                refreshing = uiState.isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun RequestNotificationPermissions() {
    val context = LocalContext.current

    // Only check permission if running on Android 13+ (SDK 33+)
    val requiresPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.requiresPermission(Manifest.permission.POST_NOTIFICATIONS)

    var isGranted by remember { mutableStateOf(!requiresPermission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted = it
    }

    LaunchedEffect(isGranted) {
        if (!isGranted && requiresPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

object HomeRoutes {
    @Serializable
    data object Home

    @Serializable
    data object Savings

    @Serializable
    data object Spending

    @Serializable
    data object AllActivity
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeContentViewPreview() {
    AppThemeSurface {
        HomeContentView(
            uiState = MainUiState(),
            suggestions = Suggestion.entries.toList(),
            rootNavController = rememberNavController(),
            walletNavController = rememberNavController(),
            onRefresh = {},
            onClickSuggestion = {},
            onRemoveSuggestion = {},
        )
    }
}
