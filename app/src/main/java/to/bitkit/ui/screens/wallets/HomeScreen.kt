package to.bitkit.ui.screens.wallets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PIXEL_TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.synonym.bitkitcore.Activity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.env.Env
import to.bitkit.models.ActivityBannerType
import to.bitkit.models.BalanceState
import to.bitkit.models.BannerItem
import to.bitkit.models.HwWallet
import to.bitkit.models.MoneyType
import to.bitkit.models.Suggestion
import to.bitkit.models.Toast
import to.bitkit.models.TransportType
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.effectiveSize
import to.bitkit.models.toBalance
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.Routes
import to.bitkit.ui.components.ActivityBanner
import to.bitkit.ui.components.AppStatus
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.EmptyStateView
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.Headline24
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.StatusBarSpacer
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.TAB_BAR_HEIGHT
import to.bitkit.ui.components.TAB_BAR_PADDING_BOTTOM
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.TopBarSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.navigateTo
import to.bitkit.ui.navigateToActivityItem
import to.bitkit.ui.navigateToAllActivity
import to.bitkit.ui.navigateToProfile
import to.bitkit.ui.navigateToTransferFunding
import to.bitkit.ui.navigateToTransferIntro
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.screens.wallets.activity.components.ActivityListSimple
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.screens.widgets.blocks.BlockCard
import to.bitkit.ui.screens.widgets.blocks.BlockCardSmall
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.calculator.CalculatorUiState
import to.bitkit.ui.screens.widgets.calculator.CalculatorViewModel
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCardSmall
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorNumberPadBar
import to.bitkit.ui.screens.widgets.components.EditableWidgetGrid
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.screens.widgets.components.WidgetFlowLayout
import to.bitkit.ui.screens.widgets.facts.FactsCard
import to.bitkit.ui.screens.widgets.facts.FactsCardSmall
import to.bitkit.ui.screens.widgets.headlines.HeadlineCard
import to.bitkit.ui.screens.widgets.headlines.HeadlineCardSmall
import to.bitkit.ui.screens.widgets.price.PriceCard
import to.bitkit.ui.screens.widgets.price.PriceCardSmall
import to.bitkit.ui.screens.widgets.suggestions.SuggestionsPreviewGrid
import to.bitkit.ui.screens.widgets.weather.WeatherCard
import to.bitkit.ui.screens.widgets.weather.WeatherCardSmall
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.PinRoute
import to.bitkit.ui.sheets.toWidgetsEditRoute
import to.bitkit.ui.sheets.toWidgetsPreviewRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Insets
import to.bitkit.ui.theme.TopBarGradient
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel

