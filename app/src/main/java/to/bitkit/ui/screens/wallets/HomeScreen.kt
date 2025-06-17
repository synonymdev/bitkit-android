package to.bitkit.ui.screens.wallets

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.requiresPermission
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.DrawerItem
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToQrScanner
import to.bitkit.ui.navigateToSettings
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.navigateToTransferIntro
import to.bitkit.ui.navigateToTransferSavingsAvailability
import to.bitkit.ui.navigateToTransferSavingsIntro
import to.bitkit.ui.navigateToTransferSpendingAmount
import to.bitkit.ui.navigateToTransferSpendingIntro
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.screens.wallets.activity.components.ActivityListSimple
import to.bitkit.ui.screens.widgets.DragAndDropWidget
import to.bitkit.ui.screens.widgets.DragDropColumn
import to.bitkit.ui.screens.widgets.blocks.BlockCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.facts.FactsCard
import to.bitkit.ui.screens.widgets.headlines.HeadlineCard
import to.bitkit.ui.screens.widgets.price.PriceCard
import to.bitkit.ui.screens.widgets.weather.WeatherCard
import to.bitkit.ui.settingsViewModel
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
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel


@Composable
fun HomeScreen(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
    rootNavController: NavController,
) {
    val uiState: MainUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeUiState: HomeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val hasSeenWidgetsIntro by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        val walletNavController = rememberNavController()
        NavHost(
            navController = walletNavController,
            startDestination = HomeRoutes.Home,
        ) {
            composable<HomeRoutes.Home> {
                val context = LocalContext.current
                val hasSeenTransferIntro by settingsViewModel.hasSeenTransferIntro.collectAsStateWithLifecycle()
                val hasSeenShopIntro by settingsViewModel.hasSeenShopIntro.collectAsStateWithLifecycle()
                val hasSeenProfileIntro by settingsViewModel.hasSeenProfileIntro.collectAsStateWithLifecycle()
                val quickPayIntroSeen by settingsViewModel.quickPayIntroSeen.collectAsStateWithLifecycle()

                HomeContentView(
                    mainUiState = uiState,
                    homeUiState = homeUiState,
                    rootNavController = rootNavController,
                    walletNavController = walletNavController,
                    drawerState = drawerState,
                    onRefresh = {
                        walletViewModel.onPullToRefresh()
                        homeViewModel.refreshWidgets()
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

                            Suggestion.BACK_UP -> {
                                appViewModel.showSheet(BottomSheetType.Backup)
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
                                if (!hasSeenProfileIntro) {
                                    rootNavController.navigate(Routes.ProfileIntro)
                                } else {
                                    rootNavController.navigate(Routes.CreateProfile)
                                }
                            }

                            Suggestion.SHOP -> {
                                if (!hasSeenShopIntro) {
                                    rootNavController.navigate(Routes.ShopIntro)
                                } else {
                                    rootNavController.navigate(Routes.ShopDiscover)
                                }
                            }

                            Suggestion.QUICK_PAY -> {
                                if (!quickPayIntroSeen) {
                                    rootNavController.navigate(Routes.QuickPayIntro)
                                } else {
                                    rootNavController.navigate(Routes.QuickPaySettings)
                                }
                            }
                        }
                    },
                    onClickAddWidget = {
                        if (!hasSeenWidgetsIntro) {
                            rootNavController.navigate(Routes.WidgetsIntro)
                        } else {
                            rootNavController.navigate(Routes.AddWidget)
                        }
                    },
                    onClickConfirmEdit = {
                        homeViewModel.confirmWidgetOrder()
                    },
                    onClickEnableEdit = {
                        homeViewModel.enableEditMode()
                    },
                    onClickEditWidget = { widgetType ->
                        when (widgetType) {
                            WidgetType.BLOCK -> rootNavController.navigate(Routes.BlocksPreview)
                            WidgetType.CALCULATOR -> rootNavController.navigate(Routes.CalculatorPreview)
                            WidgetType.FACTS -> rootNavController.navigate(Routes.FactsPreview)
                            WidgetType.NEWS -> rootNavController.navigate(Routes.HeadlinesPreview)
                            WidgetType.PRICE -> rootNavController.navigate(Routes.PricePreview)
                            WidgetType.WEATHER -> rootNavController.navigate(Routes.WeatherPreview)
                        }
                    },
                    onClickDeleteWidget = { widgetType ->
                        homeViewModel.displayAlertDeleteWidget(widgetType)
                    },
                    onMoveWidget = { fromIndex, toIndex ->
                        homeViewModel.moveWidget(fromIndex, toIndex)
                    }
                )
            }
            composable<HomeRoutes.Savings>(
                enterTransition = { screenSlideIn },
                exitTransition = { screenSlideOut },
            ) {
                val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
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
                val hasSeenSavingsIntro by settingsViewModel.hasSeenSavingsIntro.collectAsStateWithLifecycle()
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

        homeUiState.deleteWidgetAlert?.let { type ->
            AppAlertDialog(
                title = stringResource(R.string.widgets__delete__title),
                text = stringResource(R.string.widgets__delete__description).replace(
                    "{name}",
                    stringResource(type.title)
                ),
                confirmText = stringResource(R.string.common__delete_yes),
                dismissText = stringResource(R.string.common__dialog_cancel),
                onConfirm = { homeViewModel.deleteWidget(widgetType = type) },
                onDismiss = {
                    homeViewModel.dismissAlertDeleteWidget()
                },
            )
        }

        TabBar(
            onSendClick = { appViewModel.showSheet(BottomSheetType.Send()) },
            onReceiveClick = { appViewModel.showSheet(BottomSheetType.Receive) },
            onScanClick = { rootNavController.navigateToQrScanner() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
        )


        // Drawer overlay and content - moved from AppScaffold to here
        // Semi-transparent overlay when drawer is open
        AnimatedVisibility(
            visible = drawerState.currentValue == DrawerValue.Open,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f) // Higher z-index than TabBar
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Colors.Black50)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        scope.launch {
                            drawerState.close()
                        }
                    }
            )
        }

        // Right-side drawer content
        AnimatedVisibility(
            visible = drawerState.currentValue == DrawerValue.Open,
            enter = slideInHorizontally(
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it }
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .zIndex(11f) // Higher z-index than overlay
        ) {
            DrawerContent(
                walletNavController = walletNavController,
                rootNavController = rootNavController,
                drawerState = drawerState,
                onClickAddWidget = {
                    if (!hasSeenWidgetsIntro) {
                        rootNavController.navigate(Routes.WidgetsIntro)
                    } else {
                        rootNavController.navigate(Routes.AddWidget)
                    }
                }
            )
        }
    }
}


