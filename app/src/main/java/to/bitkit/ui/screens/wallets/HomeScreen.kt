package to.bitkit.ui.screens.wallets

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.synonym.bitkitcore.Activity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.Routes
import to.bitkit.ui.components.AppStatus
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.StatusBarSpacer
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.TopBarSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.navigateToTransferIntro
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.screens.wallets.activity.components.ActivityListSimple
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.screens.wallets.sheets.HighBalanceWarningSheet
import to.bitkit.ui.screens.widgets.DragAndDropWidget
import to.bitkit.ui.screens.widgets.DragDropColumn
import to.bitkit.ui.screens.widgets.blocks.BlockCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.facts.FactsCard
import to.bitkit.ui.screens.widgets.headlines.HeadlineCard
import to.bitkit.ui.screens.widgets.price.PriceCard
import to.bitkit.ui.screens.widgets.weather.WeatherCard
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun HomeScreen(
    mainUiState: MainUiState,
    drawerState: DrawerState,
    rootNavController: NavController,
    walletNavController: NavHostController,
    settingsViewModel: SettingsViewModel,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasSeenTransferIntro by settingsViewModel.hasSeenTransferIntro.collectAsStateWithLifecycle()
    val hasSeenShopIntro by settingsViewModel.hasSeenShopIntro.collectAsStateWithLifecycle()
    val hasSeenProfileIntro by settingsViewModel.hasSeenProfileIntro.collectAsStateWithLifecycle()
    val hasSeenWidgetsIntro: Boolean by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
    val quickPayIntroSeen by settingsViewModel.quickPayIntroSeen.collectAsStateWithLifecycle()
    val latestActivities by activityListViewModel.latestActivities.collectAsStateWithLifecycle()
    val hazeState = rememberHazeState()

    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    homeUiState.deleteWidgetAlert?.let { type ->
        DeleteWidgetAlert(type, homeViewModel)
    }

    Content(
        mainUiState = mainUiState,
        homeUiState = homeUiState,
        rootNavController = rootNavController,
        walletNavController = walletNavController,
        drawerState = drawerState,
        hazeState = hazeState,
        latestActivities = latestActivities,
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
        onClickEnableEdit = homeViewModel::enableEditMode,
        onClickConfirmEdit = homeViewModel::confirmWidgetOrder,
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
        },
        onDismissEmptyState = homeViewModel::dismissEmptyState,
        onDismissHighBalanceSheet = homeViewModel::dismissHighBalanceSheet,
        onClickEmptyActivityRow = { appViewModel.showSheet(BottomSheetType.Receive) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
private fun Content(
    mainUiState: MainUiState,
    homeUiState: HomeUiState,
    rootNavController: NavController,
    walletNavController: NavController,
    drawerState: DrawerState,
    hazeState: HazeState,
    latestActivities: List<Activity>?,
    onRefresh: () -> Unit = {},
    onRemoveSuggestion: (Suggestion) -> Unit = {},
    onClickSuggestion: (Suggestion) -> Unit = {},
    onClickAddWidget: () -> Unit = {},
    onClickEnableEdit: () -> Unit = {},
    onClickConfirmEdit: () -> Unit = {},
    onClickEditWidget: (WidgetType) -> Unit = {},
    onClickDeleteWidget: (WidgetType) -> Unit = {},
    onMoveWidget: (Int, Int) -> Unit = { _, _ -> },
    onDismissEmptyState: () -> Unit = {},
    onDismissHighBalanceSheet: () -> Unit = {},
    onClickEmptyActivityRow: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val balances = LocalBalances.current

    val topbarGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0.5f to Colors.Black,
            1.0f to Color.Transparent,
        )
    )

    Box {
        // Top AppBar Box
        val heightStatusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .hazeEffect(state = hazeState) {
                    mask = topbarGradient
                }
                .background(topbarGradient)
                .zIndex(1f)
        ) {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickableAlpha { }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = stringResource(R.string.slashtags__your_name_capital),
                            tint = Colors.White64,
                            modifier = Modifier.size(32.dp)
                        )
                        HorizontalSpacer(16.dp)
                        Title(text = stringResource(R.string.slashtags__your_name_capital))
                    }
                },
                actions = {
                    AppStatus(onClick = { rootNavController.navigate(Routes.AppStatus) })
                    HorizontalSpacer(4.dp)
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_list),
                            contentDescription = stringResource(R.string.settings__settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(Color.Transparent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        val pullToRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            state = pullToRefreshState,
            isRefreshing = mainUiState.isRefreshing,
            onRefresh = onRefresh,
            indicator = {
                Indicator(
                    isRefreshing = mainUiState.isRefreshing,
                    state = pullToRefreshState,
                    modifier = Modifier
                        .padding(top = heightStatusBar)
                        .align(Alignment.TopCenter)
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .zIndex(0f)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                StatusBarSpacer()
                TopBarSpacer()
                VerticalSpacer(16.dp)
                BalanceHeaderView(
                    sats = balances.totalSats.toLong(),
                    showEyeIcon = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!homeUiState.showEmptyState) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
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
                            onClick = onClickAddWidget,
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Text13Up(stringResource(R.string.wallet__activity), color = Colors.White64)
                    Spacer(modifier = Modifier.height(16.dp))
                    ActivityListSimple(
                        items = latestActivities,
                        onAllActivityClick = { walletNavController.navigate(HomeRoutes.AllActivity) },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                        onEmptyActivityRowClick = onClickEmptyActivityRow,
                    )

                    VerticalSpacer(120.dp) // scrollable empty space behind footer
                }
            }
            if (homeUiState.showEmptyState) {
                EmptyStateView(
                    text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
                    onClose = onDismissEmptyState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                )
            }

            if (homeUiState.highBalanceSheetVisible) {
                val context = LocalContext.current
                HighBalanceWarningSheet(
                    onDismiss = onDismissHighBalanceSheet,
                    understoodClick = onDismissHighBalanceSheet,
                    learnMoreClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Env.STORING_BITCOINS_URL.toUri())
                        context.startActivity(intent)
                        onDismissHighBalanceSheet()
                    }
                )
            }
        }
    }
}

@Composable
private fun DeleteWidgetAlert(
    type: WidgetType,
    homeViewModel: HomeViewModel,
) {
    AppAlertDialog(
        title = stringResource(R.string.widgets__delete__title),
        text = stringResource(R.string.widgets__delete__description)
            .replace("{name}", stringResource(type.title)),
        confirmText = stringResource(R.string.common__delete_yes),
        dismissText = stringResource(R.string.common__dialog_cancel),
        onConfirm = { homeViewModel.deleteWidget(widgetType = type) },
        onDismiss = {
            homeViewModel.dismissAlertDeleteWidget()
        },
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box {
            Content(
                mainUiState = MainUiState(),
                homeUiState = HomeUiState(),
                rootNavController = rememberNavController(),
                walletNavController = rememberNavController(),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                hazeState = rememberHazeState(),
                latestActivities = previewActivityItems.take(3),
            )
        }
    }
}
