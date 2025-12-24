package to.bitkit.ui.nav.entries

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.models.WidgetType
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.screens.widgets.AddWidgetsScreen
import to.bitkit.ui.screens.widgets.WidgetsIntroScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksEditScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksPreviewScreen
import to.bitkit.ui.screens.widgets.blocks.BlocksViewModel
import to.bitkit.ui.screens.widgets.calculator.CalculatorPreviewScreen
import to.bitkit.ui.screens.widgets.calculator.CalculatorViewModel
import to.bitkit.ui.screens.widgets.facts.FactsEditScreen
import to.bitkit.ui.screens.widgets.facts.FactsPreviewScreen
import to.bitkit.ui.screens.widgets.facts.FactsViewModel
import to.bitkit.ui.screens.widgets.headlines.HeadlinesEditScreen
import to.bitkit.ui.screens.widgets.headlines.HeadlinesPreviewScreen
import to.bitkit.ui.screens.widgets.headlines.HeadlinesViewModel
import to.bitkit.ui.screens.widgets.price.PriceEditScreen
import to.bitkit.ui.screens.widgets.price.PricePreviewScreen
import to.bitkit.ui.screens.widgets.price.PriceViewModel
import to.bitkit.ui.screens.widgets.weather.WeatherEditScreen
import to.bitkit.ui.screens.widgets.weather.WeatherPreviewScreen
import to.bitkit.ui.screens.widgets.weather.WeatherViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.SettingsViewModel

/**
 * Widget flow entry providers for Navigation 3.
 */