@Composable
private fun DrawerContent(
    walletNavController: NavController,
    rootNavController: NavController,
    drawerState: DrawerState,
    onClickAddWidget: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerWidth = 200.dp

    Column(
        modifier = Modifier
            .width(drawerWidth)
            .fillMaxHeight()
            .background(Colors.Brand)
    ) {
        VerticalSpacer(60.dp)

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__wallet),
            iconRes = R.drawable.ic_coins,
            modifier = Modifier.clickable {
                scope.launch { drawerState.close() }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__activity),
            iconRes = R.drawable.ic_heartbeat,
            modifier = Modifier.clickable {
                walletNavController.navigate(HomeRoutes.AllActivity)
                scope.launch { drawerState.close() }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__contacts),
            iconRes = R.drawable.ic_users // TODO IMPLEMENT CONTACTS
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__profile),
            iconRes = R.drawable.ic_user_square, // TODO IMPLEMENT
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__widgets),
            iconRes = R.drawable.ic_stack,
            modifier = Modifier.clickable {
                onClickAddWidget()
                scope.launch { drawerState.close() }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__shop),
            iconRes = R.drawable.ic_store_front,
            modifier = Modifier.clickable {
                rootNavController.navigate(Routes.ShopDiscover)
                scope.launch { drawerState.close() }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__settings),
            iconRes = R.drawable.ic_settings,
            modifier = Modifier.clickable {
                rootNavController.navigateToSettings()
                scope.launch { drawerState.close() }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        //TODO NAVIGATE TO APP STATE
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeContentView(
    mainUiState: MainUiState,
    homeUiState: HomeUiState,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    onClickAddWidget: () -> Unit,
    onClickEnableEdit: () -> Unit,
    onClickConfirmEdit: () -> Unit,
    onClickEditWidget: (WidgetType) -> Unit,
    onClickDeleteWidget: (WidgetType) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
    rootNavController: NavController,
    walletNavController: NavController,
    drawerState: DrawerState,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    AppScaffold(
        titleText = stringResource(R.string.slashtags__your_name_capital),
        actions = {
            IconButton(onClick = {
                scope.launch {
                    drawerState.open()
                }
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = stringResource(R.string.settings__settings),
                )
            }
        },
    ) {
        RequestNotificationPermissions()
        val balances = LocalBalances.current
        val app = appViewModel ?: return@AppScaffold
        val settings = settingsViewModel ?: return@AppScaffold
        val showEmptyStateSetting by settings.showEmptyState.collectAsStateWithLifecycle()
        val showEmptyState by remember(balances.totalSats, showEmptyStateSetting) {
            derivedStateOf {
                showEmptyStateSetting && balances.totalSats == 0uL
            }
        }
        val pullRefreshState = rememberPullRefreshState(refreshing = mainUiState.isRefreshing, onRefresh = onRefresh)

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
                BalanceHeaderView(
                    sats = balances.totalSats.toLong(),
                    showEyeIcon = true,
                    modifier = Modifier.fillMaxWidth()
                )
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

                    AnimatedVisibility(homeUiState.suggestions.isNotEmpty()) {
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
                                items(homeUiState.suggestions, key = { it.name }) { item ->
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

                    if (homeUiState.showWidgets) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text13Up(
                                stringResource(R.string.widgets__widgets),
                                color = Colors.White64
                            )

                            if (homeUiState.isEditingWidgets) {
                                IconButton(
                                    onClick = onClickConfirmEdit
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = onClickEnableEdit
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_sort_ascending),
                                        contentDescription = null
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (homeUiState.isEditingWidgets) {
                            DragDropColumn(
                                items = homeUiState.widgetsWithPosition,
                                onMove = onMoveWidget,
                                modifier = Modifier.fillMaxWidth()
                            ) { widgetWithPosition, isDragging ->
                                DragAndDropWidget(
                                    iconRes = widgetWithPosition.type.iconRes,
                                    title = stringResource(widgetWithPosition.type.title),
                                    onClickSettings = { onClickEditWidget(widgetWithPosition.type) },
                                    onClickDelete = { onClickDeleteWidget(widgetWithPosition.type) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = if (isDragging) 0.8f else 1.0f
                                        }
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                homeUiState.widgetsWithPosition.forEach { widgetsWithPosition ->
                                    when (widgetsWithPosition.type) {
                                        WidgetType.BLOCK -> {
                                            homeUiState.currentBlock?.run {
                                                BlockCard(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles,
                                                    showBlock = homeUiState.blocksPreferences.showBlock,
                                                    showTime = homeUiState.blocksPreferences.showTime,
                                                    showDate = homeUiState.blocksPreferences.showDate,
                                                    showTransactions = homeUiState.blocksPreferences.showTransactions,
                                                    showSize = homeUiState.blocksPreferences.showSize,
                                                    showSource = homeUiState.blocksPreferences.showSource,
                                                    time = time,
                                                    date = date,
                                                    transactions = transactionCount,
                                                    size = size,
                                                    source = source,
                                                    block = height
                                                )
                                            }
                                        }

                                        WidgetType.CALCULATOR -> {
                                            currencyViewModel?.let {
                                                CalculatorCard(
                                                    currencyViewModel = it,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles
                                                )
                                            }
                                        }

                                        WidgetType.FACTS -> {
                                            homeUiState.currentFact?.run {
                                                FactsCard(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles,
                                                    showSource = homeUiState.factsPreferences.showSource,
                                                    headline = homeUiState.currentFact,
                                                )
                                            }
                                        }

                                        WidgetType.NEWS -> {
                                            homeUiState.currentArticle?.run {
                                                HeadlineCard(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles,
                                                    showTime = homeUiState.headlinePreferences.showTime,
                                                    showSource = homeUiState.headlinePreferences.showSource,
                                                    headline = title,
                                                    time = timeAgo,
                                                    source = publisher,
                                                    link = link
                                                )
                                            }
                                        }

                                        WidgetType.PRICE -> {
                                            homeUiState.currentPrice?.run {
                                                PriceCard(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles,
                                                    pricePreferences = homeUiState.pricePreferences,
                                                    priceDTO = homeUiState.currentPrice,
                                                )
                                            }
                                        }

                                        WidgetType.WEATHER -> {
                                            homeUiState.currentWeather?.run {
                                                WeatherCard(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    showWidgetTitle = homeUiState.showWidgetTitles,
                                                    weatherModel = this,
                                                    preferences = homeUiState.weatherPreferences
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        TertiaryButton(
                            text = stringResource(R.string.widgets__add),
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_plus),
                                    contentDescription = null,
                                    tint = Colors.White80
                                )
                            },
                            onClick = onClickAddWidget
                        )
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
                    onClose = { settings.setShowEmptyState(false) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                )
            }

            PullRefreshIndicator(
                refreshing = mainUiState.isRefreshing,
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
            mainUiState = MainUiState(),
            rootNavController = rememberNavController(),
            walletNavController = rememberNavController(),
            onRefresh = {},
            drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
            onClickSuggestion = {},
            onRemoveSuggestion = {},
            onClickAddWidget = {},
            homeUiState = HomeUiState(),
            onClickConfirmEdit = {},
            onClickEnableEdit = {},
            onClickEditWidget = {},
            onClickDeleteWidget = {},
            onMoveWidget = { _, _ -> },
        )
    }
}
