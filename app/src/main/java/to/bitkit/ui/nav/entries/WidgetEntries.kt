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

@Suppress("LongMethod")
fun EntryProviderScope<NavKey>.widgetEntries(
    navigator: Navigator,
    currencyViewModel: CurrencyViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Widgets.Intro> {
        WidgetsIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenWidgetsIntro(true)
                navigator.navigate(Routes.Widgets.Add)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Widgets.Add> {
        AddWidgetsScreen(
            fiatSymbol = LocalCurrencies.current.currencySymbol,
            onWidgetSelected = { widgetType ->
                when (widgetType) {
                    WidgetType.NEWS -> navigator.navigate(Routes.Widgets.Headlines.Main)
                    WidgetType.FACTS -> navigator.navigate(Routes.Widgets.Facts.Main)
                    WidgetType.BLOCK -> navigator.navigate(Routes.Widgets.Blocks.Main)
                    WidgetType.WEATHER -> navigator.navigate(Routes.Widgets.Weather.Main)
                    WidgetType.PRICE -> navigator.navigate(Routes.Widgets.Price.Main)
                    WidgetType.CALCULATOR -> navigator.navigate(Routes.Widgets.Calculator.Preview)
                }
            },
            onBackCLick = { navigator.goBack() },
        )
    }

    headlinesEntries(navigator)

    factsEntries(navigator)

    blocksEntries(navigator)

    weatherEntries(navigator)

    priceEntries(navigator)

    entry<Routes.Widgets.Calculator.Preview> {
        CalculatorEntry(
            navigator = navigator,
            currencyViewModel = currencyViewModel,
        )
    }
}

private fun EntryProviderScope<NavKey>.headlinesEntries(navigator: Navigator) {
    entry<Routes.Widgets.Headlines.Main> {
        HeadlinesPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Headlines.Preview> {
        HeadlinesPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Headlines.Edit> {
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
        navigateEditWidget = { navigator.navigate(Routes.Widgets.Headlines.Edit) },
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
        navigatePreview = { navigator.navigate(Routes.Widgets.Headlines.Preview) },
    )
}

private fun EntryProviderScope<NavKey>.factsEntries(navigator: Navigator) {
    entry<Routes.Widgets.Facts.Main> {
        FactsPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Facts.Preview> {
        FactsPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Facts.Edit> {
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
        navigateEditWidget = { navigator.navigate(Routes.Widgets.Facts.Edit) },
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
        navigatePreview = { navigator.navigate(Routes.Widgets.Facts.Preview) },
    )
}

private fun EntryProviderScope<NavKey>.blocksEntries(navigator: Navigator) {
    entry<Routes.Widgets.Blocks.Main> {
        BlocksPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Blocks.Preview> {
        BlocksPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Blocks.Edit> {
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
        navigateEditWidget = { navigator.navigate(Routes.Widgets.Blocks.Edit) },
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
        navigatePreview = { navigator.navigate(Routes.Widgets.Blocks.Preview) },
    )
}

private fun EntryProviderScope<NavKey>.weatherEntries(navigator: Navigator) {
    entry<Routes.Widgets.Weather.Main> {
        WeatherPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Weather.Preview> {
        WeatherPreviewEntry(navigator)
    }

    entry<Routes.Widgets.Weather.Edit> {
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
        navigateEditWidget = { navigator.navigate(Routes.Widgets.Weather.Edit) },
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
        navigatePreview = { navigator.navigate(Routes.Widgets.Weather.Preview) },
    )
}

private fun EntryProviderScope<NavKey>.priceEntries(navigator: Navigator) {
    entry<Routes.Widgets.Price.Main> {
        PricePreviewEntry(navigator)
    }

    entry<Routes.Widgets.Price.Preview> {
        PricePreviewEntry(navigator)
    }

    entry<Routes.Widgets.Price.Edit> {
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
        navigateEditWidget = { navigator.navigate(Routes.Widgets.Price.Edit) },
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
        navigatePreview = { navigator.navigate(Routes.Widgets.Price.Preview) },
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
