package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.models.WidgetType
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.navigateTo
import to.bitkit.ui.screens.widgets.AddWidgetsSheetContent
import to.bitkit.ui.screens.widgets.WidgetsGalleryViewModel
import to.bitkit.ui.screens.widgets.blocks.BlocksEditScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksPreviewScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksViewModel
import to.bitkit.ui.screens.widgets.calculator.CalculatorPreviewScreen
import to.bitkit.ui.screens.widgets.components.widgetSheetPage
import to.bitkit.ui.screens.widgets.components.widgetSheetSurface
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
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

@Composable
fun WidgetsSheet(
    sheet: Sheet.Widgets,
    app: AppViewModel,
    fiatSymbol: String,
    showWidgets: Boolean,
    onNavigateHomeWidgets: () -> Unit,
    onOpenWidgetsSettings: () -> Unit,
) {
    val navController = rememberNavController()
    val onDismiss = app::hideSheet
    val onDone = {
        app.hideSheet()
        onNavigateHomeWidgets()
    }

    WidgetsSheetContent(
        startRoute = sheet.route,
        navController = navController,
        fiatSymbol = fiatSymbol,
        showWidgets = showWidgets,
        onDismiss = onDismiss,
        onDone = onDone,
        onOpenWidgetsSettings = {
            app.hideSheet()
            onOpenWidgetsSettings()
        },
    )
}

