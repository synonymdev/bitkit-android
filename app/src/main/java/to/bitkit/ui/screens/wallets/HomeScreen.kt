package to.bitkit.ui.screens.wallets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.ActivityBannerType
import to.bitkit.models.BalanceState
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.Routes
import to.bitkit.ui.components.ActivityBanner
import to.bitkit.ui.components.AppStatus
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.StatusBarSpacer
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.TopBarSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToAllActivity
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.navigateToTransferIntro
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.screens.wallets.activity.components.ActivityListSimple
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.screens.widgets.DragAndDropWidget
import to.bitkit.ui.screens.widgets.DragDropColumn
import to.bitkit.ui.screens.widgets.blocks.BlockCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.facts.FactsCard
import to.bitkit.ui.screens.widgets.headlines.HeadlineCard
import to.bitkit.ui.screens.widgets.price.PriceCard
import to.bitkit.ui.screens.widgets.weather.WeatherCard
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.PinRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel

private const val SMALL_SCREEN_HEIGHT_DP = 700
private const val SMALL_SCREEN_ACTIVITY_COUNT = 2
private const val LARGE_SCREEN_ACTIVITY_COUNT = 3
private const val ANIMATION_DURATION_MS = 300

@Suppress("CyclomaticComplexMethod")
@Composable
fun HomeScreen(
    isRefreshing: Boolean,
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
    val hasSeenWidgetsIntro: Boolean by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
    val bgPaymentsIntroSeen: Boolean by settingsViewModel.bgPaymentsIntroSeen.collectAsStateWithLifecycle()
    val quickPayIntroSeen by settingsViewModel.quickPayIntroSeen.collectAsStateWithLifecycle()
    val latestActivities by activityListViewModel.latestActivities.collectAsStateWithLifecycle()

    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        appViewModel.checkTimedSheets()
    }

    DisposableEffect(Unit) {
        onDispose {
            appViewModel.onLeftHome()
        }
    }

    homeUiState.deleteWidgetAlert?.let { type ->
        DeleteWidgetAlert(type, homeViewModel)
    }

    Content(
        isRefreshing = isRefreshing,
        homeUiState = homeUiState,
        rootNavController = rootNavController,
        walletNavController = walletNavController,
        drawerState = drawerState,
        latestActivities = latestActivities,
        onRefresh = {
            activityListViewModel.resync()
            walletViewModel.onPullToRefresh()
            homeViewModel.refreshWidgets()
        },
        onRemoveSuggestion = { suggestion ->
            homeViewModel.removeSuggestion(suggestion)
        },
        onClickSuggestion = { suggestion ->
            when (suggestion) {
                Suggestion.BUY -> {
                    rootNavController.navigate(Routes.BuyIntro)
                }

                Suggestion.LIGHTNING -> {
                    if (!hasSeenTransferIntro) {
                        rootNavController.navigateToTransferIntro()
                    } else {
                        rootNavController.navigateToTransferFunding()
                    }
                }

                Suggestion.BACK_UP -> {
                    appViewModel.showSheet(Sheet.Backup(BackupRoute.Intro))
                }

                Suggestion.SECURE -> {
                    appViewModel.showSheet(Sheet.Pin(PinRoute.Prompt(showLaterButton = true)))
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
                    rootNavController.navigate(Routes.Profile)
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

                Suggestion.NOTIFICATIONS -> {
                    if (bgPaymentsIntroSeen) {
                        rootNavController.navigate(Routes.BackgroundPaymentsSettings)
                    } else {
                        rootNavController.navigate(Routes.BackgroundPaymentsIntro)
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
        onClickEditWidgetList = homeViewModel::onClickEditWidgetList,
        onClickEditWidget = { widgetType ->
            homeViewModel.disableEditMode()
            when (widgetType) {
                WidgetType.BLOCK -> rootNavController.navigate(Routes.BlocksPreview)
                WidgetType.CALCULATOR -> rootNavController.navigate(Routes.CalculatorPreview)
                WidgetType.FACTS -> rootNavController.navigate(Routes.FactsPreview)
                WidgetType.NEWS -> rootNavController.navigate(Routes.HeadlinesPreview)
                WidgetType.PRICE -> rootNavController.navigate(Routes.PricePreview)
                WidgetType.WEATHER -> rootNavController.navigate(Routes.WeatherPreview)
                WidgetType.SUGGESTIONS -> rootNavController.navigate(Routes.SuggestionsPreview)
            }
        },
        onClickDeleteWidget = { widgetType ->
            homeViewModel.displayAlertDeleteWidget(widgetType)
        },
        onMoveWidget = { fromIndex, toIndex ->
            homeViewModel.moveWidget(fromIndex, toIndex)
        },
        onPageChanged = homeViewModel::onPageChanged,
        onDismissWidgetsOnboardingHint = homeViewModel::dismissWidgetsOnboardingHint,
    )
}

@Suppress("MagicNumber")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
private fun Content(
    isRefreshing: Boolean,
    homeUiState: HomeUiState,
    rootNavController: NavController,
    walletNavController: NavController,
    drawerState: DrawerState,
    hazeState: HazeState = rememberHazeState(),
    latestActivities: List<Activity>?,
    onRefresh: () -> Unit = {},
    onRemoveSuggestion: (Suggestion) -> Unit = {},
    onClickSuggestion: (Suggestion) -> Unit = {},
    onClickAddWidget: () -> Unit = {},
    onClickEditWidgetList: () -> Unit = {},
    onClickEditWidget: (WidgetType) -> Unit = {},
    onClickDeleteWidget: (WidgetType) -> Unit = {},
    onMoveWidget: (Int, Int) -> Unit = { _, _ -> },
    onPageChanged: (Int) -> Unit = {},
    onDismissWidgetsOnboardingHint: () -> Unit = {},
    balances: BalanceState = LocalBalances.current,
) {
    val scope = rememberCoroutineScope()
    val pageCount = if (homeUiState.showWidgets) 2 else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        if (pagerState.currentPage == 1 && !latestActivities.isNullOrEmpty()) {
            onDismissWidgetsOnboardingHint()
        }
    }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val activityCount = if (screenHeightDp < SMALL_SCREEN_HEIGHT_DP) {
        SMALL_SCREEN_ACTIVITY_COUNT
    } else {
        LARGE_SCREEN_ACTIVITY_COUNT
    }

    Box {
        TopBar(
            hazeState = hazeState,
            rootNavController = rootNavController,
            scope = scope,
            drawerState = drawerState,
            showEditWidgets = homeUiState.currentPage == 1 && homeUiState.showWidgets,
            isEditingWidgets = homeUiState.isEditingWidgets,
            onClickEditWidgetList = onClickEditWidgetList,
        )

        VerticalPager(
            state = pagerState,
            userScrollEnabled = homeUiState.showWidgets,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .zIndex(0f)
        ) { page ->
            when (page) {
                0 -> WalletPage(
                    isRefreshing = isRefreshing,
                    homeUiState = homeUiState,
                    latestActivities = latestActivities?.take(activityCount),
                    balances = balances,
                    rootNavController = rootNavController,
                    walletNavController = walletNavController,
                    onRefresh = onRefresh,
                )

                1 -> WidgetsPage(
                    homeUiState = homeUiState,
                    onRemoveSuggestion = onRemoveSuggestion,
                    onClickSuggestion = onClickSuggestion,
                    onClickAddWidget = onClickAddWidget,
                    onClickEditWidget = onClickEditWidget,
                    onClickDeleteWidget = onClickDeleteWidget,
                    onMoveWidget = onMoveWidget,
                )
            }
        }
    }
}

@Suppress("MagicNumber")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPage(
    isRefreshing: Boolean,
    homeUiState: HomeUiState,
    latestActivities: List<Activity>?,
    balances: BalanceState,
    rootNavController: NavController,
    walletNavController: NavController,
    onRefresh: () -> Unit,
) {
    val heightStatusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val hasActivity = !latestActivities.isNullOrEmpty()

    PullToRefreshBox(
        state = pullToRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        indicator = {
            Indicator(
                isRefreshing = isRefreshing,
                state = pullToRefreshState,
                modifier = Modifier
                    .padding(top = heightStatusBar)
                    .align(Alignment.TopCenter)
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("HomeScrollView")
        ) {
            StatusBarSpacer()
            TopBarSpacer()
            VerticalSpacer(16.dp)

            BalanceHeaderView(
                sats = balances.totalSats.toLong(),
                showEyeIcon = true,
                testTag = "TotalBalance",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("TotalBalance")
            )

            if (!homeUiState.showEmptyState) {
                VerticalSpacer(32.dp)
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
                            .clickableAlpha { walletNavController.navigate(Routes.Savings) }
                            .padding(vertical = 4.dp)
                            .testTag("ActivitySavings")
                    )
                    VerticalDivider()
                    HorizontalSpacer(16.dp)
                    WalletBalanceView(
                        title = stringResource(R.string.wallet__spending__title),
                        sats = balances.totalLightningSats.toLong(),
                        icon = painterResource(id = R.drawable.ic_ln_circle),
                        modifier = Modifier
                            .clickableAlpha { walletNavController.navigate(Routes.Spending) }
                            .padding(vertical = 4.dp)
                            .testTag("ActivitySpending")
                    )
                }

                if (hasActivity) {
                    AnimatedVisibility(homeUiState.banners.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp, bottom = 18.dp)
                        ) {
                            homeUiState.banners.forEach { banner ->
                                ActivityBanner(
                                    gradientColor = banner.color,
                                    title = stringResource(banner.title),
                                    icon = banner.icon,
                                    onClick = {
                                        when (banner) {
                                            ActivityBannerType.SPENDING ->
                                                rootNavController.navigate(Routes.SettingUp)

                                            ActivityBannerType.SAVINGS -> Unit
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    VerticalSpacer(32.dp)

                    ActivityListSimple(
                        items = latestActivities,
                        onAllActivityClick = { rootNavController.navigateToAllActivity() },
                        onActivityItemClick = { rootNavController.navigateToActivityItem(it) },
                    )

                    FillHeight()

                    if (homeUiState.showWidgetsOnboardingHint) {
                        WidgetsOnboardingHint()
                    }
                }

                VerticalSpacer(150.dp)
            }
        }

        if (homeUiState.showEmptyState) {
            EmptyStateView(
                text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
private fun WidgetsPage(
    homeUiState: HomeUiState,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    onClickAddWidget: () -> Unit,
    onClickEditWidget: (WidgetType) -> Unit,
    onClickDeleteWidget: (WidgetType) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
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
            Widgets(
                homeUiState = homeUiState,
                onRemoveSuggestion = onRemoveSuggestion,
                onClickSuggestion = onClickSuggestion,
            )
        }

        VerticalSpacer(32.dp)

        TertiaryButton(
            text = stringResource(R.string.widgets__add),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = Colors.White80,
                )
            },
            onClick = onClickAddWidget,
            modifier = Modifier.testTag("WidgetsAdd")
        )

        VerticalSpacer(150.dp)
    }
}

@Composable
private fun SuggestionsSection(
    suggestions: List<Suggestion>,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = (suggestions.size + 1) / 2

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardSize = (maxWidth - 16.dp) / 2
        val gridHeight = (cardSize * rows) + (16.dp * (rows - 1))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .testTag("Suggestions")
        ) {
            items(
                items = suggestions,
                key = { it.name }
            ) { item ->
                SuggestionCard(
                    gradientColor = item.color,
                    title = stringResource(item.title),
                    description = stringResource(item.description),
                    icon = item.icon,
                    onClose = { onRemoveSuggestion(item) }.takeIf { item.dismissible },
                    onClick = { onClickSuggestion(item) },
                    modifier = Modifier
                        .testTag("Suggestion-${item.name.lowercase()}")
                        .animateItem(
                            fadeInSpec = tween(durationMillis = ANIMATION_DURATION_MS),
                            fadeOutSpec = tween(durationMillis = ANIMATION_DURATION_MS),
                            placementSpec = tween(durationMillis = ANIMATION_DURATION_MS),
                        )
                )
            }
        }
    }
}

@Composable
private fun WidgetsOnboardingHint(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
    ) {
        Display(
            text = stringResource(R.string.widgets__onboarding_swipe).withAccent(),
            modifier = Modifier.weight(1f)
        )
        HorizontalSpacer(16.dp)
        Image(
            painter = painterResource(R.drawable.swipe_instruction),
            contentDescription = null,
        )
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun Widgets(
    homeUiState: HomeUiState,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        homeUiState.widgetsWithPosition.forEach { widgetsWithPosition ->
            when (widgetsWithPosition.type) {
                WidgetType.BLOCK -> {
                    homeUiState.currentBlock?.run {
                        BlockCard(
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
                            block = height,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("BlocksWidget")
                        )
                    }
                }

                WidgetType.CALCULATOR -> {
                    currencyViewModel?.let {
                        CalculatorCard(
                            currencyViewModel = it,
                            showWidgetTitle = homeUiState.showWidgetTitles,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                WidgetType.FACTS -> {
                    homeUiState.currentFact?.run {
                        FactsCard(
                            showWidgetTitle = homeUiState.showWidgetTitles,
                            showSource = homeUiState.factsPreferences.showSource,
                            headline = homeUiState.currentFact,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                WidgetType.NEWS -> {
                    homeUiState.currentArticle?.run {
                        HeadlineCard(
                            showWidgetTitle = homeUiState.showWidgetTitles,
                            showTime = homeUiState.headlinePreferences.showTime,
                            showSource = homeUiState.headlinePreferences.showSource,
                            headline = title,
                            time = timeAgo,
                            source = publisher,
                            link = link,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("NewsWidget")
                        )
                    }
                }

                WidgetType.PRICE -> {
                    homeUiState.currentPrice?.run {
                        PriceCard(
                            showWidgetTitle = homeUiState.showWidgetTitles,
                            pricePreferences = homeUiState.pricePreferences,
                            priceDTO = homeUiState.currentPrice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("PriceWidget")
                        )
                    }
                }

                WidgetType.WEATHER -> {
                    homeUiState.currentWeather?.run {
                        WeatherCard(
                            showWidgetTitle = homeUiState.showWidgetTitles,
                            weatherModel = this,
                            preferences = homeUiState.weatherPreferences,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                WidgetType.SUGGESTIONS -> {
                    if (homeUiState.suggestions.isNotEmpty()) {
                        SuggestionsSection(
                            suggestions = homeUiState.suggestions,
                            onRemoveSuggestion = onRemoveSuggestion,
                            onClickSuggestion = onClickSuggestion,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    hazeState: HazeState,
    rootNavController: NavController,
    scope: CoroutineScope,
    drawerState: DrawerState,
    showEditWidgets: Boolean = false,
    isEditingWidgets: Boolean = false,
    onClickEditWidgetList: () -> Unit = {},
) {
    val topbarGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0.5f to Colors.Black,
            1.0f to Color.Transparent,
        )
    )

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
            title = {},
            actions = {
                if (showEditWidgets) {
                    IconButton(
                        onClick = onClickEditWidgetList,
                        modifier = Modifier.testTag("WidgetsEdit")
                    ) {
                        Icon(
                            painter = if (isEditingWidgets) {
                                painterResource(R.drawable.ic_check)
                            } else {
                                painterResource(R.drawable.ic_sort_ascending)
                            },
                            contentDescription = null,
                        )
                    }
                }
                AppStatus(onClick = { rootNavController.navigate(Routes.AppStatus) })
                HorizontalSpacer(4.dp)
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.testTag("HeaderMenu")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_list),
                        contentDescription = stringResource(R.string.settings__settings),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        )
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
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                ),
                rootNavController = rememberNavController(),
                walletNavController = rememberNavController(),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewActivityItems.take(3),
                balances = BalanceState(
                    totalOnchainSats = 165_000u,
                    totalLightningSats = 45_000u,
                ),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showEmptyState = true,
                ),
                rootNavController = rememberNavController(),
                walletNavController = rememberNavController(),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewActivityItems.take(3),
                balances = BalanceState()
            )
            TabBar()
        }
    }
}