private const val SMALL_SCREEN_HEIGHT_DP = 800
private const val SMALL_SCREEN_SLOT_CAPACITY = 3
private const val LARGE_SCREEN_SLOT_CAPACITY = 4
private val CALCULATOR_LIFT_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
private val BOTTOM_SPACER_HEIGHT = (TAB_BAR_HEIGHT + TAB_BAR_PADDING_BOTTOM + 36).dp

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
    walletPageRequest: Int = 0,
    widgetsPageRequest: Int = 0,
    onConsumeWalletPageRequest: () -> Unit = {},
    onConsumeWidgetsPageRequest: () -> Unit = {},
    onCalculatorInputActiveChanged: (Boolean) -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasSeenTransferIntro by settingsViewModel.hasSeenTransferIntro.collectAsStateWithLifecycle()
    val hasSeenShopIntro by settingsViewModel.hasSeenShopIntro.collectAsStateWithLifecycle()
    val hasSeenProfileIntro by settingsViewModel.hasSeenProfileIntro.collectAsStateWithLifecycle()
    val isPubkyAuthenticated by settingsViewModel.isPubkyAuthenticated.collectAsStateWithLifecycle()
    val isPaykitEnabled by settingsViewModel.isPaykitEnabled.collectAsStateWithLifecycle()
    val profileDisplayName by homeViewModel.profileDisplayName.collectAsStateWithLifecycle()
    val profileDisplayImageUri by homeViewModel.profileDisplayImageUri.collectAsStateWithLifecycle()
    val hasSeenWidgetsIntro: Boolean by settingsViewModel.hasSeenWidgetsIntro.collectAsStateWithLifecycle()
    val bgPaymentsIntroSeen: Boolean by settingsViewModel.bgPaymentsIntroSeen.collectAsStateWithLifecycle()
    val quickPayIntroSeen by settingsViewModel.quickPayIntroSeen.collectAsStateWithLifecycle()
    val latestActivities by activityListViewModel.latestActivities.collectAsStateWithLifecycle()
    val hardwareIds by activityListViewModel.hardwareIds.collectAsStateWithLifecycle()

    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> appViewModel.checkTimedSheets()
                Lifecycle.Event.ON_PAUSE -> appViewModel.onLeftHome()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            appViewModel.onLeftHome()
        }
    }

    homeUiState.deleteWidgetAlert?.let { type ->
        DeleteWidgetAlert(type, homeViewModel)
    }

    val navigateToProfile = {
        rootNavController.navigateToProfile(
            isAuthenticated = isPubkyAuthenticated,
            hasSeenIntro = hasSeenProfileIntro,
        )
    }

    Content(
        isRefreshing = isRefreshing,
        homeUiState = homeUiState,
        drawerState = drawerState,
        profileDisplayName = profileDisplayName,
        profileDisplayImageUri = profileDisplayImageUri,
        showProfileButton = isPaykitEnabled,
        onClickProfile = navigateToProfile,
        latestActivities = latestActivities,
        hardwareIds = hardwareIds,
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
                    rootNavController.navigateTo(Routes.BuyIntro)
                }

                Suggestion.HARDWARE -> {
                    appViewModel.showSheet(Sheet.Hardware())
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
                    rootNavController.navigateTo(Routes.Support)
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
                    if (isPaykitEnabled) navigateToProfile() else rootNavController.navigateTo(Routes.Profile)
                }

                Suggestion.SHOP -> {
                    if (!hasSeenShopIntro) {
                        rootNavController.navigateTo(Routes.ShopIntro)
                    } else {
                        rootNavController.navigateTo(Routes.ShopDiscover)
                    }
                }

                Suggestion.QUICK_PAY -> {
                    if (!quickPayIntroSeen) {
                        rootNavController.navigateTo(Routes.QuickPayIntro)
                    } else {
                        rootNavController.navigateTo(Routes.QuickPaySettings)
                    }
                }

                Suggestion.NOTIFICATIONS -> {
                    if (bgPaymentsIntroSeen) {
                        rootNavController.navigateTo(Routes.BackgroundPaymentsSettings)
                    } else {
                        rootNavController.navigateTo(Routes.BackgroundPaymentsIntro)
                    }
                }
            }
        },
        onClickAddWidget = {
            if (!hasSeenWidgetsIntro) {
                rootNavController.navigateTo(Routes.WidgetsIntro)
            } else {
                appViewModel.showSheet(Sheet.Widgets())
            }
        },
        onClickEditWidgetList = homeViewModel::onClickEditWidgetList,
        onClickEditWidget = { widgetType ->
            homeViewModel.disableEditMode()
            val route = widgetType.toWidgetsEditRoute() ?: widgetType.toWidgetsPreviewRoute()
            appViewModel.showSheet(Sheet.Widgets(route))
        },
        onClickDeleteWidget = { widgetType ->
            homeViewModel.displayAlertDeleteWidget(widgetType)
        },
        onMoveWidget = { fromIndex, toIndex ->
            homeViewModel.moveWidget(fromIndex, toIndex)
        },
        walletPageRequest = walletPageRequest,
        widgetsPageRequest = widgetsPageRequest,
        onConsumeWalletPageRequest = onConsumeWalletPageRequest,
        onConsumeWidgetsPageRequest = onConsumeWidgetsPageRequest,
        onPageChanged = homeViewModel::onPageChanged,
        onDismissWidgetsOnboardingHint = homeViewModel::dismissWidgetsOnboardingHint,
        onNavigateToAppStatus = { rootNavController.navigate(Routes.AppStatus) },
        onNavigateToSettingUp = { rootNavController.navigate(Routes.SettingUp) },
        onNavigateToAllActivity = { rootNavController.navigateToAllActivity(activityListViewModel::clearFilters) },
        onNavigateToActivityItem = { rootNavController.navigateToActivityItem(it) },
        onNavigateToSavings = { walletNavController.navigate(Routes.Savings) },
        onNavigateToSpending = { walletNavController.navigate(Routes.Spending) },
        onClickHardwareWallet = {
            scope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Hardware wallet overview not yet implemented.",
                )
            }
        },
        onCalculatorInputActiveChanged = onCalculatorInputActiveChanged,
    )
}