@Composable
private fun WidgetsSheetContent(
    startRoute: WidgetsRoute,
    navController: NavHostController,
    fiatSymbol: String,
    showWidgets: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onOpenWidgetsSettings: () -> Unit,
) {
    val galleryViewModelStoreOwner = rememberSheetViewModelStoreOwner()
    val galleryScrollState = rememberScrollState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isGalleryRoute = navBackStackEntry?.destination?.hasRoute<WidgetsRoute.Gallery>() == true
    val widgetFlowKey = navBackStackEntry?.destination?.widgetFlowKey()
        ?: startRoute.widgetFlowKey().takeIf { navBackStackEntry == null }
    val widgetViewModelStoreOwner = rememberWidgetFlowViewModelStoreOwner(widgetFlowKey)

    LaunchedEffect(isGalleryRoute) {
        galleryScrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE)
            .widgetSheetSurface()
            .testTag("widgets_navigation_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize()
        ) {
            composableWithDefaultTransitions<WidgetsRoute.Gallery> {
                val galleryViewModel = hiltViewModel<WidgetsGalleryViewModel>(
                    viewModelStoreOwner = galleryViewModelStoreOwner
                )
                val weather by galleryViewModel.currentWeather.collectAsStateWithLifecycle()
                val block by galleryViewModel.currentBlock.collectAsStateWithLifecycle()
                val article by galleryViewModel.currentArticle.collectAsStateWithLifecycle()
                val fact by galleryViewModel.currentFact.collectAsStateWithLifecycle()
                val price by galleryViewModel.currentPrice.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    galleryViewModel.refreshOnDisplay()
                }

                AddWidgetsSheetContent(
                    fiatSymbol = fiatSymbol,
                    showWidgets = showWidgets,
                    onWidgetSelected = {
                        navController.navigateTo(it.toWidgetsPreviewRoute())
                    },
                    onEnableInSettingsClick = onOpenWidgetsSettings,
                    galleryScrollState = galleryScrollState,
                    weatherModel = weather,
                    article = article,
                    block = block,
                    fact = fact,
                    price = price,
                    modifier = Modifier
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.PricePreview> {
                val priceViewModel = hiltViewModel<PriceViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                PricePreviewScreen(
                    priceViewModel = priceViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.PriceEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.PriceEdit> {
                val priceViewModel = hiltViewModel<PriceViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                PriceEditScreen(
                    viewModel = priceViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.WeatherPreview> {
                val weatherViewModel = hiltViewModel<WeatherViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                WeatherPreviewScreen(
                    weatherViewModel = weatherViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.WeatherEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.WeatherEdit> {
                val weatherViewModel = hiltViewModel<WeatherViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                WeatherEditScreen(
                    weatherViewModel = weatherViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.BlocksPreview> {
                val blocksViewModel = hiltViewModel<BlocksViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                BlocksPreviewScreen(
                    blocksViewModel = blocksViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.BlocksEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.BlocksEdit> {
                val blocksViewModel = hiltViewModel<BlocksViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                BlocksEditScreen(
                    blocksViewModel = blocksViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.HeadlinesPreview> {
                val headlinesViewModel = hiltViewModel<HeadlinesViewModel>(
                    viewModelStoreOwner = widgetViewModelStoreOwner
                )

                HeadlinesPreviewScreen(
                    headlinesViewModel = headlinesViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.HeadlinesEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.HeadlinesEdit> {
                val headlinesViewModel = hiltViewModel<HeadlinesViewModel>(
                    viewModelStoreOwner = widgetViewModelStoreOwner
                )

                HeadlinesEditScreen(
                    headlinesViewModel = headlinesViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.FactsPreview> {
                val factsViewModel = hiltViewModel<FactsViewModel>(viewModelStoreOwner = widgetViewModelStoreOwner)

                FactsPreviewScreen(
                    factsViewModel = factsViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.CalculatorPreview> {
                CalculatorPreviewScreen(
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.SuggestionsPreview> {
                val suggestionsViewModel = hiltViewModel<SuggestionsViewModel>(
                    viewModelStoreOwner = widgetViewModelStoreOwner
                )

                SuggestionsPreviewScreen(
                    suggestionsViewModel = suggestionsViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
        }
    }
}

@Composable
private fun rememberWidgetFlowViewModelStoreOwner(widgetFlowKey: WidgetFlowKey?): ViewModelStoreOwner {
    return rememberViewModelStoreOwner(key = widgetFlowKey)
}

@Composable
private fun rememberSheetViewModelStoreOwner(): ViewModelStoreOwner {
    return rememberViewModelStoreOwner(key = "sheet")
}

@Composable
private fun rememberViewModelStoreOwner(key: Any?): ViewModelStoreOwner {
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    }
    val parentFactoryOwner = checkNotNull(parentOwner as? HasDefaultViewModelProviderFactory) {
        "WidgetsSheet requires a default ViewModelProvider.Factory owner"
    }
    val viewModelStore = remember(key) { ViewModelStore() }
    DisposableEffect(viewModelStore) {
        onDispose { viewModelStore.clear() }
    }

    return remember(viewModelStore, parentFactoryOwner) {
        SheetViewModelStoreOwner(viewModelStore, parentFactoryOwner)
    }
}

private class SheetViewModelStoreOwner(
    private val store: ViewModelStore,
    private val factoryOwner: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = store
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = factoryOwner.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras
        get() = factoryOwner.defaultViewModelCreationExtras
}

private fun NavHostController.popOrDismiss(onDismiss: () -> Unit) {
    if (!popBackStack()) {
        onDismiss()
    }
}

fun WidgetType.toWidgetsPreviewRoute(): WidgetsRoute = when (this) {
    WidgetType.BLOCK -> WidgetsRoute.BlocksPreview
    WidgetType.CALCULATOR -> WidgetsRoute.CalculatorPreview
    WidgetType.FACTS -> WidgetsRoute.FactsPreview
    WidgetType.NEWS -> WidgetsRoute.HeadlinesPreview
    WidgetType.PRICE -> WidgetsRoute.PricePreview
    WidgetType.WEATHER -> WidgetsRoute.WeatherPreview
    WidgetType.SUGGESTIONS -> WidgetsRoute.SuggestionsPreview
}

private fun WidgetsRoute.widgetFlowKey(): WidgetFlowKey? = when (this) {
    WidgetsRoute.PricePreview,
    WidgetsRoute.PriceEdit,
    -> WidgetFlowKey.PRICE

    WidgetsRoute.WeatherPreview,
    WidgetsRoute.WeatherEdit,
    -> WidgetFlowKey.WEATHER

    WidgetsRoute.BlocksPreview,
    WidgetsRoute.BlocksEdit,
    -> WidgetFlowKey.BLOCKS

    WidgetsRoute.HeadlinesPreview,
    WidgetsRoute.HeadlinesEdit,
    -> WidgetFlowKey.HEADLINES

    WidgetsRoute.FactsPreview -> WidgetFlowKey.FACTS
    WidgetsRoute.SuggestionsPreview -> WidgetFlowKey.SUGGESTIONS
    WidgetsRoute.Gallery,
    WidgetsRoute.CalculatorPreview,
    -> null
}

private fun NavDestination.widgetFlowKey(): WidgetFlowKey? = when {
    hasRoute<WidgetsRoute.PricePreview>() || hasRoute<WidgetsRoute.PriceEdit>() -> WidgetFlowKey.PRICE
    hasRoute<WidgetsRoute.WeatherPreview>() || hasRoute<WidgetsRoute.WeatherEdit>() -> WidgetFlowKey.WEATHER
    hasRoute<WidgetsRoute.BlocksPreview>() || hasRoute<WidgetsRoute.BlocksEdit>() -> WidgetFlowKey.BLOCKS
    hasRoute<WidgetsRoute.HeadlinesPreview>() || hasRoute<WidgetsRoute.HeadlinesEdit>() -> WidgetFlowKey.HEADLINES
    hasRoute<WidgetsRoute.FactsPreview>() -> WidgetFlowKey.FACTS
    hasRoute<WidgetsRoute.SuggestionsPreview>() -> WidgetFlowKey.SUGGESTIONS
    else -> null
}

private enum class WidgetFlowKey {
    PRICE,
    WEATHER,
    BLOCKS,
    HEADLINES,
    FACTS,
    SUGGESTIONS,
}

sealed interface WidgetsRoute {
    @Serializable
    data object Gallery : WidgetsRoute

    @Serializable
    data object PricePreview : WidgetsRoute

    @Serializable
    data object PriceEdit : WidgetsRoute

    @Serializable
    data object WeatherPreview : WidgetsRoute

    @Serializable
    data object WeatherEdit : WidgetsRoute

    @Serializable
    data object BlocksPreview : WidgetsRoute

    @Serializable
    data object BlocksEdit : WidgetsRoute

    @Serializable
    data object HeadlinesPreview : WidgetsRoute

    @Serializable
    data object HeadlinesEdit : WidgetsRoute

    @Serializable
    data object FactsPreview : WidgetsRoute

    @Serializable
    data object CalculatorPreview : WidgetsRoute

    @Serializable
    data object SuggestionsPreview : WidgetsRoute
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sheetHeight(SheetSize.LARGE, isModal = true)
                    .widgetSheetSurface()
            ) {
                AddWidgetsSheetContent(
                    showWidgets = true,
                )
            }
        }
    }
}