@Suppress("LongMethod")
fun EntryProviderScope<NavKey>.widgetEntries(
    navigator: Navigator,
    currencyViewModel: CurrencyViewModel,
    settingsViewModel: SettingsViewModel,
) {
    // Widgets Intro
    entry<Routes.WidgetsIntro> {
        WidgetsIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenWidgetsIntro(true)
                navigator.navigate(Routes.AddWidget)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    // Add Widget
    entry<Routes.AddWidget> {
        AddWidgetsScreen(
            fiatSymbol = LocalCurrencies.current.currencySymbol,
            onWidgetSelected = { widgetType ->
                when (widgetType) {
                    WidgetType.NEWS -> navigator.navigate(Routes.Headlines)
                    WidgetType.FACTS -> navigator.navigate(Routes.Facts)
                    WidgetType.BLOCK -> navigator.navigate(Routes.Blocks)
                    WidgetType.WEATHER -> navigator.navigate(Routes.Weather)
                    WidgetType.PRICE -> navigator.navigate(Routes.Price)
                    WidgetType.CALCULATOR -> navigator.navigate(Routes.CalculatorPreview)
                }
            },
            onBackCLick = { navigator.goBack() },
        )
    }

    // Headlines Flow
    headlinesEntries(navigator)

    // Facts Flow
    factsEntries(navigator)

    // Blocks Flow
    blocksEntries(navigator)

    // Weather Flow
    weatherEntries(navigator)

    // Price Flow
    priceEntries(navigator)

    // Calculator Preview
    entry<Routes.CalculatorPreview> {
        CalculatorEntry(
            navigator = navigator,
            currencyViewModel = currencyViewModel,
        )
    }
}

private fun EntryProviderScope<NavKey>.headlinesEntries(navigator: Navigator) {
    entry<Routes.Headlines> {
        HeadlinesPreviewEntry(navigator)
    }

    entry<Routes.HeadlinesPreview> {
        HeadlinesPreviewEntry(navigator)
    }

    entry<Routes.HeadlinesEdit> {
        HeadlinesEditEntry(navigator)
    }
}

@Composable
private fun HeadlinesPreviewEntry(
    navigator: Navigator,
    viewModel: HeadlinesViewModel = hiltViewModel(),
) {
    HeadlinesPreviewScreen(
        headlinesViewModel = viewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
        navigateEditWidget = { navigator.navigate(Routes.HeadlinesEdit) },
    )
}

@Composable
private fun HeadlinesEditEntry(
    navigator: Navigator,
    viewModel: HeadlinesViewModel = hiltViewModel(),
) {
    HeadlinesEditScreen(
        headlinesViewModel = viewModel,
        onBack = { navigator.goBack() },
        navigatePreview = { navigator.navigate(Routes.HeadlinesPreview) },
    )
}

private fun EntryProviderScope<NavKey>.factsEntries(navigator: Navigator) {
    entry<Routes.Facts> {
        FactsPreviewEntry(navigator)
    }

    entry<Routes.FactsPreview> {
        FactsPreviewEntry(navigator)
    }

    entry<Routes.FactsEdit> {
        FactsEditEntry(navigator)
    }
}

@Composable
private fun FactsPreviewEntry(
    navigator: Navigator,
    viewModel: FactsViewModel = hiltViewModel(),
) {
    FactsPreviewScreen(
        factsViewModel = viewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
        navigateEditWidget = { navigator.navigate(Routes.FactsEdit) },
    )
}

@Composable
private fun FactsEditEntry(
    navigator: Navigator,
    viewModel: FactsViewModel = hiltViewModel(),
) {
    FactsEditScreen(
        factsViewModel = viewModel,
        onBack = { navigator.goBack() },
        navigatePreview = { navigator.navigate(Routes.FactsPreview) },
    )
}

private fun EntryProviderScope<NavKey>.blocksEntries(navigator: Navigator) {
    entry<Routes.Blocks> {
        BlocksPreviewEntry(navigator)
    }

    entry<Routes.BlocksPreview> {
        BlocksPreviewEntry(navigator)
    }

    entry<Routes.BlocksEdit> {
        BlocksEditEntry(navigator)
    }
}

@Composable
private fun BlocksPreviewEntry(
    navigator: Navigator,
    viewModel: BlocksViewModel = hiltViewModel(),
) {
    BlocksPreviewScreen(
        blocksViewModel = viewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
        navigateEditWidget = { navigator.navigate(Routes.BlocksEdit) },
    )
}

@Composable
private fun BlocksEditEntry(
    navigator: Navigator,
    viewModel: BlocksViewModel = hiltViewModel(),
) {
    BlocksEditScreen(
        blocksViewModel = viewModel,
        onBack = { navigator.goBack() },
        navigatePreview = { navigator.navigate(Routes.BlocksPreview) },
    )
}

private fun EntryProviderScope<NavKey>.weatherEntries(navigator: Navigator) {
    entry<Routes.Weather> {
        WeatherPreviewEntry(navigator)
    }

    entry<Routes.WeatherPreview> {
        WeatherPreviewEntry(navigator)
    }

    entry<Routes.WeatherEdit> {
        WeatherEditEntry(navigator)
    }
}

@Composable
private fun WeatherPreviewEntry(
    navigator: Navigator,
    viewModel: WeatherViewModel = hiltViewModel(),
) {
    WeatherPreviewScreen(
        weatherViewModel = viewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
        navigateEditWidget = { navigator.navigate(Routes.WeatherEdit) },
    )
}

@Composable
private fun WeatherEditEntry(
    navigator: Navigator,
    viewModel: WeatherViewModel = hiltViewModel(),
) {
    WeatherEditScreen(
        weatherViewModel = viewModel,
        onBack = { navigator.goBack() },
        navigatePreview = { navigator.navigate(Routes.WeatherPreview) },
    )
}

private fun EntryProviderScope<NavKey>.priceEntries(navigator: Navigator) {
    entry<Routes.Price> {
        PricePreviewEntry(navigator)
    }

    entry<Routes.PricePreview> {
        PricePreviewEntry(navigator)
    }

    entry<Routes.PriceEdit> {
        PriceEditEntry(navigator)
    }
}

@Composable
private fun PricePreviewEntry(
    navigator: Navigator,
    viewModel: PriceViewModel = hiltViewModel(),
) {
    PricePreviewScreen(
        priceViewModel = viewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
        navigateEditWidget = { navigator.navigate(Routes.PriceEdit) },
    )
}

@Composable
private fun PriceEditEntry(
    navigator: Navigator,
    priceViewModel: PriceViewModel = hiltViewModel(),
) {
    PriceEditScreen(
        viewModel = priceViewModel,
        onBack = { navigator.goBack() },
        navigatePreview = { navigator.navigate(Routes.PricePreview) },
    )
}

@Composable
private fun CalculatorEntry(
    navigator: Navigator,
    currencyViewModel: CurrencyViewModel,
    viewModel: CalculatorViewModel = hiltViewModel(),
) {
    CalculatorPreviewScreen(
        viewModel = viewModel,
        currencyViewModel = currencyViewModel,
        onClose = { navigator.navigateToHome() },
        onBack = { navigator.goBack() },
    )
}