@Suppress("CyclomaticComplexMethod", "MagicNumber")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
private fun Content(
    isRefreshing: Boolean,
    homeUiState: HomeUiState,
    drawerState: DrawerState,
    profileDisplayName: String? = null,
    profileDisplayImageUri: String? = null,
    showProfileButton: Boolean = false,
    onClickProfile: () -> Unit = {},
    latestActivities: ImmutableList<Activity>?,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    onRefresh: () -> Unit = {},
    onRemoveSuggestion: (Suggestion) -> Unit = {},
    onClickSuggestion: (Suggestion) -> Unit = {},
    onClickAddWidget: () -> Unit = {},
    onClickEditWidgetList: () -> Unit = {},
    onClickEditWidget: (WidgetType) -> Unit = {},
    onClickDeleteWidget: (WidgetType) -> Unit = {},
    onMoveWidget: (Int, Int) -> Unit = { _, _ -> },
    walletPageRequest: Int = 0,
    widgetsPageRequest: Int = 0,
    onConsumeWalletPageRequest: () -> Unit = {},
    onConsumeWidgetsPageRequest: () -> Unit = {},
    onPageChanged: (Int) -> Unit = {},
    onDismissWidgetsOnboardingHint: () -> Unit = {},
    onNavigateToAppStatus: () -> Unit = {},
    onNavigateToSettingUp: () -> Unit = {},
    onNavigateToAllActivity: () -> Unit = {},
    onNavigateToActivityItem: (String) -> Unit = {},
    onNavigateToSavings: () -> Unit = {},
    onNavigateToSpending: () -> Unit = {},
    onClickHardwareWallet: () -> Unit = {},
    onCalculatorInputActiveChanged: (Boolean) -> Unit = {},
    hazeState: HazeState = rememberHazeState(),
    balances: BalanceState = LocalBalances.current,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var calculatorInputDismissKey by remember { mutableIntStateOf(0) }
    var isCalculatorInputActive by remember { mutableStateOf(false) }
    val onCalculatorInputActiveChange = { isActive: Boolean ->
        isCalculatorInputActive = isActive
        onCalculatorInputActiveChanged(isActive)
    }
    val pageCount = if (homeUiState.showWidgets) 2 else 1
    val pagerState = rememberPagerState(
        initialPage = homeUiState.currentPage,
        pageCount = { pageCount },
    )
    fun dismissKeyboard(callback: (() -> Unit)? = null) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        calculatorInputDismissKey++
        callback?.invoke()
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        if (pagerState.currentPage == 1 && !latestActivities.isNullOrEmpty()) {
            onDismissWidgetsOnboardingHint()
        }
    }

    LaunchedEffect(walletPageRequest) {
        if (walletPageRequest == 0) return@LaunchedEffect

        pagerState.animateScrollToPage(0)
        onPageChanged(0)
        onConsumeWalletPageRequest()
    }

    LaunchedEffect(widgetsPageRequest, pageCount) {
        if (widgetsPageRequest == 0) return@LaunchedEffect

        if (pageCount > 1) {
            pagerState.animateScrollToPage(1)
            onPageChanged(1)
        }
        onConsumeWidgetsPageRequest()
    }

    LaunchedEffect(pagerState.currentPage, isCalculatorInputActive) {
        if (pagerState.currentPage == 0 && isCalculatorInputActive) {
            dismissKeyboard()
        }
    }

    val density = LocalDensity.current
    val screenHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }
    val isSmallScreen = screenHeightDp < SMALL_SCREEN_HEIGHT_DP
    val slotCapacity = if (isSmallScreen) SMALL_SCREEN_SLOT_CAPACITY else LARGE_SCREEN_SLOT_CAPACITY
    val nonItemSlots = countNonItemSlots(homeUiState)
    val activityCount = (slotCapacity - nonItemSlots).coerceAtLeast(0)

    val paginatedActivities = remember(latestActivities, activityCount) {
        latestActivities?.take(activityCount)?.toImmutableList()
    }

    Box {
        TopBar(
            hazeState = hazeState,
            profileDisplayName = profileDisplayName,
            profileDisplayImageUri = profileDisplayImageUri,
            showProfileButton = showProfileButton,
            onClickProfile = {
                dismissKeyboard {
                    onClickProfile()
                }
            },
            showEditWidgets = homeUiState.currentPage == 1 && homeUiState.showWidgets,
            isEditingWidgets = homeUiState.isEditingWidgets,
            onClickEditWidgetList = {
                dismissKeyboard {
                    onClickEditWidgetList()
                }
            },
            onNavigateToAppStatus = {
                dismissKeyboard {
                    onNavigateToAppStatus()
                }
            },
            onOpenDrawer = {
                dismissKeyboard {
                    scope.launch { drawerState.open() }
                }
            },
        )

        VerticalPager(
            state = pagerState,
            userScrollEnabled = homeUiState.showWidgets && !homeUiState.isEditingWidgets,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                decayAnimationSpec = exponentialDecay(frictionMultiplier = 0.1f),
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                snapPositionalThreshold = 0.01f,
            ),
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .zIndex(0f)
        ) { page ->
            when (page) {
                0 -> WalletPage(
                    isRefreshing = isRefreshing,
                    homeUiState = homeUiState,
                    latestActivities = paginatedActivities,
                    hardwareIds = hardwareIds,
                    balances = balances,
                    isSmallScreen = isSmallScreen,
                    onRefresh = onRefresh,
                    onNavigateToSettingUp = onNavigateToSettingUp,
                    onNavigateToAllActivity = onNavigateToAllActivity,
                    onNavigateToActivityItem = onNavigateToActivityItem,
                    onNavigateToSavings = onNavigateToSavings,
                    onNavigateToSpending = onNavigateToSpending,
                    onClickHardwareWallet = onClickHardwareWallet,
                )

                1 -> WidgetsPage(
                    homeUiState = homeUiState,
                    calculatorInputDismissKey = calculatorInputDismissKey,
                    onDismissCalculatorInput = ::dismissKeyboard,
                    onCalculatorInputActiveChanged = onCalculatorInputActiveChange,
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

private fun countNonItemSlots(homeUiState: HomeUiState): Int {
    val bannerSlot = if (homeUiState.banners.isNotEmpty()) 1 else 0
    val widgetsHintSlot = if (homeUiState.showWidgetsOnboardingHint) 1 else 0
    return bannerSlot + widgetsHintSlot
}

@Suppress("MagicNumber")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPage(
    isRefreshing: Boolean,
    homeUiState: HomeUiState,
    latestActivities: ImmutableList<Activity>?,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    balances: BalanceState,
    isSmallScreen: Boolean,
    onRefresh: () -> Unit,
    onNavigateToSettingUp: () -> Unit,
    onNavigateToAllActivity: () -> Unit,
    onNavigateToActivityItem: (String) -> Unit,
    onNavigateToSavings: () -> Unit,
    onNavigateToSpending: () -> Unit,
    onClickHardwareWallet: () -> Unit,
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
                sats = balances.totalWithHardwareSats.toLong(),
                showEyeIcon = true,
                testTag = "TotalBalance",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("TotalBalance")
            )
            VerticalSpacer(32.dp)
            BalancesSection(
                balances = balances,
                hardwareWallets = homeUiState.hardwareWallets,
                onNavigateToSavings = onNavigateToSavings,
                onNavigateToSpending = onNavigateToSpending,
                onClickHardwareWallet = onClickHardwareWallet,
            )
            VerticalSpacer(32.dp)

            if (!homeUiState.showEmptyState) {
                if (hasActivity) {
                    AnimatedVisibility(homeUiState.banners.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            homeUiState.banners.forEach { banner ->
                                ActivityBanner(
                                    gradientColor = banner.type.color,
                                    title = banner.title,
                                    icon = banner.type.icon,
                                    onClick = {
                                        when (banner.type) {
                                            ActivityBannerType.SPENDING -> onNavigateToSettingUp()
                                            ActivityBannerType.SAVINGS -> Unit
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    ActivityListSimple(
                        items = latestActivities,
                        onAllActivityClick = onNavigateToAllActivity,
                        onActivityItemClick = onNavigateToActivityItem,
                        hardwareIds = hardwareIds,
                    )

                    FillHeight()

                    if (homeUiState.showWidgetsOnboardingHint) {
                        WidgetsOnboardingHint()
                    }
                }

                VerticalSpacer(BOTTOM_SPACER_HEIGHT + Insets.Bottom)
            }
        }

        if (homeUiState.showEmptyState) {
            EmptyStateView(
                text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
                isSmallScreen = isSmallScreen,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun BalancesSection(
    balances: BalanceState,
    hardwareWallets: ImmutableList<HwWallet>,
    onNavigateToSavings: () -> Unit,
    onNavigateToSpending: () -> Unit,
    onClickHardwareWallet: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    .clickableAlpha(onClick = onNavigateToSavings)
                    .padding(vertical = 4.dp)
                    .testTag("ActivitySavings")
            )
            VerticalDivider(color = Colors.Gray4)
            HorizontalSpacer(16.dp)
            WalletBalanceView(
                title = stringResource(R.string.wallet__spending__title),
                sats = balances.totalLightningSats.toLong(),
                icon = painterResource(id = R.drawable.ic_ln_circle),
                modifier = Modifier
                    .clickableAlpha(onClick = onNavigateToSpending)
                    .padding(vertical = 4.dp)
                    .testTag("ActivitySpending")
            )
        }

        HwDevices(wallets = hardwareWallets, onClick = onClickHardwareWallet)
    }
}

/**
 * Hardware wallets flow into a 2-column grid below the Savings/Spending tiles:
 * a second device fills the bottom-right column, additional devices wrap onto new rows.
 */
@Composable
private fun HwDevices(
    wallets: ImmutableList<HwWallet>,
    onClick: () -> Unit,
) {
    wallets.chunked(2).forEach { rowWallets ->
        VerticalSpacer(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            HwDeviceCell(wallet = rowWallets[0], onClick = onClick)
            VerticalDivider(color = Colors.Gray4)
            HorizontalSpacer(16.dp)
            val second = rowWallets.getOrNull(1)
            if (second != null) {
                HwDeviceCell(wallet = second, onClick = onClick)
            } else {
                FillWidth()
            }
        }
    }
}

@Composable
private fun RowScope.HwDeviceCell(
    wallet: HwWallet,
    onClick: () -> Unit,
) {
    WalletBalanceView(
        title = wallet.name,
        sats = wallet.balanceSats.toLong(),
        icon = painterResource(id = R.drawable.ic_btc_circle_blue),
        modifier = Modifier
            .clickableAlpha(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag("ActivityHardware")
    ) {
        HorizontalSpacer(4.dp)
        Icon(
            painter = painterResource(
                id = when (wallet.transportType) {
                    TransportType.BLUETOOTH -> R.drawable.ic_bluetooth_connected
                    TransportType.USB -> R.drawable.ic_usb_connected
                }
            ),
            contentDescription = null,
            tint = if (wallet.isConnected) Colors.Green else Colors.Gray1,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun WidgetsPage(
    homeUiState: HomeUiState,
    calculatorInputDismissKey: Int,
    onDismissCalculatorInput: () -> Unit,
    onCalculatorInputActiveChanged: (Boolean) -> Unit,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    onClickAddWidget: () -> Unit,
    onClickEditWidget: (WidgetType) -> Unit,
    onClickDeleteWidget: (WidgetType) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
) {
    if (LocalInspectionMode.current) {
        WidgetsPageContent(
            homeUiState = homeUiState,
            calculatorInputDismissKey = calculatorInputDismissKey,
            calcState = CalculatorUiState(),
            onDismissCalculatorInput = onDismissCalculatorInput,
            onCalculatorInputActiveChanged = onCalculatorInputActiveChanged,
            onInputDismissed = {},
            onInputSelected = {},
            onBtcInputChanged = {},
            onFiatInputChanged = {},
            onRemoveSuggestion = onRemoveSuggestion,
            onClickSuggestion = onClickSuggestion,
            onClickAddWidget = onClickAddWidget,
            onClickEditWidget = onClickEditWidget,
            onClickDeleteWidget = onClickDeleteWidget,
            onMoveWidget = onMoveWidget,
        )
        return
    }

    val calculatorViewModel: CalculatorViewModel = hiltViewModel()
    val calcState by calculatorViewModel.uiState.collectAsStateWithLifecycle()
    WidgetsPageContent(
        homeUiState = homeUiState,
        calculatorInputDismissKey = calculatorInputDismissKey,
        calcState = calcState,
        onDismissCalculatorInput = onDismissCalculatorInput,
        onCalculatorInputActiveChanged = onCalculatorInputActiveChanged,
        onInputDismissed = calculatorViewModel::onInputDismissed,
        onInputSelected = calculatorViewModel::onInputSelected,
        onBtcInputChanged = calculatorViewModel::onBtcInputChanged,
        onFiatInputChanged = calculatorViewModel::onFiatInputChanged,
        onRemoveSuggestion = onRemoveSuggestion,
        onClickSuggestion = onClickSuggestion,
        onClickAddWidget = onClickAddWidget,
        onClickEditWidget = onClickEditWidget,
        onClickDeleteWidget = onClickDeleteWidget,
        onMoveWidget = onMoveWidget,
    )
}

@Suppress("CyclomaticComplexMethod", "MagicNumber", "LongMethod")
@Composable
private fun WidgetsPageContent(
    homeUiState: HomeUiState,
    calculatorInputDismissKey: Int,
    calcState: CalculatorUiState,
    onDismissCalculatorInput: () -> Unit,
    onCalculatorInputActiveChanged: (Boolean) -> Unit,
    onInputDismissed: () -> Unit,
    onInputSelected: (MoneyType) -> Unit,
    onBtcInputChanged: (String) -> Unit,
    onFiatInputChanged: (String) -> Unit,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
    onClickAddWidget: () -> Unit,
    onClickEditWidget: (WidgetType) -> Unit,
    onClickDeleteWidget: (WidgetType) -> Unit,
    onMoveWidget: (Int, Int) -> Unit,
) {
    val isCalcActive = calcState.activeInput != null

    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val widgetsScrollState = rememberScrollState()
    val density = LocalDensity.current
    var pageBounds by remember { mutableStateOf<Rect?>(null) }
    var calculatorBounds by remember { mutableStateOf<Rect?>(null) }
    var numberPadBounds by remember { mutableStateOf<Rect?>(null) }
    val latestCalculatorBounds by rememberUpdatedState(calculatorBounds)
    val latestNumberPadBounds by rememberUpdatedState(numberPadBounds)
    val revealMarginPx = with(density) { 16.dp.toPx() }
    // Lifts the page so the focused calculator clears the number pad, snapping back when it closes.
    val focusedOffsetY = remember { Animatable(0f) }

    // Keep the hoisted state in sync so the pager and page-change handling react to calculator input.
    LaunchedEffect(isCalcActive) { onCalculatorInputActiveChanged(isCalcActive) }

    // Honor external dismiss requests (page change, profile/drawer taps, etc.).
    LaunchedEffect(calculatorInputDismissKey) {
        if (calculatorInputDismissKey != 0) onInputDismissed()
    }

    // Drop the calculator input when editing or when the widget is no longer present.
    LaunchedEffect(homeUiState.widgetsWithPosition, homeUiState.isEditingWidgets) {
        val hasCalculator = homeUiState.widgetsWithPosition.any { it.type == WidgetType.CALCULATOR }
        if (homeUiState.isEditingWidgets || !hasCalculator) {
            onInputDismissed()
            calculatorBounds = null
            numberPadBounds = null
        }
    }

    // Lift the page so the focused calculator card sits above the number pad bar (any position),
    // and snap it back when the input closes — matching iOS's content offset.
    LaunchedEffect(isCalcActive, numberPadBounds != null) {
        if (!isCalcActive || numberPadBounds == null) {
            focusedOffsetY.animateTo(0f, CALCULATOR_LIFT_SPEC)
            return@LaunchedEffect
        }
        withFrameNanos { }
        val calculator = latestCalculatorBounds ?: return@LaunchedEffect
        val numberPad = latestNumberPadBounds ?: return@LaunchedEffect
        val overlap = calculator.bottom - (numberPad.top - revealMarginPx)
        focusedOffsetY.animateTo(if (overlap > 0f) -overlap else 0f, CALCULATOR_LIFT_SPEC)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { pageBounds = it.boundsInRoot() }
            .dismissCalculatorInputOnOutsideTap(
                isCalculatorInputActive = isCalcActive,
                pageBounds = pageBounds,
                calculatorBounds = calculatorBounds,
                numberPadBounds = numberPadBounds,
                onDismiss = onDismissCalculatorInput,
            )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .graphicsLayer { translationY = focusedOffsetY.value }
                .verticalScroll(
                    state = widgetsScrollState,
                    enabled = !isCalcActive,
                )
        ) {
            StatusBarSpacer()
            TopBarSpacer()
            VerticalSpacer(16.dp)

            if (homeUiState.isEditingWidgets) {
                EditableWidgetGrid(
                    items = homeUiState.widgetsWithPosition,
                    onMove = onMoveWidget,
                    onDelete = onClickDeleteWidget,
                    onSettings = onClickEditWidget,
                    modifier = Modifier.fillMaxWidth(),
                ) { widget ->
                    WidgetCardContent(
                        widget = widget,
                        homeUiState = homeUiState,
                        calcState = calcState,
                        onSelectCalcInput = null,
                        onCalculatorBoundsChanged = {},
                        onRemoveSuggestion = onRemoveSuggestion,
                        onClickSuggestion = onClickSuggestion,
                    )
                }
            } else {
                val visibleWidgets = homeUiState.widgetsWithPosition
                    .filter { hasWidgetContent(it.type, homeUiState) }
                WidgetFlowLayout(
                    isWide = visibleWidgets.map { it.effectiveSize() == WidgetSize.WIDE }.toImmutableList(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    visibleWidgets.forEach { widget ->
                        WidgetCardContent(
                            widget = widget,
                            homeUiState = homeUiState,
                            calcState = calcState,
                            onSelectCalcInput = onInputSelected,
                            onCalculatorBoundsChanged = { calculatorBounds = it },
                            onRemoveSuggestion = onRemoveSuggestion,
                            onClickSuggestion = onClickSuggestion,
                        )
                    }
                }
            }

            val footerAlpha = if (isCalcActive) 0f else 1f

            VerticalSpacer(16.dp)

            TertiaryButton(
                text = stringResource(R.string.widgets__add),
                onClick = onClickAddWidget,
                enabled = !isCalcActive,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier
                    .alpha(footerAlpha)
                    .testTag("WidgetsAdd")
            )

            VerticalSpacer(150.dp + imeBottomPadding)
        }

        calcState.activeInput?.let { activeInput ->
            CalculatorNumberPadBar(
                activeInput = activeInput,
                btcValue = calcState.btcValue,
                fiatValue = calcState.fiatValue,
                btcPrimaryDisplayUnit = calcState.displayUnit,
                onBtcChange = onBtcInputChanged,
                onFiatChange = onFiatInputChanged,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { numberPadBounds = it.boundsInRoot() }
            )
        }
    }
}

private fun Modifier.dismissCalculatorInputOnOutsideTap(
    isCalculatorInputActive: Boolean,
    pageBounds: Rect?,
    calculatorBounds: Rect?,
    numberPadBounds: Rect?,
    onDismiss: () -> Unit,
): Modifier = pointerInput(isCalculatorInputActive, pageBounds, calculatorBounds, numberPadBounds) {
    if (!isCalculatorInputActive) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val page = pageBounds ?: return@awaitEachGesture
        val calculator = calculatorBounds ?: return@awaitEachGesture
        val tapPositionInRoot = down.position.toRootPosition(page)
        var isTap = true
        var isPointerUp = false

        while (!isPointerUp) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val pointer = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                isTap = false
            }
            if (pointer.changedToUpIgnoreConsumed()) {
                isPointerUp = true
                val onNumberPad = numberPadBounds?.contains(tapPositionInRoot) == true
                if (isTap && !calculator.contains(tapPositionInRoot) && !onNumberPad) {
                    pointer.consume()
                    onDismiss()
                }
            }
        }
    }
}

private fun Offset.toRootPosition(bounds: Rect): Offset = this + Offset(bounds.left, bounds.top)

@Composable
private fun SuggestionsSection(
    suggestions: ImmutableList<Suggestion>,
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
                .testTag("SuggestionsWidget")
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
                            fadeInSpec = tween(durationMillis = AnimationConstants.DefaultDurationMillis),
                            fadeOutSpec = tween(durationMillis = AnimationConstants.DefaultDurationMillis),
                            placementSpec = tween(durationMillis = AnimationConstants.DefaultDurationMillis),
                        )
                )
            }
        }
    }
}

@Composable
private fun WidgetsOnboardingHint(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp)
    ) {
        Headline24(
            text = stringResource(R.string.widgets__onboarding_swipe).withAccent(),
            modifier = Modifier.weight(1f)
        )
        Image(
            painter = painterResource(R.drawable.swipe_instruction),
            contentDescription = null,
        )
    }
}

