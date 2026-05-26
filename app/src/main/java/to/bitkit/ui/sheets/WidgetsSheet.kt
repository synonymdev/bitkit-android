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
    val sheetViewModelStoreOwner = rememberSheetViewModelStoreOwner()
    val galleryScrollState = rememberScrollState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isGalleryRoute = navBackStackEntry?.destination?.hasRoute<WidgetsRoute.Gallery>() == true

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
                    viewModelStoreOwner = sheetViewModelStoreOwner
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
                val priceViewModel = hiltViewModel<PriceViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                PricePreviewScreen(
                    priceViewModel = priceViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.PriceEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.PriceEdit> {
                val priceViewModel = hiltViewModel<PriceViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                PriceEditScreen(
                    viewModel = priceViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.WeatherPreview> {
                val weatherViewModel = hiltViewModel<WeatherViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                WeatherPreviewScreen(
                    weatherViewModel = weatherViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.WeatherEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.WeatherEdit> {
                val weatherViewModel = hiltViewModel<WeatherViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                WeatherEditScreen(
                    weatherViewModel = weatherViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.BlocksPreview> {
                val blocksViewModel = hiltViewModel<BlocksViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                BlocksPreviewScreen(
                    blocksViewModel = blocksViewModel,
                    onClose = onDone,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigateEditWidget = { navController.navigateTo(WidgetsRoute.BlocksEdit) },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.BlocksEdit> {
                val blocksViewModel = hiltViewModel<BlocksViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

                BlocksEditScreen(
                    blocksViewModel = blocksViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.HeadlinesPreview> {
                val headlinesViewModel = hiltViewModel<HeadlinesViewModel>(
                    viewModelStoreOwner = sheetViewModelStoreOwner
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
                    viewModelStoreOwner = sheetViewModelStoreOwner
                )

                HeadlinesEditScreen(
                    headlinesViewModel = headlinesViewModel,
                    onBack = { navController.popOrDismiss(onDismiss) },
                    navigatePreview = { navController.popBackStack() },
                    modifier = Modifier.widgetSheetPage()
                )
            }
            composableWithDefaultTransitions<WidgetsRoute.FactsPreview> {
                val factsViewModel = hiltViewModel<FactsViewModel>(viewModelStoreOwner = sheetViewModelStoreOwner)

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
                    viewModelStoreOwner = sheetViewModelStoreOwner
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
private fun rememberSheetViewModelStoreOwner(): ViewModelStoreOwner {
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    }
    val parentFactoryOwner = checkNotNull(parentOwner as? HasDefaultViewModelProviderFactory) {
        "WidgetsSheet requires a default ViewModelProvider.Factory owner"
    }
    val viewModelStore = remember { ViewModelStore() }
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