private fun hasWidgetContent(type: WidgetType, state: HomeUiState): Boolean = when (type) {
    WidgetType.BLOCK -> state.currentBlock != null
    WidgetType.NEWS -> state.currentArticle != null
    WidgetType.PRICE -> state.currentPrice != null
    WidgetType.WEATHER -> state.currentWeather != null
    WidgetType.FACTS -> state.currentFact != null
    WidgetType.CALCULATOR -> true
    WidgetType.SUGGESTIONS -> state.suggestions.isNotEmpty()
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun WidgetCardContent(
    widget: WidgetWithPosition,
    homeUiState: HomeUiState,
    calcState: CalculatorUiState,
    onSelectCalcInput: ((MoneyType) -> Unit)?,
    onCalculatorBoundsChanged: (Rect) -> Unit,
    onRemoveSuggestion: (Suggestion) -> Unit,
    onClickSuggestion: (Suggestion) -> Unit,
) {
    val small = widget.effectiveSize() == WidgetSize.SMALL
    when (widget.type) {
        WidgetType.BLOCK -> {
            val block = homeUiState.currentBlock
            when {
                block == null -> WidgetEditPlaceholder(small = small)
                small -> BlockCardSmall(
                    preferences = homeUiState.blocksPreferences,
                    block = block,
                    modifier = Modifier.testTag("BlocksWidget")
                )

                else -> BlockCard(
                    preferences = homeUiState.blocksPreferences,
                    block = block,
                    modifier = Modifier.fillMaxWidth().testTag("BlocksWidget")
                )
            }
        }

        WidgetType.NEWS -> {
            val article = homeUiState.currentArticle
            when {
                article == null -> WidgetEditPlaceholder(small = small)
                small -> HeadlineCardSmall(
                    showTime = homeUiState.headlinePreferences.showTime,
                    time = article.timeAgo,
                    headline = article.title,
                    link = article.link,
                    modifier = Modifier.testTag("NewsWidget")
                )

                else -> HeadlineCard(
                    showTime = homeUiState.headlinePreferences.showTime,
                    showSource = homeUiState.headlinePreferences.showSource,
                    headline = article.title,
                    time = article.timeAgo,
                    source = article.publisher,
                    link = article.link,
                    modifier = Modifier.fillMaxWidth().testTag("NewsWidget")
                )
            }
        }

        WidgetType.PRICE -> {
            val price = homeUiState.currentPrice
            when {
                price == null -> WidgetEditPlaceholder(small = small)
                small -> PriceCardSmall(
                    pricePreferences = homeUiState.pricePreferences,
                    priceDTO = price,
                    modifier = Modifier.testTag("PriceWidget")
                )

                else -> PriceCard(
                    pricePreferences = homeUiState.pricePreferences,
                    priceDTO = price,
                    modifier = Modifier.fillMaxWidth().testTag("PriceWidget")
                )
            }
        }

        WidgetType.WEATHER -> {
            val weather = homeUiState.currentWeather
            when {
                weather == null -> WidgetEditPlaceholder(small = small)
                small -> WeatherCardSmall(
                    weatherModel = weather,
                    preferences = homeUiState.weatherPreferences,
                    modifier = Modifier.testTag("WeatherWidget")
                )

                else -> WeatherCard(
                    weatherModel = weather,
                    preferences = homeUiState.weatherPreferences,
                    modifier = Modifier.fillMaxWidth().testTag("WeatherWidget")
                )
            }
        }

        WidgetType.FACTS -> {
            val fact = homeUiState.currentFact
            when {
                fact == null -> WidgetEditPlaceholder(small = small)
                small -> FactsCardSmall(
                    headline = fact,
                    modifier = Modifier.testTag("FactsWidget")
                )

                else -> FactsCard(
                    headline = fact,
                    modifier = Modifier.fillMaxWidth().testTag("FactsWidget")
                )
            }
        }

        WidgetType.CALCULATOR -> {
            val calcModifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onCalculatorBoundsChanged(it.boundsInRoot()) }
                .testTag("CalculatorWidget")
            if (small) {
                CalculatorCardSmall(
                    btcPrimaryDisplayUnit = calcState.displayUnit,
                    btcValue = calcState.btcValue,
                    fiatSymbol = calcState.currencySymbol,
                    fiatValue = calcState.fiatValue,
                    activeInput = calcState.activeInput,
                    onSelectInput = onSelectCalcInput,
                    modifier = calcModifier
                )
            } else {
                CalculatorCard(
                    btcPrimaryDisplayUnit = calcState.displayUnit,
                    btcValue = calcState.btcValue,
                    fiatSymbol = calcState.currencySymbol,
                    fiatName = calcState.selectedCurrency,
                    fiatValue = calcState.fiatValue,
                    activeInput = calcState.activeInput,
                    onSelectInput = onSelectCalcInput ?: {},
                    modifier = calcModifier
                )
            }
        }

        WidgetType.SUGGESTIONS -> {
            when {
                homeUiState.suggestions.isNotEmpty() -> SuggestionsSection(
                    suggestions = homeUiState.suggestions,
                    onRemoveSuggestion = onRemoveSuggestion,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.fillMaxWidth()
                )

                homeUiState.isEditingWidgets -> SuggestionsPreviewGrid(
                    onSuggestionClick = {},
                    modifier = Modifier.fillMaxWidth()
                )

                else -> WidgetEditPlaceholder(small = false)
            }
        }
    }
}

@Composable
private fun WidgetEditPlaceholder(
    small: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (small) Modifier else Modifier.height(WidgetCardDimens.COMPACT_CARD_SIZE.height))
            .clip(MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    hazeState: HazeState,
    profileDisplayName: String? = null,
    profileDisplayImageUri: String? = null,
    showProfileButton: Boolean = false,
    onClickProfile: () -> Unit = {},
    showEditWidgets: Boolean = false,
    isEditingWidgets: Boolean = false,
    onClickEditWidgetList: () -> Unit = {},
    onNavigateToAppStatus: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hazeEffect(state = hazeState) {
                mask = TopBarGradient
            }
            .background(TopBarGradient)
            .zIndex(1f)
    ) {
        TopAppBar(
            title = {
                if (showProfileButton) {
                    ProfileButton(
                        displayName = profileDisplayName,
                        displayImageUri = profileDisplayImageUri,
                        onClick = onClickProfile,
                    )
                }
            },
            actions = {
                AnimatedVisibility(showEditWidgets) {
                    IconButton(
                        onClick = onClickEditWidgetList,
                        modifier = Modifier.testTag("WidgetsEdit")
                    ) {
                        Icon(
                            painter = if (isEditingWidgets) {
                                painterResource(R.drawable.ic_check)
                            } else {
                                painterResource(R.drawable.ic_edit)
                            },
                            tint = Colors.White,
                            contentDescription = null,
                        )
                    }
                }
                AppStatus(
                    onClick = onNavigateToAppStatus,
                )
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.testTag("HeaderMenu")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_list),
                        tint = Colors.White,
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
private fun ProfileButton(
    displayName: String?,
    displayImageUri: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .clickableAlpha(onClick = onClick)
            .testTag("ProfileButton")
    ) {
        if (displayImageUri != null) {
            PubkyImage(
                uri = displayImageUri,
                size = 32.dp,
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Colors.Gray4)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_user_square),
                    contentDescription = null,
                    tint = Colors.White32,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Title(
            text = displayName ?: stringResource(R.string.profile__your_name),
            maxLines = 1,
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

private val previewBalances = BalanceState(
    totalOnchainSats = 165_000u,
    totalLightningSats = 45_000u,
)

private val previewWidgets = persistentListOf(
    WidgetWithPosition(type = WidgetType.SUGGESTIONS, position = 0),
    WidgetWithPosition(type = WidgetType.PRICE, position = 1),
    WidgetWithPosition(type = WidgetType.BLOCK, position = 2),
    WidgetWithPosition(type = WidgetType.NEWS, position = 3),
)

private val previewBlock = BlockModel(
    height = "761,405",
    time = "01:31:42 UTC",
    date = "01/2/2022",
    transactionCount = "2,175",
    size = "1,606kB",
    fees = "25 059 357",
)

private val previewArticle = ArticleModel(
    timeAgo = "21 minutes ago",
    title = "How Bitcoin changed El Salvador in more ways",
    publisher = "bitcoinmagazine.com",
    link = "bitcoinmagazine.com",
)

private val previewPrice = PriceDTO(
    widgets = listOf(
        PriceWidgetData(
            pair = TradingPair.BTC_USD,
            change = Change(isPositive = true, formatted = "$ 20,326"),
            price = "$20,326",
            pastValues = listOf(1.0, 2.0, 3.0, 4.0),
            period = GraphPeriod.ONE_DAY,
        ),
    ),
)

private val previewWeather = WeatherModel(
    condition = FeeCondition.GOOD,
    title = R.string.widgets__weather__condition__good__title,
    shortTitle = R.string.widgets__weather__condition__good__short_title,
    description = R.string.widgets__weather__condition__good__description,
    currentFee = "$ 0.52",
    currentFeeSats = 520L,
    currentFeeSatsFormatted = "520 \u20BF",
    nextBlockFee = "6 \u20BF/vByte",
    icon = "\u2600\uFE0F",
)

private val previewLatestActivities = previewActivityItems.take(3).toImmutableList()
private val previewBanners = ActivityBannerType.entries.map { BannerItem(type = it, title = "") }.toImmutableList()
private val previewSuggestions = Suggestion.entries.take(4).toImmutableList()
private val previewHardwareWalletBt = HwWallet(
    id = "trezor-1",
    name = "Trezor Safe 5",
    model = "Safe 5",
    transportType = TransportType.BLUETOOTH,
    isConnected = true,
    balanceSats = 10_562_411uL,
    activities = persistentListOf(),
)
private val previewHardwareWalletUsb = HwWallet(
    id = "trezor-2",
    name = "Trezor Model T",
    model = "Model T",
    transportType = TransportType.USB,
    isConnected = false,
    balanceSats = 2_735_180uL,
    activities = persistentListOf(),
)
private val previewHardwareWalletThird = HwWallet(
    id = "trezor-3",
    name = "Trezor Safe 3",
    model = "Safe 3",
    transportType = TransportType.BLUETOOTH,
    isConnected = true,
    balanceSats = 500_000uL,
    activities = persistentListOf(),
)

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithActivity() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithHardwareWallet() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    hardwareWallets = persistentListOf(previewHardwareWalletBt),
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances.copy(hardwareWallets = listOf(previewHardwareWalletBt.toBalance())),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithTwoHardwareWallets() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    hardwareWallets = persistentListOf(previewHardwareWalletBt, previewHardwareWalletUsb),
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances.copy(
                    hardwareWallets = listOf(
                        previewHardwareWalletBt.toBalance(),
                        previewHardwareWalletUsb.toBalance(),
                    )
                ),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithThreeHardwareWallets() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    hardwareWallets = persistentListOf(
                        previewHardwareWalletBt,
                        previewHardwareWalletUsb,
                        previewHardwareWalletThird,
                    ),
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances.copy(
                    hardwareWallets = listOf(
                        previewHardwareWalletBt.toBalance(),
                        previewHardwareWalletUsb.toBalance(),
                        previewHardwareWalletThird.toBalance(),
                    )
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
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = BalanceState(),
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithBanners() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                    banners = previewBanners,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithOnboardingHint() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                    showWidgetsOnboardingHint = true,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWidgetsPage() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                    currentPage = 1,
                    widgetsWithPosition = previewWidgets,
                    currentBlock = previewBlock,
                    currentArticle = previewArticle,
                    currentPrice = previewPrice,
                    currentWeather = previewWeather,
                    suggestions = previewSuggestions,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWidgetsEditing() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                    currentPage = 1,
                    isEditingWidgets = true,
                    widgetsWithPosition = previewWidgets,
                    currentBlock = previewBlock,
                    currentArticle = previewArticle,
                    currentPrice = previewPrice,
                    currentWeather = previewWeather,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true, device = PIXEL_TABLET)
@Composable
private fun PreviewTabletLandscape() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true, device = "spec:width=1200dp,height=2000dp,dpi=240")
@Composable
private fun PreviewTabletPortrait() {
    AppThemeSurface {
        Box {
            Content(
                isRefreshing = false,
                homeUiState = HomeUiState(
                    showWidgets = true,
                ),
                drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
                latestActivities = previewLatestActivities,
                balances = previewBalances,
            )
            TabBar()
        }
    }
}
